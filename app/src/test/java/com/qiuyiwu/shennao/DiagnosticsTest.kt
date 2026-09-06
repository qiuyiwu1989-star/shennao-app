package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/** 诊断包：只放能定位问题的，不放能定位人的。 */
class DiagnosticsTest {
    private fun snap(logcat: String = "") = Diagnostics.Snapshot(
        versionName = "3.6.0", versionCode = 41, device = "Xiaomi 14", android = "Android 14 (API 34)",
        emailMasked = Diagnostics.mask("qiuyiwu1989@gmail.com"), orgId = "org-1",
        keepAliveExempt = false, romHint = "小米：自启动", ble = "conn=IDLE", knobs = "fastInterval=false mtu=185 phy2m=false", pendingSegments = 3, recordingSegments = 0,
        sessions = 2, lastCrash = null, logcat = logcat, at = "2026-09-06 10:00:00",
    )

    @Test fun `邮箱打码：认得出是哪类账号，认不出是谁`() {
        assertEquals("q***@gmail.com", Diagnostics.mask("qiuyiwu1989@gmail.com"))
        assertEquals("***", Diagnostics.mask("not-an-email"))
    }

    @Test fun `日志里的凭证一律抹掉`() {
        val s = Diagnostics.scrub("Authorization: Bearer eyJhbGciOi.abc-def  refresh_token=rt_12345 password: hunter2")
        assertFalse(s.contains("eyJhbGciOi")); assertFalse(s.contains("rt_12345")); assertFalse(s.contains("hunter2"))
        assertTrue(s.contains("Bearer ***"))
    }

    @Test fun `渲染出来的东西能定位问题：版本、机型、豁免、待传段都在`() {
        val r = Diagnostics.render(snap("D/Ble: connected"))
        listOf("3.6.0 (41)", "Xiaomi 14", "豁免=否", "小米：自启动", "待传段 3", "q***@gmail.com", "D/Ble: connected",
               "mtu=185").forEach {
            assertTrue("缺 $it", r.contains(it))
        }
        assertFalse("完整邮箱不能出现", r.contains("qiuyiwu1989"))
    }
}
