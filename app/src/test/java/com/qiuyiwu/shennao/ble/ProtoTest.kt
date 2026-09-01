package com.qiuyiwu.shennao.ble

import org.junit.Assert.*
import org.junit.Test

/*
 * 协议是从 Mac 端已验证的实现移植的。**移植对不对，不看代码像不像，看字节一不一样**——
 * 所以这里保留了同一套向量，包括厂商文档 7.3 那条真机成功帧。
 */
class ProtoTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test fun `CRC-16 XMODEM 的标准检验向量`() {
        assertEquals(0x31C3, Proto.crc16("123456789".toByteArray()))
    }

    @Test fun `厂商文档 7点3 的真机成功帧要逐字节一致`() {
        val doc = byteArrayOf(
            0x5a, 0x03, 0x9e.toByte(), 0x20, 0x1e, 0x00, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00,
            0x6e, 0x6f, 0x74, 0x65, 0x32, 0x30, 0x32, 0x36, 0x30, 0x37, 0x31, 0x30,
            0x2d, 0x31, 0x36, 0x32, 0x39, 0x33, 0x38, 0x2e, 0x77, 0x61, 0x76, 0x00,
        )
        val built = Proto.buildImportRequest("note20260710-162938.wav", offset = 0, seq = 3)
        assertEquals(hex(doc), hex(built))
        assertEquals("下载请求必须整帧 36B 一次写完", 36, built.size)
    }

    @Test fun `帧头的 LEN 和 CRC 是小端`() {
        val f = Proto.buildImportRequest("note20260710-162938.wav", 0, 3)
        assertEquals(0x209E, (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8))
        assertEquals(30, (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8))
    }

    @Test fun `文件名超长要报错，不能悄悄截断`() {
        // 截断之后设备会去找一个不存在的文件，回一个「文件不存在」——
        // 而用户看到的是「这条录音导不出来」，完全对不上真正的原因。
        try {
            Proto.buildImportRequest("a".repeat(25))
            fail("超长文件名应该抛异常")
        } catch (e: Proto.ProtoError) {
            assertTrue(e.message!!.contains("超过"))
        }
    }

    @Test fun `删除用的是下载请求的格式，不是文档说的 28B 条目`() {
        // 真机实测：按文档写设备一律回 01 拒绝
        val d = Proto.buildDeleteOne("note20260710-162938.wav")
        assertEquals(36, d.size)
        assertEquals(Proto.FileCmd.DEL_ONE, d[7].toInt())
    }
}

class FileListTest {
    /** 造一条列表条目：time(4 BE) + size(4 BE) + name(20) */
    private fun entry(time: Long, size: Long, name: String): ByteArray {
        fun be32(v: Long) = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        val n = name.toByteArray()
        return be32(time) + be32(size) + n + ByteArray(20 - n.size)
    }
    private fun body(vararg e: ByteArray): ByteArray {
        val cnt = e.size
        return byteArrayOf(0, 0, 0, cnt.toByte()) + e.reduce { a, b -> a + b }
    }

    @Test fun `time 和 size 是大端——和帧头的小端不一样`() {
        val r = FileListDecoder.decode(body(entry(3600, 7_200_000, "note20260828.opus")))
        assertEquals(3600L, r[0].time)
        assertEquals(7_200_000L, r[0].size)
    }

    @Test fun `声明数比实际多时以实际为准——照着声明读会越界读出垃圾`() {
        val b = body(entry(10, 20, "a.opus"))
        b[3] = 5                                   // 谎称有 5 条
        assertEquals(1, FileListDecoder.decode(b).size)
    }

    @Test fun `被截断的名字要能重建出完整名，且 opus 优先`() {
        // 20B 字段会把 note20260828-205856.opus 截成 note20260828-205856.
        val e = FileEntry(0, 0, "note20260828-205856.")
        assertEquals("note20260828-205856.opus", e.candidates.first())
        // wav 是备选：设备转码的 wav 走 BLE 慢一个数量级（7.2MB vs 115MB）
        assertTrue(e.candidates.contains("note20260828-205856.wav"))
    }

    @Test fun `空 body 不崩`() {
        assertEquals(emptyList<FileEntry>(), FileListDecoder.decode(ByteArray(0)))
        assertEquals(emptyList<FileEntry>(), FileListDecoder.decode(byteArrayOf(0, 0)))
    }
}

class FrameParserTest {
    /*
     * BLE 通知按 MTU 切，一个帧可能横跨好几个通知。而音频数据里必然出现假的 0x5A。
     */
    @Test fun `在任意一个字节位置切开都要能重组`() {
        val frames = listOf(
            Proto.buildFrame(Proto.T.CTRL, 4, byteArrayOf(87), seq = 1),
            Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_DATA,
                             ByteArray(200) { it.toByte() }, seq = 2),
            Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_END, byteArrayOf(0), seq = 3),
        )
        val stream = frames.reduce { a, b -> a + b }
        for (cut in 1 until stream.size) {
            val p = FrameParser()
            val got = p.feed(stream.copyOfRange(0, cut)) + p.feed(stream.copyOfRange(cut, stream.size))
            assertEquals("在第 $cut 字节切开时重组失败", listOf(1, 2, 3), got.map { it.seq })
        }
    }

    @Test fun `数据里的假帧头不能卡死解析器`() {
        // 音频里 0x5A 就是个普通字节，一定会出现
        val noise = ByteArray(300) { Proto.MAGIC }
        val real = Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_END, byteArrayOf(0), seq = 9)
        val got = FrameParser().feed(noise + real)
        assertEquals(listOf(9), got.map { it.seq })
    }

    @Test fun `一直收不到合法帧时缓冲不能无限涨`() {
        // 不设上限的话，一个持续发音频的设备会把内存吃光
        val p = FrameParser()
        repeat(40) { p.feed(ByteArray(4096) { 0x11 }) }
        assertTrue("缓冲涨到了 ${p.buffered}", p.buffered <= 64 * 1024)
    }
}

