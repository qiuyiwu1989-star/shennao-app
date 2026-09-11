package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.record.PcmRing
import com.qiuyiwu.shennao.record.VoiceGate
import org.junit.Assert.*
import org.junit.Test

/**
 * 全时聆听里能在 JVM 上测的两块：环形缓冲、以及「什么时候该起录、什么时候该收尾」。
 *
 * 麦克风交接那一段测不了（要真设备），所以这里把它周围的判断全测到，
 * 让真机上只剩下「交接本身」一个变量。
 */
class AlwaysOnTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `环没满时按顺序全取回来`() {
        val r = PcmRing(10)
        r.push(bytes(1, 2, 3), 3)
        r.push(bytes(4, 5), 2)
        assertArrayEquals(bytes(1, 2, 3, 4, 5), r.drain())
        assertEquals(0, r.size)
    }

    @Test fun `环满了丢最老的，留下的是最近的那一截`() {
        val r = PcmRing(4)
        r.push(bytes(1, 2, 3), 3)
        r.push(bytes(4, 5, 6), 3)
        // 只留最近 4 个字节
        assertArrayEquals(bytes(3, 4, 5, 6), r.snapshot())
    }

    @Test fun `一次推进来的比整个环还大，只留最后那一段`() {
        // 这条防的是越界：不处理的话 arraycopy 会直接抛，而它跑在采集线程上
        val r = PcmRing(3)
        r.push(bytes(1, 2, 3, 4, 5), 5)
        assertArrayEquals(bytes(3, 4, 5), r.snapshot())
    }

    @Test fun `绕过一圈之后取出来的顺序仍然是对的`() {
        val r = PcmRing(4)
        r.push(bytes(1, 2, 3), 3)
        r.push(bytes(4, 5, 6), 3)   // head 绕回去了
        r.push(bytes(7), 1)
        assertArrayEquals(bytes(4, 5, 6, 7), r.snapshot())
    }

    // ── 起录与收尾的判据 ──────────────────────────────────────

    /** 全时聆听用的那套参数：100 毫秒一帧、静 40 秒算说完 */
    private fun gate() = VoiceGate(frameMs = 100, hangoverMs = 40_000, minSpeechMs = 600)

    private fun feed(g: VoiceGate, level: Float, ms: Int) {
        repeat(ms / 100) { g.feed(level) }
    }

    @Test fun `一声咳嗽不起录`() {
        val g = gate()
        feed(g, 0.02f, 2_000)        // 先让地板落在安静上
        feed(g, 0.6f, 300)           // 咳嗽
        // 起录的条件是「开口了并且说够了」。300 毫秒没说够
        assertTrue(g.voicedMsSoFar < 600)
    }

    @Test fun `说够六百毫秒就起录，不等它说完`() {
        val g = gate()
        feed(g, 0.02f, 2_000)
        feed(g, 0.6f, 800)
        assertEquals(VoiceGate.State.SPEAKING, g.state)
        assertTrue("说了 800 毫秒就该起录了", g.voicedMsSoFar >= 600)
    }

    @Test fun `说话中间停两秒不收尾`() {
        val g = gate()
        feed(g, 0.02f, 2_000)
        feed(g, 0.6f, 1_000)
        var ended = false
        repeat(20) { if (g.feed(0.02f) == VoiceGate.Event.CLOSE) ended = true }   // 静 2 秒
        assertFalse("两秒的停顿是句子内部的停顿，不是说完了", ended)
    }

    @Test fun `静够四十秒才收尾`() {
        val g = gate()
        feed(g, 0.02f, 2_000)
        feed(g, 0.6f, 1_000)
        var closedAt = -1
        for (i in 1..500) {
            if (g.feed(0.02f) == VoiceGate.Event.CLOSE) { closedAt = i * 100; break }
        }
        assertEquals("应该正好在静 40 秒时收尾", 40_000, closedAt)
    }

    @Test fun `收尾之后能再起一场，不是一次性的`() {
        val g = gate()
        feed(g, 0.02f, 2_000)
        feed(g, 0.6f, 1_000)
        repeat(400) { g.feed(0.02f) }            // 静 40 秒，收尾
        assertEquals(VoiceGate.State.SILENT, g.state)
        feed(g, 0.6f, 800)                        // 又有人说话
        assertEquals(VoiceGate.State.SPEAKING, g.state)
        assertTrue(g.voicedMsSoFar >= 600)
    }

    @Test fun `计的是真正出声的时间，不是聆听开着的时间`() {
        val g = gate()
        feed(g, 0.02f, 2_000)                     // 安静两秒：不计
        feed(g, 0.6f, 1_000)                      // 说一秒
        repeat(400) { g.feed(0.02f) }             // 静 40 秒收尾：静音不计
        // 计的是「出声 + 前置缓冲」，前置缓冲那段音频确实留下并转写了
        assertTrue("应该在 1 秒上下，不是 43 秒", g.speechMs in 1_000..1_600)
    }

    @Test fun `咳嗽不计费`() {
        val g = gate()
        feed(g, 0.02f, 2_000)
        feed(g, 0.6f, 300)
        repeat(400) { g.feed(0.02f) }             // 静够，这一段判为噪声
        assertEquals("一声咳嗽不该收钱", 0L, g.speechMs)
    }

    @Test fun `一直说不停，噪声地板不会把人声关在门外`() {
        // 地板只升不降的那一版，讲到后来门就永远关着了
        val g = gate()
        feed(g, 0.02f, 1_000)
        feed(g, 0.5f, 600_000)                    // 讲十分钟
        assertEquals(VoiceGate.State.SPEAKING, g.state)
        assertTrue("十分钟里应该一直算在说话", g.voicedMsSoFar > 590_000)
    }
}
