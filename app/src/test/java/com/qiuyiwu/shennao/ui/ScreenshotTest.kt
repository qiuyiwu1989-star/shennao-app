package com.qiuyiwu.shennao.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.qiuyiwu.shennao.ShennaoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 界面截图测试：在 JVM 上把每块界面画成 PNG，**不需要真机也不需要模拟器**。
 *
 * 它解决的是安卓开发里最要命的一件事：**改了界面看不见**。
 * 在这之前，想看一眼手机上长什么样，要么装 Android Studio 开预览，
 * 要么连设备装包——而 UI 的问题（对比度不够、节奏乱、暗色下字看不清）
 * 恰恰是「不看见就发现不了」的那一类。
 *
 * 跑一次：
 *     ./gradlew :app:recordRoborazziDebug      # 生成/更新基准图
 *     ./gradlew :app:verifyRoborazziDebug      # 和基准比，变了就红并输出差异图
 *
 * 图落在 `app/build/outputs/roborazzi/`，直接点开看。
 * 基准图提交进仓库之后，任何人改样式都会在这里看到自己改了哪一块。
 *
 * **每块界面至少两张：亮色与暗色。** 暗色是这个 App 最容易出问题的地方——
 * 网页那边根本没有暗色（设计系统 v1 明确不做），手机却必须跟随系统。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")   // Pixel 尺寸；换机型改这一行
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            ShennaoTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name-${if (dark) "dark" else "light"}.png")
    }

    /**
     * 色板一张。**这张最该先看**：它把所有 token 摆在一起，
     * 一眼能看出「卡片和页面底分不分得开」「次要文字在暗色下还看不看得见」。
     * 界面的精致与否，八成在这张图上就定了。
     */
    @Test fun `色板 亮色`() = shoot("palette", dark = false) { TokenBoard() }
    @Test fun `色板 暗色`() = shoot("palette", dark = true) { TokenBoard() }

    /** 记录页的双栏卡片。放的是边角形态，见 SampleCards。 */
    @Test fun `素材卡片 亮色`() = shoot("cards", dark = false) { SampleCards() }
    @Test fun `素材卡片 暗色`() = shoot("cards", dark = true) { SampleCards() }

    // ---- 真实的屏，用夹具数据（Demo.kt 那套，和 adb --ez demo true 看到的一样）----
    //
    // 取数一律在测试线程里同步做完（DemoHttp 是同步的），再把解析好的数据喂给各屏的「已加载态」
    // composable（MeetingLoaded / HistoryLoaded / MeContent…）。以前直接组合 MeetingScreen 这类会自己
    // 取数的屏，取数走 LaunchedEffect + Dispatchers.IO，Robolectric 下回主线程的时机不定，
    // 同一份代码偶发拍到半截页——门禁红得毫无信息量。

    private fun demoClient(): com.qiuyiwu.shennao.DeepBrainClient {
        com.qiuyiwu.shennao.Demo.install()
        return com.qiuyiwu.shennao.Session.client(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }
    private fun <T> ok(r: com.qiuyiwu.shennao.ApiResult<T>): T = (r as com.qiuyiwu.shennao.ApiResult.Ok).value
    private fun demoToday(): com.qiuyiwu.shennao.Today = ok(demoClient().today())

    private fun screens(dark: Boolean) {
        val today = demoToday()
        shoot("today", dark) {
            androidx.compose.foundation.layout.Column {
                com.qiuyiwu.shennao.LiveBar(onClick = {})
                com.qiuyiwu.shennao.TodayScreen(today, {}, {}, {})
            }
        }
    }
    @Test fun `今天 亮色`() = screens(false)
    @Test fun `今天 暗色`() = screens(true)

    /** 周带画的是「现在」所在的那一周；钉死在夹具数据的那一周，不然基准图每周都变 */
    private val fixedNow = 1757073600000L   // 2026-09-05T12:00:00Z

    private fun records(dark: Boolean) {
        val client = demoClient()
        val served = ok(client.sessions())
        shoot("records", dark) {
            com.qiuyiwu.shennao.HistoryLoaded(
                client, rows = emptyList(), served = served, loaded = true, stale = null,
                card = com.qiuyiwu.shennao.CardStatus.read(), onRecord = {}, onOpen = {}, nowMs = { fixedNow },
            )
        }
    }
    @Test fun `记录 亮色`() = records(false)
    @Test fun `记录 暗色`() = records(true)

    private fun me(dark: Boolean) {
        val client = demoClient()
        val update = com.qiuyiwu.shennao.Update.check(com.qiuyiwu.shennao.Demo.http!!, com.qiuyiwu.shennao.BuildConfig.VERSION_CODE)
        val credits = ok(client.credits())
        val orgs = ok(client.orgs())
        shoot("me", dark) {
            com.qiuyiwu.shennao.MeContent(
                client, state = update, checking = false, onCheckUpdate = {}, credits = credits, orgs = orgs,
                onOpenWeb = { _, _ -> }, onSignOut = {}, versionName = "x.y.z",
            )
        }
    }
    @Test fun `我的 亮色`() = me(false)
    @Test fun `我的 暗色`() = me(true)

    private fun meeting(dark: Boolean) {
        val client = demoClient()
        val m = ok(client.meeting("t1"))
        shoot("meeting", dark) { com.qiuyiwu.shennao.MeetingLoaded(client, m, onBack = {}) }
    }
    @Test fun `会议 亮色`() = meeting(false)
    @Test fun `会议 暗色`() = meeting(true)

    // 下面这些屏进来不取数（问答等你输入，录音台/定时/接入 AI 只读本机），直接组合就是确定的
    @Test fun `问 亮色`() = shoot("ask", false) { com.qiuyiwu.shennao.AskScreen(demoClient()) {} }
    @Test fun `问 暗色`() = shoot("ask", true) { com.qiuyiwu.shennao.AskScreen(demoClient()) {} }
    @Test fun `录音台 亮色`() = shoot("record", false) { com.qiuyiwu.shennao.RecordScreen(onBack = {}) }
    @Test fun `录音台 暗色`() = shoot("record", true) { com.qiuyiwu.shennao.RecordScreen(onBack = {}) }
    @Test fun `定时 亮色`() = shoot("schedule", false) { com.qiuyiwu.shennao.ScheduleScreen(onBack = {}) }
    @Test fun `接入AI 暗色`() = shoot("agents", true) { com.qiuyiwu.shennao.AgentsScreen(onBack = {}) { _, _ -> } }

}
