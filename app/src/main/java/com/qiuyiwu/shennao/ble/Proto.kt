package com.qiuyiwu.shennao.ble

/*
 * CB08 录音笔的 BLE 协议。
 *
 * **从 Mac 端已验证的实现逐字节移植**（clients/mac-recorder/.../Protocol.swift），
 * 不是照着厂商文档重写——文档在好几处是错的，那些坑是真机上一条条试出来的：
 *
 *   · 删除单个文件（2-8）文档说参数是「28B 列表条目」，设备一律回 01 拒绝。
 *     实际要的和下载请求 2-2 完全一样：offset 4B LE + 文件名 24B 补零，且要带完整扩展名。
 *   · 帧头的 LEN/CRC 是**小端**，而文件列表里的 time/size 是**大端**。同一个协议里两种字节序。
 *   · 下载请求必须整帧 36B **一次 GATT 写完**，拆包设备会把文件名解析错。
 *
 * 所以这里保留了同一套测试向量（含厂商文档 7.3 那条真机成功帧）——
 * 移植对不对，不看代码像不像，看字节一不一样。
 */
object Proto {
    const val MAGIC: Byte = 0x5A
    const val HEADER_LEN = 6
    /** 文件列表里的名字字段，会把扩展名截断 */
    const val NAME_FIELD_LEN = 20
    /** 下载/删除请求里的名字字段 */
    const val FILENAME_FIELD_LEN = 24
    const val ENTRY_LEN = 28

    private fun u16(short: Int) = String.format("0000%04x-0000-1000-8000-00805f9b34fb", short)
    val SERVICE_MAIN: String = u16(0xAE20)
    val CHAR_WRITE: String = u16(0xAE21)    // App → 设备
    val CHAR_NOTIFY: String = u16(0xAE22)   // 设备 → App：控制 / 音频 / 列表 / 文件
    val CHAR_KEY: String = u16(0xAE23)      // 设备 → App：按键与录音状态

    object T { const val CTRL = 0; const val AUDIO = 1; const val FILE = 2; const val KEY = 3 }

    object FileCmd {
        const val LIST_REQ = 0; const val LIST_DATA = 1
        const val IMPORT_REQ = 2; const val IMPORT_BEGIN = 3
        const val IMPORT_DATA = 4; const val IMPORT_END = 5
        const val IMPORT_ABORT = 7
        const val DEL_ONE = 8            // 破坏性
        const val DEL_ONE_ACK = 13
        const val IMPORT_RANGE = 12
        const val LIST_DONE = 18
        // 故意不定义 delAll(9)：批量删除发出去无法挽回，且旧固件不回应答。
    }

    /**
     * 控制命令（TYPE=0）。**2026-09-05 按 CB08 协议 §4 新写，没有已验证的参考实现**——
     * Mac 端的 Protocol.swift 只定义了 ctrl=0 这个常量，从没发过这几条。
     * 唯一的依据是协议文档和 lomehong/record 那份真机跑通的 Python（无 license，只照文档写）。
     *
     * 都是只读查询，发错了顶多设备不理。**解出来的值在真机上核对之前只能当参考**，
     * 界面上解析不出就显示「—」，不编数。
     */
    object CtrlCmd {
        const val SYNC_TIME = 0      // App→Dev：year:2B LE + month/day/hour/minute/second 各 1B
        const val CAP_REQ = 1;  const val CAP_ACK = 2     // remain:4B LE + total:4B LE
        const val BAT_REQ = 3;  const val BAT_ACK = 4     // 1B：0~100；110 = 充电中
        const val FW_REQ = 10;  const val FW_ACK = 11     // 6B ASCII，如 V1.0.0
    }

