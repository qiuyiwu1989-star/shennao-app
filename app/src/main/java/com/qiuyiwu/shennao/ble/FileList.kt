package com.qiuyiwu.shennao.ble

/**
 * 文件列表条目。
 *
 * **注意字节序**：帧头的 LEN/CRC 是小端，而这里的 time/size 是**大端**。
 * 同一个协议里两种字节序，抄错一处就是一堆看起来很大的乱数。
 */
data class FileEntry(
    /** 录音时长，秒 */
    val time: Long,
    /** 设备内压缩体积，字节 */
    val size: Long,
    /** 20B 截断名，扩展名可能不全 */
    val name: String,
) {
    val base: String get() = if (name.endsWith(".")) name.dropLast(1) else name

    /**
     * 列表字段只有 20B，`note20260828-205856.opus` 会被截成 `note20260828-205856.`。
     * 下载和删除都必须用重建出的完整名。
     *
     * **优先 .opus**：设备转码出的 wav 走 BLE 会慢一个数量级
     * （1 小时录音 opus 7.2MB vs wav 115MB，而实测带宽只有 27KB/s）。
     */
    val candidates: List<String> get() {
        val b = base
        val out = mutableListOf<String>()
        for (ext in listOf(".opus", ".wav")) {
            out += if (b.lowercase().endsWith(ext)) b else b + ext
        }
        out += name
        return out.distinct()
    }
}

object FileListDecoder {
    /** body = count:4B **大端** + N × 28B */
    fun decode(body: ByteArray): List<FileEntry> {
        if (body.size < 4) return emptyList()
        val declared = be32(body, 0).toInt()
        val available = (body.size - 4) / Proto.ENTRY_LEN
        // 声明数与实际字节不符时**以实际为准**——设备偶尔会多报，
        // 照着声明数去读会越界，而越界读出来的是垃圾条目。
        val count = minOf(declared, available)
        val out = ArrayList<FileEntry>(maxOf(count, 0))
        for (i in 0 until count) {
            val off = 4 + i * Proto.ENTRY_LEN
            val raw = body.copyOfRange(off + 8, off + 8 + Proto.NAME_FIELD_LEN)
            val nameBytes = raw.takeWhile { it != 0.toByte() }.toByteArray()
            out += FileEntry(
                time = be32(body, off),
                size = be32(body, off + 4),
                name = String(nameBytes, Charsets.UTF_8),
            )
        }
        return out
    }

    private fun be32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}
