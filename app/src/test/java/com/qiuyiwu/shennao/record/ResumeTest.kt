package com.qiuyiwu.shennao.record

import org.junit.Assert.*
import org.junit.Test

/** WorkManager 要不要再来一次的判据（2026-09-12 审计 A2）。 */
class ResumeTest {
    @Test fun `段全传完、收尾 503——段数是 0 也要再来`() {
        val r = mapOf("s" to DrainResult.Failed("收尾没成（503）", retryable = true))
        assertTrue(Resume.shouldRetry(r, pendingSegments = 0))
    }
    @Test fun `不可重试的失败不再来——那是要人看的`() {
        val r = mapOf("s" to DrainResult.Failed("深脑不收", retryable = false))
        assertFalse(Resume.shouldRetry(r, pendingSegments = 0))
    }
    @Test fun `还在推进也要再来`() {
        assertTrue(Resume.shouldRetry(mapOf("s" to DrainResult.Progress(1, 2)), 0))
    }
    @Test fun `全做完了就收工`() {
        assertFalse(Resume.shouldRetry(mapOf("s" to DrainResult.Done("x", 3)), 0))
        assertFalse(Resume.shouldRetry(emptyMap(), 0))
    }
    @Test fun `还有段没传，不管结果都再来`() {
        assertTrue(Resume.shouldRetry(emptyMap(), 2))
    }
}
