package com.qiuyiwu.shennao.ble

/**
 * 流式帧解析。
 *
 * BLE 的通知是按 MTU 切开的，一个协议帧可能横跨好几个通知，两个帧也可能挤在一个里。
 * 所以必须按字节流解析，不能假设「一个通知就是一个帧」。
 *
 * **最要紧的一条：音频数据里必然出现假的 0x5A**（它就是个普通字节）。
 * 解析器遇到对不上的帧头必须**只前进一个字节**继续找，不能丢弃整个缓冲、
 * 更不能停下来等——一停就再也不动了，而表现是「设备连上了但什么都不发」。
 */
class FrameParser(val name: String = "") {
    private var buf = ByteArray(0)
    /** 诊断用：CRC 对不上多少次、重新对齐多少次。设备行为可疑时这两个数直接说明问题。 */
    var crcErrors = 0; private set
    var resyncs = 0; private set

    /** 喂进新到的字节，取出这一轮能完整解析出来的帧。 */
    fun feed(chunk: ByteArray): List<Proto.Frame> {
        buf += chunk
        val out = mutableListOf<Proto.Frame>()
        var i = 0
        /*
         * 收不全的那个候选帧留在哪里。
         *
         * 关键：遇到「帧还没收全」**不能停下来等**。假帧头能拼出一个看起来合法的
         * 长度（实测 0x5A 0x5A… 会拼出 2394），于是解析器会等一批永远不会来的数据，
         * 而后面明明就跟着一个完整的真帧。表现是「设备连上了但什么都不发」。
         *
         * 所以：记下最早那个收不全的位置（future 数据可能补全它），
         * 但**继续往后扫**，先把能解出来的真帧交出去。
         */
        var pending = -1
        while (true) {
            while (i < buf.size && buf[i] != Proto.MAGIC) { i++; }
            if (buf.size - i < Proto.HEADER_LEN) {
                if (i < buf.size && pending < 0) pending = i
                break
            }

            val seq = buf[i + 1].toInt() and 0xFF
            val crc = (buf[i + 2].toInt() and 0xFF) or ((buf[i + 3].toInt() and 0xFF) shl 8)
            val len = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)

            // LEN 合理性上限。设备不会发比这更大的帧，超过就一定是假帧头。
            if (len > MAX_DATA_LEN) { resyncs++; i++; continue }

            val end = i + Proto.HEADER_LEN + len
            if (end > buf.size) {
                if (pending < 0) pending = i     // 留着等后续数据
                i++                              // 但继续往后找完整的真帧
                continue
            }

            if (Proto.crc16(buf, i + 4, end) == crc) {
                out += Proto.Frame(seq, buf.copyOfRange(i + Proto.HEADER_LEN, end))
                i = end
                pending = -1                     // 解出真帧了，之前的候选是噪音
            } else {
                // 对不上 = 这个 0x5A 是数据里的巧合。**只前进一个字节**：
                // 跳过整帧长度的话，真帧头就在里面时会被一起吞掉。
                crcErrors++; i++
            }
        }
        val keepFrom = if (pending >= 0) pending else i
        buf = if (keepFrom > 0) buf.copyOfRange(keepFrom, buf.size) else buf
        // 缓冲不能无限涨：一个持续发音频的设备会把内存吃光。
        if (buf.size > MAX_BUFFER) buf = buf.copyOfRange(buf.size - Proto.HEADER_LEN, buf.size)
        return out
    }

    fun reset() { buf = ByteArray(0); crcErrors = 0; resyncs = 0 }

    val buffered: Int get() = buf.size

    private companion object {
        const val MAX_BUFFER = 64 * 1024
        /** 设备不会发比这更大的帧。超过就一定是假帧头。 */
        const val MAX_DATA_LEN = 8192
    }
}
