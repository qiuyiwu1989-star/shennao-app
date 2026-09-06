package com.qiuyiwu.shennao.ble

/*
 * 从录音笔把文件导进来。**纯逻辑**——不碰任何 android.*，能在 JVM 上跑。
 *
 * 为什么坚持纯：真机验证很贵（要有设备、要有电、要有录音），而这里的判断
 * 恰恰是最容易写错的部分——什么时候该重试、断了从哪续、什么算传完。
 *
 * 协议时序（真机验过的，别照厂商文档改）：
 *   列文件  2-0 → 若干 2-1 → 2-18 结束
 *   下载    2-2(offset) → 2-3 开始(带总长) → 若干 2-4 数据 → 2-5 结束(带结束码)
 *
 * 断点续传是刚需不是锦上添花：实测带宽 27 KB/s，一小时录音 7.2 MB 要传四分半，
 * 中途断是常态。
 */

/** 导入过程中的状态。界面照着念，不许自己编。 */
sealed class ImportState {
    object Idle : ImportState()
    object Listing : ImportState()
    data class Listed(val files: List<FileEntry>) : ImportState()

    data class Downloading(val name: String, val got: Long, val total: Long) : ImportState() {
        /** 总长未知时（还没收到 2-3）返回 null——界面该显示不确定进度，而不是 0% */
        val fraction: Float? get() = if (total > 0) got.toFloat() / total else null
    }

    data class Done(val name: String, val bytes: ByteArray) : ImportState() {
        override fun equals(other: Any?) =
            other is Done && other.name == name && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * name.hashCode() + bytes.contentHashCode()
    }

    /**
     * 失败要能分辨来源：
     *   deviceSaid=true  设备明确拒绝（文件不存在等）→ 换个候选名再试，重连没用
     *   deviceSaid=false 断线 / 超时 → 该重连，换名字没用
     * 混成一种的话，两边都会用错误的方式重试。
     */
    data class Failed(val reason: String, val deviceSaid: Boolean) : ImportState()
}

