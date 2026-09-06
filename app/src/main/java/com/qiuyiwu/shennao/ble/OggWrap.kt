package com.qiuyiwu.shennao.ble

/**
 * 把设备吐的**裸 OPUS 定长包**封成 Ogg/Opus。
 *
 * ## 为什么非做不可
 *
 * CB08 传过来的不是一个 .opus 文件，是**连续的 40 字节定长包**
 * （config 9，16kHz 宽带，一包 = 一个 20ms 帧）。直接当成 audio/ogg
 * 传给服务端，ffmpeg 会解出垃圾——而且**多半不报错**，
 * 就像 8-31 那次 m4a 拼接一样。
 *
 * 2026-09-01：我第一版 Ingest 就是直接把裸包写成 .opus 上传的，
 * 幸亏记忆里记着「设备吐裸 OPUS 40B 定长包非 Ogg」这一条才发现。
 *
 * ## 从 Mac 端逐位移植
 *
 * Ogg 的 CRC（多项式 0x04C11DB7，不反转）和 granule（走 **48kHz** 时钟，
 * 每包推进 960，即便音频是 16kHz）算错，产出的就是一个谁也解不开的文件。
 * 所以这里不重新推导，照搬已验证的实现。
 */
object OggWrap {
    const val PACKET_LEN = 40
    const val FRAME_MS = 20
    /** 20ms @ 48kHz。**不是 16kHz**——Ogg 的 granule 固定走 48k 时钟。 */
    const val GRANULE_PER_PACKET = 960L

    private val crcTable: IntArray = IntArray(256) { v ->
        var reg = v shl 24
        repeat(8) {
            // 看最高位。Kotlin 的 Int 是有符号的，所以用 and (1 shl 31) 判，
            // 不能写成 reg < 0 之外的花样——第一版我把这行写绕了，
            // 而 CRC 算错的后果是产出一个谁也解不开的文件，且不报错。
            reg = if (reg and (1 shl 31) != 0) (reg shl 1) xor 0x04C1_1DB7 else reg shl 1
        }
        reg
    }

    /** 只给测试用：验 CRC 表和算法本身，不然错了没人看得出来。 */
    fun crcOf(data: ByteArray): Int = oggCrc(data)
    fun tableAt(i: Int): Int = crcTable[i]

    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) and 0xFF) xor (b.toInt() and 0xFF)
            crc = (crc shl 8) xor crcTable[idx]
        }
        return crc
    }

    /**
     * 拼一页。header 27 字节 + 分段表 + 载荷，直接往 ByteArray 里写。
     * 012 P0-3：以前用 ArrayList<Byte> 逐字节 addAll，7 MB 的文件要装箱上亿次——内存飙五倍、低端机被杀。
     * 输出字节和旧实现完全一致（CRC 表与算法没动）。
     */
    private fun page(payloads: List<ByteArray>, granule: Long, serial: Int, seq: Int, flags: Int): ByteArray =
        pageOf(payloads.map { it.size }, granule, serial, seq, flags) { out, off ->
            var o = off
            payloads.forEach { it.copyInto(out, o); o += it.size }
        }

    /** 数据页：载荷是 raw 里的一段连续区间（count 个定长包），零拷贝切出来。 */
    private fun pageRaw(raw: ByteArray, from: Int, count: Int, granule: Long, serial: Int, seq: Int, flags: Int): ByteArray =
        pageOf(List(count) { PACKET_LEN }, granule, serial, seq, flags) { out, off ->
            raw.copyInto(out, off, from, from + count * PACKET_LEN)
        }

    private inline fun pageOf(sizes: List<Int>, granule: Long, serial: Int, seq: Int, flags: Int,
                              fill: (ByteArray, Int) -> Unit): ByteArray {
        val laces = java.io.ByteArrayOutputStream()
        for (n in sizes) { var rem = n; while (rem >= 255) { laces.write(255); rem -= 255 }; laces.write(rem) }
        val lace = laces.toByteArray()
        val payloadLen = sizes.sum()
        val out = ByteArray(27 + lace.size + payloadLen)
        out[0] = 'O'.code.toByte(); out[1] = 'g'.code.toByte(); out[2] = 'g'.code.toByte(); out[3] = 'S'.code.toByte()
        out[4] = 0; out[5] = flags.toByte()
        for (i in 0 until 8) out[6 + i] = ((granule ushr (i * 8)) and 0xFF).toByte()
        for (i in 0 until 4) out[14 + i] = ((serial ushr (i * 8)) and 0xFF).toByte()
        for (i in 0 until 4) out[18 + i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        // 22..25 是 CRC，先留 0
        out[26] = lace.size.toByte()
        lace.copyInto(out, 27)
        fill(out, 27 + lace.size)
        val crc = oggCrc(out)
        for (i in 0 until 4) out[22 + i] = ((crc ushr (i * 8)) and 0xFF).toByte()
        return out
    }
    class WrapError(msg: String) : Exception(msg)

    /** 裸包 → Ogg/Opus。长度不是 40 的整数倍时截掉尾部残包。 */
    /** 裸包 → Ogg/Opus。长度不是 40 的整数倍时截掉尾部残包。 */
    fun wrap(raw: ByteArray, sampleRate: Int = 16000, tag: String = "CB08"): ByteArray {
        val usable = raw.size - (raw.size % PACKET_LEN)
        if (usable < PACKET_LEN) throw WrapError("不足一个完整的 OPUS 包")
        val packets = usable / PACKET_LEN
        val serial = 0x5153_3638
        var seq = 0
        val head = java.io.ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray())
            write(1); write(1)
            write(312 and 0xFF); write(312 shr 8)                      // pre-skip, LE
            for (i in 0 until 4) write((sampleRate ushr (i * 8)) and 0xFF)
            write(0); write(0); write(0)                               // output gain(2) + mapping(1)
        }.toByteArray()
        val tagBytes = tag.toByteArray()
        val tags = java.io.ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            for (i in 0 until 4) write((tagBytes.size ushr (i * 8)) and 0xFF)
            write(tagBytes); write(0); write(0); write(0); write(0)
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream(usable + usable / 40 + 256)
        out.write(page(listOf(head), 0, serial, seq++, 0x02))
        out.write(page(listOf(tags), 0, serial, seq++, 0x00))
        var granule = 0L
        var start = 0
        while (start < packets) {
            val count = minOf(50, packets - start)
            granule += GRANULE_PER_PACKET * count
            val last = start + 50 >= packets
            out.write(pageRaw(raw, start * PACKET_LEN, count, granule, serial, seq++, if (last) 0x04 else 0x00))
            start += 50
        }
        return out.toByteArray()
    }
    fun durationMs(rawLength: Int): Long = (rawLength / PACKET_LEN).toLong() * FRAME_MS

    /** 是不是裸包（而不是已经封好的 Ogg）。 */
    fun looksRaw(data: ByteArray): Boolean =
        data.size >= PACKET_LEN && data.size % PACKET_LEN == 0 &&
            !(data.size >= 4 && data[0] == 'O'.code.toByte() && data[1] == 'g'.code.toByte() &&
              data[2] == 'g'.code.toByte() && data[3] == 'S'.code.toByte())
}