class FrameParserResyncTest {
    /*
     * 这一条是移植时漏掉、被测试抓住的：没有 LEN 合理性上限时，
     * 假帧头 0x5A 0x5A 会被读成「长度 23130 的帧」，解析器永久等数据——
     * 而表现是「设备连上了但什么都不发」，最难查的那一类。
     */
    @Test fun `荒唐的长度必须当成假帧头，不能傻等`() {
        val p = FrameParser()
        // 0x5A 0x5A 0x.. 0x.. 0x5A 0x5A → LEN = 0x5A5A = 23130
        val noise = ByteArray(300) { Proto.MAGIC }
        val real = Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_END, byteArrayOf(0), seq = 9)
        assertEquals(listOf(9), p.feed(noise + real).map { it.seq })
        assertTrue("应该记下重新对齐的次数", p.resyncs > 0)
    }

    @Test fun `真帧紧跟在坏帧后面也要认出来`() {
        val bad = Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_DATA, ByteArray(10), seq = 1)
        bad[2] = (bad[2] + 1).toByte()      // 把 CRC 弄坏
        val good = Proto.buildFrame(Proto.T.FILE, Proto.FileCmd.IMPORT_END, byteArrayOf(0), seq = 2)
        val p = FrameParser()
        assertEquals(listOf(2), p.feed(bad + good).map { it.seq })
        assertTrue(p.crcErrors > 0)
    }
}

class OggWrapTest {
    /*
     * 设备吐的是**裸 OPUS 40 字节定长包**，不是 Ogg 文件。直接当 audio/ogg
     * 传上去，ffmpeg 会解出垃圾——而且多半不报错，就像 8-31 那次 m4a 拼接。
     *
     * Ogg 的 CRC（0x04C11DB7，不反转）和 granule（走 48kHz 时钟）算错，
     * 产出的就是一个谁也解不开的文件。所以用独立算出的参照值验。
     */

    @Test fun `CRC 表和算法用独立参照值验过`() {
        // 参照值由一份独立的 Python 实现算出，不是从这段代码自己产生的
        assertEquals(79764919, OggWrap.tableAt(1))
        assertEquals(2985771188L.toInt(), OggWrap.tableAt(255))
        assertEquals(2309065087L.toInt(), OggWrap.crcOf("123456789".toByteArray()))
        assertEquals(1605413199, OggWrap.crcOf("OggS".toByteArray()))
    }

    @Test fun `封出来的是合法 Ogg：以 OggS 开头，含 OpusHead 和 OpusTags`() {
        val raw = ByteArray(OggWrap.PACKET_LEN * 10) { (it % 251).toByte() }
        val ogg = OggWrap.wrap(raw)
        assertEquals("OggS", String(ogg.copyOfRange(0, 4)))
        val text = String(ogg, Charsets.ISO_8859_1)
        assertTrue("缺 OpusHead", text.contains("OpusHead"))
        assertTrue("缺 OpusTags", text.contains("OpusTags"))
    }

    @Test fun `granule 走 48kHz 时钟——即便音频是 16kHz`() {
        // 写成 16k 的话时长会算成三倍，深脑那边的时间轴整个错位
        assertEquals(960L, OggWrap.GRANULE_PER_PACKET)
        // 一包 20ms：50 包 = 1 秒
        assertEquals(1000L, OggWrap.durationMs(OggWrap.PACKET_LEN * 50))
    }

    @Test fun `尾部残包要截掉，不能当成完整包封进去`() {
        val raw = ByteArray(OggWrap.PACKET_LEN * 3 + 17)     // 多出 17 字节
        val ogg = OggWrap.wrap(raw)
        // 3 包 = 60ms
        assertEquals(60L, OggWrap.durationMs(raw.size))
        assertTrue(ogg.isNotEmpty())
    }

    @Test fun `一个完整包都没有时要报错，不能产出一个空 Ogg`() {
        // 空 Ogg 会被当成一段合法录音传上去，然后在服务端变成 0 秒
        try {
            OggWrap.wrap(ByteArray(20))
            fail("不足一包时应该抛异常")
        } catch (e: OggWrap.WrapError) { /* 预期 */ }
    }

    @Test fun `能认出已经是 Ogg 的数据，不重复封装`() {
        val raw = ByteArray(OggWrap.PACKET_LEN * 4)
        assertTrue(OggWrap.looksRaw(raw))
        assertFalse(OggWrap.looksRaw(OggWrap.wrap(raw)))
    }
}