class Importer(
    private val transport: BleTransport,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    var state: ImportState = ImportState.Idle
        private set

    private val listBuf = mutableListOf<FileEntry>()

    /*
     * 实测速度。从**第一包数据**到传完，只算数据本身——把请求往返、设备找文件的时间
     * 算进去会让数字偏低，而我们要拿它去比较三个旋钮的效果，得可比。
     */
    private var dataStartedAt = 0L
    private var dataBytes = 0L
    /** 上一次传完的实测速度（KB/s）。没传完过就是 null。 */
    var lastKbps: Double? = null
        private set
    private val data = java.io.ByteArrayOutputStream()

    /** 当前下载的候选名队列。第一个失败就试下一个。 */
    private var candidates: List<String> = emptyList()
    private var candidateIdx = 0
    private var total = 0L
    private var lastHeardAt = 0L
    private var seq = 0

    // ---- 发起 ----

    fun startListing(): Boolean {
        listBuf.clear()
        state = ImportState.Listing
        lastHeardAt = now()
        return send(Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.LIST_REQ, seq = nextSeq()))
    }

    /**
     * 开始下载。`from` 不为 0 表示续传。
     *
     * 续传时**不清空已收的数据**——那正是续传的意义。清空的话每次断线都要重头，
     * 而 27 KB/s 的链路上重头意味着再等四分钟。
     */
    fun startDownload(entry: FileEntry, from: Long = 0): Boolean {
        candidates = entry.candidates
        candidateIdx = 0
        if (from == 0L) { data.reset(); total = 0 }
        return requestCurrentCandidate(from)
    }

    private fun requestCurrentCandidate(from: Long): Boolean {
        val name = candidates.getOrNull(candidateIdx) ?: run {
            state = ImportState.Failed("这个文件的几种名字设备都说没有", deviceSaid = true)
            return false
        }
        state = ImportState.Downloading(name, data.size().toLong(), total)
        lastHeardAt = now()
        return runCatching {
            send(Proto.buildImportRequest(name, offset = from, seq = nextSeq()))
        }.getOrElse {
            // 文件名超长之类：这是我们自己的问题，不是设备的
            state = ImportState.Failed(it.message ?: "请求构造失败", deviceSaid = false)
            false
        }
    }

    /** 断线之后接着传。从已经收到的字节数继续。 */
    fun resume(): Boolean {
        val name = candidates.getOrNull(candidateIdx)
            ?: return false.also { state = ImportState.Failed("没有可续的下载", deviceSaid = false) }
        lastHeardAt = now()
        state = ImportState.Downloading(name, data.size().toLong(), total)
        return runCatching {
            send(Proto.buildImportRequest(name, offset = data.size().toLong(), seq = nextSeq()))
        }.getOrElse { false }
    }

    // ---- 收 ----

    fun onFrame(f: Proto.Frame) {
        lastHeardAt = now()
        if (f.type != Proto.T.FILE) return
        when (f.cmd) {
            Proto.FileCmd.LIST_DATA -> if (state is ImportState.Listing) {
                listBuf += FileListDecoder.decode(f.body)
            }
            Proto.FileCmd.LIST_DONE -> if (state is ImportState.Listing) {
                state = ImportState.Listed(listBuf.toList())
            }
            Proto.FileCmd.IMPORT_BEGIN -> {
                // body 前 4 字节是总长度（大端，和文件列表同一套）
                if (f.body.size >= 4) total = be32(f.body)
                (state as? ImportState.Downloading)?.let {
                    state = it.copy(got = data.size().toLong(), total = total)
                }
            }
            Proto.FileCmd.IMPORT_DATA -> {
                if (dataStartedAt == 0L) { dataStartedAt = now(); dataBytes = 0 }
                dataBytes += f.body.size
                data.write(f.body)
                (state as? ImportState.Downloading)?.let {
                    state = it.copy(got = data.size().toLong(), total = total)
                }
            }
            Proto.FileCmd.IMPORT_END -> {
                val code = f.body.firstOrNull()?.toInt()?.and(0xFF) ?: 3
                if (code == 0) {
                    val name = candidates.getOrNull(candidateIdx) ?: "unknown"
                    LinkTuning.kbps(dataBytes, now() - dataStartedAt)?.let { lastKbps = it }
                    dataStartedAt = 0
                    state = ImportState.Done(name, data.toByteArray())
                } else if (code == 1 && candidateIdx + 1 < candidates.size) {
                    // 「文件不存在」= 这个候选名不对（列表里的名字是 20B 截断的）。
                    // 换下一个候选**从头开始**：换了文件，之前收的字节就不是它的。
                    candidateIdx++
                    data.reset(); total = 0; dataStartedAt = 0
                    requestCurrentCandidate(0)
                } else {
                    state = ImportState.Failed(
                        Proto.IMPORT_END_MEANING[code] ?: "设备停止了传输（码 $code）",
                        deviceSaid = true,
                    )
                }
            }
        }
    }

    /**
     * 检查超时。**设备可能一声不吭**——不主动查的话界面会永远转圈。
     *
     * 超时算「断线」而不是「设备拒绝」：它可能只是走远了，重连是对的做法。
     */
    fun tick(): ImportState {
        val idle = now() - lastHeardAt
        val limit = when (state) {
            is ImportState.Listing -> LIST_TIMEOUT_MS
            is ImportState.Downloading -> DATA_TIMEOUT_MS
            else -> return state
        }
        if (idle > limit) {
            /*
             * 超时提示要给出路。「设备 8 秒没有回应」是一句准确但**没用**的话——
             * 用户不知道该等、该重连、还是该去动录音笔。
             *
             * 2026-09-01 用户看到这句时，真正的原因是我这边的 GATT 队列被一个
             * 合成操作卡死了，一个字节都没发出去。所以这里也提一句「重连」——
             * 客户端自己出问题时，重连是唯一能把状态清干净的动作。
             */
            state = ImportState.Failed(
                "录音笔 ${idle / 1000} 秒没有回应。\n" +
                    "可以先按一下它的按键（它空闲几分钟就会休眠），再点重连。\n" +
                    "如果反复如此，退出这一页重新进——连接状态可能没清干净。",
                deviceSaid = false,
            )
        }
        return state
    }

    /** 已经收到多少字节。续传要用它。 */
    val received: Long get() = data.size().toLong()

    private fun send(frame: ByteArray): Boolean {
        if (!transport.write(frame)) {
            state = ImportState.Failed("发不出去（没连上）", deviceSaid = false)
            return false
        }
        return true
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFF; return seq }

    private fun be32(b: ByteArray): Long =
        ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
        ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

    companion object {
        /** 设备一声不吭多久算超时。列表比下载短——列表是它立刻能答的。 */
        const val LIST_TIMEOUT_MS = 8_000L
        const val DATA_TIMEOUT_MS = 15_000L
    }
}
