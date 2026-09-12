package com.qiuyiwu.shennao.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.qiuyiwu.shennao.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 边角形态的截图。顺的形态本来就不会出问题；出问题的是：
 * 空的、坏的、超长的、系统字号调大的、卡住的。每一种各画一张，看一眼就知道哪里塌了。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EdgeShotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                ShennaoTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/edge-$name.png")
    }

    private fun today(
        commitments: List<Commitment> = emptyList(), insights: List<Insight> = emptyList(),
        predictions: List<Prediction> = emptyList(), notReady: Boolean = false, failed: Boolean = false, awaiting: Int = 0,
    ) = Today(TodayCounts(commitments.count { (it.overdueDays ?: 0) > 0 }, commitments.size, awaiting), commitments, insights, predictions, notReady, failed)

    private fun longCommitment() = Commitment(
        id = "c9", speakerName = "欧阳-市场部-华东区负责人-张三丰", statement = "x",
        quote = "这个项目的预算我回去之后会和财务、法务、还有集团那边的三个部门分别再确认一遍，确认完之后大概率下下周之前能给你们一个明确的答复，如果中间有变化我会第一时间在群里说。",
        saidDate = "8月28日", context = "一个标题特别特别长的会议名称用来测试换行是否正常显示", dueDate = "9月30日",
        overdueDays = 128, status = "open", transcriptId = "t1", personId = "p1",
    )

    @Test fun `今天 全空`() = shoot("today-empty") { TodayScreen(today(), {}, {}, {}) }
    @Test fun `今天 取不到`() = shoot("today-failed") { TodayScreen(today(failed = true), {}, {}, {}) }
    @Test fun `今天 还在升级`() = shoot("today-notready") { TodayScreen(today(notReady = true), {}, {}, {}) }
    @Test fun `今天 超长`() = shoot("today-long") {
        TodayScreen(today(commitments = listOf(longCommitment()), awaiting = 12), {}, {}, {})
    }
    @Test fun `今天 大字号`() = shoot("today-bigfont", fontScale = 1.3f) {
        Column { LiveBar {}; TodayScreen(today(commitments = listOf(longCommitment())), {}, {}, {}) }
    }
    @Test fun `今天 大字号 暗色`() = shoot("today-bigfont-dark", dark = true, fontScale = 1.3f) {
        Column { LiveBar {}; TodayScreen(today(commitments = listOf(longCommitment())), {}, {}, {}) }
    }

    private fun demoClient(): DeepBrainClient { Demo.install(); return Session.client(ApplicationProvider.getApplicationContext()) }
    private object NoNet : Http {
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?) = HttpResponse(0, "网络不通")
        override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) = HttpResponse(0, "")
    }
    private fun offlineClient(): DeepBrainClient {
        val store = object : CredentialStore {
            var c: Credentials? = Credentials("r", "o", "a-very-long-email-address-for-layout@example-company.com")
            override fun load() = c; override fun save(c: Credentials) { this.c = c }; override fun clear() { c = null }
        }
        return DeepBrainClient(NoNet, store, "https://api.test", "https://sb.test", "k")
    }

    // 记录页 / 我的 / 会议 / 人物：不组合会自己取数的屏，直接喂「已加载态」（为什么见 ScreenshotTest）
    private val fixedNow = 1757073600000L   // 2026-09-05T12:00:00Z，周带钉在这一周
    @Composable private fun Records(client: DeepBrainClient, rows: List<LocalSession>) = HistoryLoaded(
        client, rows = rows, served = emptyList(), loaded = true, stale = null,
        card = CardStatus.read(), onRecord = {}, onOpen = {}, nowMs = { fixedNow },
    )
    @Test fun `记录 空`() = shoot("records-empty") { Records(offlineClient(), emptyList()) }
    @Test fun `记录 卡住一条`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = com.qiuyiwu.shennao.record.FileVault(File(ctx.filesDir, "recordings"))
        val s = vault.newSession(com.qiuyiwu.shennao.record.SessionMeta("ble-note20260829-140354", "note20260829-140354", 1756447434000, finished = true,
            lastError = "第 0 段传不上去（分片大小超出范围）"))
        vault.writeMeta(s, vault.readMeta(s)!!)
        File(vault.segmentFile(s, com.qiuyiwu.shennao.record.Segment(0, 0, 796560, com.qiuyiwu.shennao.record.Segment.State.SEALED, ext = "opus")).path).writeBytes(ByteArray(10))
        val rows = scanLocal(File(ctx.filesDir, "recordings"))
        shoot("records-stuck") { Records(offlineClient(), rows) }
        vault.deleteSession(s)
    }
    @Test fun `我的 没网 长邮箱 大字号`() {
        val update = Update.check(NoNet, BuildConfig.VERSION_CODE)
        shoot("me-offline-bigfont", fontScale = 1.3f) {
            MeContent(offlineClient(), state = update, checking = false, onCheckUpdate = {}, credits = null, orgs = emptyList(),
                      onOpenWeb = { _, _ -> }, onSignOut = {}, versionName = "x.y.z")
        }
    }
    @Test fun `问 大字号 暗色`() = shoot("ask-bigfont-dark", dark = true, fontScale = 1.3f) { AskScreen(demoClient()) {} }
    @Test fun `搜索 空`() = shoot("search-empty") { SearchScreen(offlineClient()) {} }
    @Test fun `坏了和空`() = shoot("broken-empty") {
        Column { Broken("网络不通") {}; Empty("还没有录过", "录一场会，它会自己走完转写和分析。", "录一场") {} ; SkeletonList(2) }
    }
    @Test fun `会议 大字号`() {
        val client = demoClient()
        val m = (client.meeting("t1") as ApiResult.Ok).value
        shoot("meeting-bigfont", fontScale = 1.3f) { MeetingLoaded(client, m, onBack = {}) }
    }
    @Test fun `录音台 大字号 暗色`() = shoot("record-bigfont-dark", dark = true, fontScale = 1.3f) { RecordScreen(onBack = {}) }
    @Test fun `灵魂卡页`() = shoot("ble") { BleScreen(onDone = {}) }
    // 以前组合 PersonScreen 配没网的客户端，拍到的其实是「没取到」而不是骨架——取数立刻失败，画面看运气
    @Test fun `人物页 骨架`() = shoot("person-skeleton") { PersonPage(null, null, {}, {}, {}, {}) }
}