    /**
     * 同步时间。连上就发一次：灵魂卡的文件名里带录音时刻（note20260828-205856），
     * 它的钟走偏了，深脑那边的时间轴就跟着偏——而那条时间轴是整套判断的坐标。
     */
    fun buildSyncTime(epochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): ByteArray {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        val y = c.get(java.util.Calendar.YEAR)
        val p = byteArrayOf(
            (y and 0xFF).toByte(), (y shr 8).toByte(),
            (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(),
            c.get(java.util.Calendar.SECOND).toByte(),
        )
        return buildFrame(T.CTRL, CtrlCmd.SYNC_TIME, p)
    }

    object KeyCmd {
        const val STATUS_REQ = 19; const val STATUS_ACK = 20   // 1录音中 2未录音 3暂停
        const val CUR_NAME_REQ = 23; const val CUR_NAME_ACK = 24
    }

    val IMPORT_END_MEANING = mapOf(
        0 to "完成", 1 to "文件不存在", 2 to "offset 过大", 3 to "其他原因停止",
    )

    /** CRC-16/XMODEM：poly 0x1021, init 0x0000, 不反转, xorout 0x0000 */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    data class Frame(val seq: Int, val data: ByteArray) {
        val type: Int? get() = data.firstOrNull()?.toInt()?.and(0xFF)
        /** 只有 TYPE 一字节时按 ACK 处理 */
        val cmd: Int? get() = if (data.size >= 2) data[1].toInt() and 0xFF else null
        val body: ByteArray get() = if (data.size > 2) data.copyOfRange(2, data.size) else ByteArray(0)

        override fun equals(other: Any?) = other is Frame && other.seq == seq && other.data.contentEquals(data)
        override fun hashCode() = 31 * seq + data.contentHashCode()
    }

    /** CRC 的输入是 **LEN 的两个原始字节 + DATA**，不含 MAGIC/SEQ/CRC 自己。 */
    fun buildFrame(type: Int, cmd: Int, params: ByteArray = ByteArray(0), seq: Int = 0): ByteArray {
        val data = byteArrayOf(type.toByte(), cmd.toByte()) + params
        val len = data.size
        val lenBytes = byteArrayOf((len and 0xFF).toByte(), (len shr 8).toByte())   // LE
        val crc = crc16(lenBytes + data)
        return byteArrayOf(
            MAGIC, seq.toByte(), (crc and 0xFF).toByte(), (crc shr 8).toByte(),
        ) + lenBytes + data
    }

    class ProtoError(message: String) : Exception(message)

    private fun paddedName(filename: String): ByteArray {
        val bytes = filename.toByteArray(Charsets.UTF_8)
        if (bytes.size > FILENAME_FIELD_LEN) {
            throw ProtoError("文件名超过 ${FILENAME_FIELD_LEN}B: $filename")
        }
        return bytes + ByteArray(FILENAME_FIELD_LEN - bytes.size)
    }

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    fun buildImportRequest(filename: String, offset: Long = 0, seq: Int = 0): ByteArray {
        val frame = buildFrame(T.FILE, FileCmd.IMPORT_REQ, le32(offset) + paddedName(filename), seq)
        // 36B 必须一次 GATT 写完——拆包设备会把文件名解析错
        check(frame.size == 36) { "下载请求帧长必须 36B，实际 ${frame.size}" }
        return frame
    }

    fun buildImportRange(filename: String, start: Long, end: Long, seq: Int = 0): ByteArray =
        buildFrame(T.FILE, FileCmd.IMPORT_RANGE, le32(start) + le32(end) + paddedName(filename), seq)

    /**
     * 2-8 删除单个文件。**破坏性且不可逆。**
     *
     * 真机实测：厂商文档说参数是「与文件列表相同的 28B 条目」，那是错的——
     * 设备一律回应答码 01 拒绝。实际要的是和下载请求 2-2 完全相同的格式。
     */
    fun buildDeleteOne(filename: String, seq: Int = 0): ByteArray =
        buildFrame(T.FILE, FileCmd.DEL_ONE, le32(0) + paddedName(filename), seq)
}
