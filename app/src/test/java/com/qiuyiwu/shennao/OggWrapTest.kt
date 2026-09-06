package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.ble.OggWrap
import org.junit.Assert.*
import org.junit.Test

/** 012 P0-3：OggWrap 改成直写字节之后，结构和 CRC 必须和以前一模一样。 */
class OggWrapTest {
    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    /** 把整个输出按 Ogg 页切开：每页 27 字节头 + 分段表 + 载荷。 */
    private fun pages(ogg: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>(); var i = 0
        while (i < ogg.size) {
            assertEquals("OggS", String(ogg, i, 4, Charsets.US_ASCII))
            val nseg = ogg[i + 26].toInt() and 0xFF
            var payload = 0
            for (k in 0 until nseg) payload += ogg[i + 27 + k].toInt() and 0xFF
            val len = 27 + nseg + payload
            out += ogg.copyOfRange(i, i + len); i += len
        }
        return out
    }

    @Test fun `120 个包 → 头页 + 标签页 + 三个数据页，最后一页标 EOS，每页 CRC 自洽`() {
        val raw = ByteArray(120 * OggWrap.PACKET_LEN) { (it % 251).toByte() }
        val ogg = OggWrap.wrap(raw)
        val ps = pages(ogg)
        assertEquals(5, ps.size)
        assertEquals("头页标 BOS", 0x02, ps[0][5].toInt())
        assertEquals("最后一页标 EOS", 0x04, ps[4][5].toInt())
        assertEquals("OpusHead", String(ps[0], 27 + 1, 8, Charsets.US_ASCII))
        assertEquals("OpusTags", String(ps[1], 27 + 1, 8, Charsets.US_ASCII))
        // 每个数据页 50 个 40 字节的包，最后一页 20 个
        assertEquals(50, ps[2][26].toInt() and 0xFF); assertEquals(20, ps[4][26].toInt() and 0xFF)
        // granule 走 48k 时钟：第一页 50×960，最后一页 120×960
        assertEquals(50 * 960L, le32(ps[2], 6)); assertEquals(120 * 960L, le32(ps[4], 6))
        // CRC：把存的 4 字节清零后重算要等于存的
        ps.forEachIndexed { n, p ->
            val stored = le32(p, 22).toInt()
            val zeroed = p.copyOf(); for (k in 22..25) zeroed[k] = 0
            assertEquals("第 $n 页 CRC", stored, OggWrap.crcOf(zeroed))
        }
        // 载荷原样：第三页第一个包 = raw 的前 40 字节
        assertArrayEquals(raw.copyOfRange(0, 40), ps[2].copyOfRange(27 + 50, 27 + 50 + 40))
        assertEquals(120 * 20L, OggWrap.durationMs(raw.size))
    }

    @Test fun `不足一个包就拒绝；尾部残包截掉`() {
        assertThrows(OggWrap.WrapError::class.java) { OggWrap.wrap(ByteArray(39)) }
        val ogg = OggWrap.wrap(ByteArray(40 * 3 + 7))
        assertEquals(3, pages(ogg)[2][26].toInt() and 0xFF)
    }
}
