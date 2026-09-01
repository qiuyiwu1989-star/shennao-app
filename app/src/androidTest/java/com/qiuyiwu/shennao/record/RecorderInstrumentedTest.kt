package com.qiuyiwu.shennao.record

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/*
 * 在真实 Android 运行时上跑录音。
 *
 * 这是唯一能真正碰到 AudioRecord 和 MediaCodec 的地方：
 * · JVM 单测碰不到它们
 * · Robolectric 也碰不到（它只模拟框架，不模拟音频硬件）
 * · 从 adb shell 启动前台服务被系统拒绝（服务 exported=false，那是对的）
 *
 * 2026-08-31 那个让整整一天录音都传不上去的 bug——分片之间差 160 毫秒——
 * 就是这一层该抓住而当时没有这一层。
 */
@RunWith(AndroidJUnit4::class)
class RecorderInstrumentedTest {

    /**
     * 测试自己拿麦克风权限。
     *
     * 原来靠跑之前手工 `adb grant`——而重装 APK 会把权限清掉，
     * 于是门禁在一次正常的重装之后就红了。**依赖外部手工步骤的测试，
     * 迟早会在没做那一步的环境里跑出错误结论。**
     */
    @get:Rule
    val permission: org.junit.rules.TestRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun 录两分钟_分段的时间轴必须严丝合缝() {
        val root = File(ctx.cacheDir, "rec-test-${System.currentTimeMillis()}")
        val vault = FileVault(root)
        val rec = Recorder(vault) {}

        val session = rec.start("门禁自测", System.currentTimeMillis())
        assertNotNull("麦克风打不开——检查测试运行器有没有 RECORD_AUDIO 权限", session)

        // 录到至少两段封好为止。段长 60 秒，所以要等两分多钟。
        val deadline = System.currentTimeMillis() + 200_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000)
            if (vault.segments(session!!).count { it.state == Segment.State.SEALED } >= 2) break
        }
        rec.stop()
        Thread.sleep(3_000)

        val segs = vault.segments(session!!).sortedBy { it.sequence }
        // 空的不是通过，是没测到
        assertTrue("只落了 ${segs.size} 段——录音没真正产出内容", segs.size >= 2)

        segs.forEachIndexed { i, s ->
            assertTrue("第 ${s.sequence} 段时长为负或零", s.endMs > s.startMs)
            if (i == 0) assertEquals("第一段必须从 0 开始", 0L, s.startMs)
            else assertEquals(
                "第 ${s.sequence} 段的起点必须等于上一段的终点——" +
                    "服务端冻结清单时要求逐片严丝合缝，差一毫秒整场都传不上去",
                segs[i - 1].endMs, s.startMs,
            )
        }

        // 封好的段必须真有内容。0 字节的 .aac 会被当成一段合法录音传上去。
        segs.filter { it.state == Segment.State.SEALED }.forEach {
            val f = vault.segmentFile(session, it)
            assertTrue("第 ${it.sequence} 段是空文件", f.isFile && f.length() > 1000)
        }
        root.deleteRecursively()
    }
}
