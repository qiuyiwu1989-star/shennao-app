package com.qiuyiwu.shennao.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VAD 的测试全部围绕「哪一种误判会让用户吃亏」写：
 * 吃掉开头、把一句话切碎、为咳嗽付钱、在嘈杂环境里全程当成在说话。
 */
class VoiceGateTest {
    private fun feedAll(g: VoiceGate, level: Float, ms: Int, frameMs: Int = 20): List<VoiceGate.Event> {
        val out = ArrayList<VoiceGate.Event>()
        repeat(ms / frameMs) { out.add(g.feed(level)) }
        return out
    }

    @Test fun `安静时不开门`() {
        val g = VoiceGate()
        val events = feedAll(g, 0.02f, 5_000)
        assertTrue(events.all { it == VoiceGate.Event.IDLE })
        assertEquals(0L, g.speechMs)
    }

    @Test fun `说一句话开一段门并计时`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000)            // 先给它认识一下安静环境
        val speaking = feedAll(g, 0.30f, 3_000)
        assertEquals(VoiceGate.Event.OPEN, speaking.first { it != VoiceGate.Event.IDLE })
        val tail = feedAll(g, 0.02f, 2_000) // 静音超过 hangover → 封段
        assertTrue(tail.contains(VoiceGate.Event.CLOSE))
        // 3 秒说话 + 400 毫秒前置缓冲，允许状态机判定延迟带来的少量出入
        assertTrue("实际计时 ${g.speechMs}", g.speechMs in 3_000..3_700)
    }

    @Test fun `一场半数时间没人说话的会议只计说话的那一半`() {
        // 全时聆听的段落尺度：静默 45 秒才算这段对话结束
        val g = VoiceGate(hangoverMs = 45_000)
        feedAll(g, 0.02f, 1_000)
        repeat(10) { feedAll(g, 0.30f, 6_000); feedAll(g, 0.02f, 6_000) }   // 说 6 秒停 6 秒，共 2 分钟
        g.flush()
        // 真正出声 60 秒；按段落跨度算会是 120 秒
        assertTrue("实际计时 ${g.speechMs}", g.speechMs in 58_000..64_000)
    }

    @Test fun `句子中间的停顿不切段`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000)
        feedAll(g, 0.30f, 1_000)
        val pause = feedAll(g, 0.02f, 500)   // 500 毫秒的换气，短于 hangover
        assertTrue("换气不该封段", !pause.contains(VoiceGate.Event.CLOSE))
        feedAll(g, 0.30f, 1_000)
        val end = feedAll(g, 0.02f, 2_000)
        assertEquals("整句只该封一次", 1, end.count { it == VoiceGate.Event.CLOSE })
        // 两段各 1 秒发声 + 一次前置缓冲；中间那 500 毫秒换气不进账
        assertTrue("换气不该计费（实际 ${g.speechMs}）", g.speechMs in 2_000..2_599)
    }

    @Test fun `一声咳嗽不计费也不上传`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000)
        feedAll(g, 0.60f, 200)              // 短促巨响
        val after = feedAll(g, 0.02f, 2_000)
        assertTrue("太短的段应当丢弃", after.contains(VoiceGate.Event.DISCARD))
        assertTrue("不该出现上传", !after.contains(VoiceGate.Event.CLOSE))
        assertEquals("不该计费", 0L, g.speechMs)
    }

    @Test fun `嘈杂环境里的背景噪声不算说话`() {
        val g = VoiceGate()
        // 地铁：持续 0.18 的背景。地板会跟上去，门应当保持关闭
        val noise = feedAll(g, 0.18f, 20_000)
        val opened = noise.count { it == VoiceGate.Event.OPEN }
        assertTrue("背景噪声不该反复开门（实际 $opened 次）", opened <= 1)
        assertTrue("噪声不该被大量计费（实际 ${g.speechMs}）", g.speechMs < 3_000)
    }

    @Test fun `长时间连续说话不会因为地板上抬而中断`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000)
        val long = feedAll(g, 0.32f, 120_000)   // 两分钟不停
        assertEquals("两分钟连续说话不该封段", 0, long.count { it == VoiceGate.Event.CLOSE })
        val end = feedAll(g, 0.02f, 2_000)
        assertTrue(end.contains(VoiceGate.Event.CLOSE))
        assertTrue("两分钟应当足额计时（实际 ${g.speechMs}）", g.speechMs in 119_000..121_000)
    }

    @Test fun `停止聆听时收掉没封的那一段`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000)
        feedAll(g, 0.30f, 2_000)
        assertEquals(VoiceGate.Event.CLOSE, g.flush())
        assertTrue(g.speechMs >= 2_000)
        assertEquals("已经收过就不该再收", VoiceGate.Event.IDLE, g.flush())
    }

    @Test fun `计量归零不影响对环境的认识`() {
        val g = VoiceGate()
        feedAll(g, 0.02f, 1_000); feedAll(g, 0.30f, 2_000); g.flush()
        assertTrue(g.speechMs > 0)
        g.resetMeter()
        assertEquals(0L, g.speechMs)
        // 归零后仍然认得出说话，不需要重新学习环境
        feedAll(g, 0.30f, 1_000)
        assertEquals(VoiceGate.State.SPEAKING, g.state)
    }
}
