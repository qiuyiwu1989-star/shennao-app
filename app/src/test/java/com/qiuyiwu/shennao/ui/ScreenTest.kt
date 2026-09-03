package com.qiuyiwu.shennao.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qiuyiwu.shennao.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * 界面测试。跑在 JVM 上（Robolectric），不需要模拟器。
 *
 * 立这一层是因为一个难看的比例：99 个单测**没有一个碰界面**，
 * 而 2026-08-31 用户报的四个缺陷全是界面问题，全由他发现。
 * 那不该是用户的活。
 *
 * 下面每一条都对着一个真实发生过的缺陷写，不是为了覆盖率。
 */
@RunWith(RobolectricTestRunner::class)
// **屏幕尺寸要是一台真手机。** Robolectric 默认给的是 320×470dp——
// 2026 年没有这个尺寸的机器。在它上面，正常的卡片会被挤出屏幕，
// 而 performClick 点在可视区外时**既不报错也不生效**：
// 测试会红在一句「回调没被调到」上，让人以为业务逻辑坏了，
// 实际坏的是测试自己的取景框。Pixel 尺寸（411×891dp）。
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun today(
        commitments: List<Commitment> = emptyList(),
        insights: List<Insight> = emptyList(),
        predictions: List<Prediction> = emptyList(),
        notReady: Boolean = false,
        failed: Boolean = false,
    ) = Today(
        counts = TodayCounts(overdue = commitments.size, total = commitments.size, awaitingSpeaker = 0),
        commitments = commitments, insights = insights, predictions = predictions,
        notReady = notReady, failed = failed,
    )

    private fun commitment(id: String = "c1") = Commitment(
        id = id, speakerName = "陈总", statement = "下周给方案", quote = "下周给方案",
        saidDate = "8月30日", context = "三方洽谈", dueDate = "2026-09-07",
        overdueDays = 2, status = "open", transcriptId = "t1",
    )

    // ---- 空态必须给出口（设计系统铁律，网页那边 32 处没给） ----

    @Test fun `每个频道空了都要给下一步动作，不能只说一句「没有」`() {
        compose.setContent {
            ShennaoTheme { TodayScreen(today(), {}, {}, {}) }
        }
        // 「下文」空态
        compose.onNodeWithText("没有要问的").assertExists()
        // 空态的说明里必须交代「什么时候会出现东西」，否则用户不知道该等还是该做什么
        compose.onNodeWithText("别人在会上答应过的事，到期了会出现在这里。").assertExists()
    }

    // ---- 三态不能长得一样（Broken 绝不能画成 Empty） ----

    @Test fun `「取不到」和「没有」必须是两种画面`() {
        compose.setContent { ShennaoTheme { Broken("网络不通") {} } }
        compose.onNodeWithText("没取到").assertExists()
        compose.onNodeWithText("再试一次").assertExists()   // 出事了要能重试
    }

    @Test fun `空态给了动作就要能点`() {
        var clicked = false
        compose.setContent {
            ShennaoTheme { Empty("还没有录过", "录一场会。", "录一场") { clicked = true } }
        }
        compose.onNodeWithText("录一场").performClick()
        assert(clicked) { "空态的出口点了没反应，等于没有出口" }
    }

    // ---- 承诺卡：原话是主角，且落账两个动作都在 ----

    @Test fun `承诺卡显示原话与逾期天数`() {
        compose.setContent {
            ShennaoTheme { TodayScreen(today(commitments = listOf(commitment())), {}, {}, {}) }
        }
        compose.onNodeWithText("「下周给方案」").assertExists()
        // 逾期用文字说清楚，不只靠颜色——阳光下和色觉障碍面前颜色都不可靠
        compose.onNodeWithText("过期 2 天").assertExists()
    }

    @Test fun `落账点一下要真的报上去，并且不会重复报`() {
        val calls = mutableListOf<Pair<String, String>>()
        compose.setContent {
            ShennaoTheme {
                TodayScreen(today(commitments = listOf(commitment())), {}, {}, {},
                    onSettle = { id, action -> calls += id to action })
            }
        }
        // 先断言它真的在屏幕上——点一个看不见的节点是静默失败
        compose.onNodeWithText("兑现了").assertIsDisplayed().performClick()
        assert(calls == listOf("c1" to "kept")) { "落账没报上去：$calls" }
        // 点完之后按钮要消失，换成「已记」——否则用户会再点一次，账上多一笔
        compose.onNodeWithText("已记：兑现了").assertExists()
        compose.onNodeWithText("兑现了").assertDoesNotExist()
    }

    // ---- Markdown 必须渲染（用户报过：屏幕上一堆 ## 和 **） ----

    @Test fun `Markdown 的标记不能原样显示给用户`() {
        compose.setContent {
            ShennaoTheme { MarkdownText("## 这场会\n聊了**合作框架**。") }
        }
        compose.onNodeWithText("这场会").assertExists()
        compose.onNodeWithText("## 这场会").assertDoesNotExist()
    }

    // ---- 离线横幅必须标出「这是什么时候的」 ----

    @Test fun `离线时要说清楚数据是什么时候的`() {
        compose.setContent {
            ShennaoTheme { TodayScreen(today(), {}, {}, {}, staleLabel = "离线 · 5 分钟前的") }
        }
        // 不标时间的缓存比没有缓存更糟——用户会拿三天前的当今天的
        compose.onNodeWithText("离线 · 5 分钟前的").assertExists()
    }

    // ---- 系统自己有毛病时别去烦用户 ----

    @Test fun `迁移没跑完要直说，不能显示成「今天没事」`() {
        compose.setContent { ShennaoTheme { TodayScreen(today(notReady = true), {}, {}, {}) } }
        compose.onNodeWithText("下文还没准备好——服务端的迁移还没跑。").assertExists()
    }

    @Test fun `取数失败要直说，不能显示成「今天没事」`() {
        compose.setContent { ShennaoTheme { TodayScreen(today(failed = true), {}, {}, {}) } }
        compose.onNodeWithText("取数失败了，不是「没有内容」。").assertExists()
    }

    // ---- UI 二次成型（2026-09-01）----

    @Test fun `骨架屏要画出真实卡片的形状，不是一个转圈`() {
        compose.setContent { ShennaoTheme { SkeletonList(3) } }
        // 骨架是靠形状说话的，没有文字可断言——所以断言它确实渲染出了节点。
        // 一条「什么都没画也能过」的测试比没有测试更糟。
        compose.onRoot().assertExists()
        val dump = compose.onRoot().printToString()
        assertTrue("骨架屏什么都没画出来", dump.length > 200)
    }

    @Test fun `删除录音必须先问一次——音频删了找不回来`() {
        var confirmed = false
        compose.setContent {
            ShennaoTheme {
                ConfirmDialog(
                    title = "删掉这条录音？",
                    detail = "音频会从深脑删除，找不回来。",
                    confirmLabel = "删掉", destructive = true,
                    onConfirm = { confirmed = true }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("取消").assertExists()
        assertFalse("还没点确认就执行了", confirmed)
        compose.onNodeWithText("删掉").performClick()
        assertTrue(confirmed)
    }

    @Test fun `确认框要说清做完会怎样，不能只是重复标题`() {
        compose.setContent {
            ShennaoTheme {
                ConfirmDialog(
                    title = "退出登录？",
                    detail = "还没传完的录音会留在手机上，重新登录后接着传。",
                    confirmLabel = "退出", onConfirm = {}, onDismiss = {},
                )
            }
        }
        // 「确定要退出吗」是句废话；用户真正担心的是没传完的录音会不会没
        compose.onNodeWithText("还没传完的录音会留在手机上，重新登录后接着传。").assertExists()
    }

    @Test fun `轻提示只说屏幕上看不见的事——落账成功不弹，失败才弹`() {
        // 这条测的是判据本身，不是某个像素：
        // 「已记：兑现了」已经写在卡片上了，再弹一句提示是噪音；
        // 噪音弹多了，真正要紧的那句会被当成背景划走。
        val posted = mutableListOf<String>()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNotice provides { posted += it }) {
                ShennaoTheme {
                    TodayScreen(today(commitments = listOf(commitment())), {}, {}, {}, onSettle = { _, _ -> })
                }
            }
        }
        compose.onNodeWithText("兑现了").assertIsDisplayed().performClick()
        compose.onNodeWithText("已记：兑现了").assertExists()
        assertEquals("落账成功不该再弹提示，卡片自己说了", emptyList<String>(), posted)
    }

    // ---- 我的：隐私/条款出口、更新检查（2026-09-02，v3 收口）----

    private class MemStore(var c: Credentials? = null) : CredentialStore {
        override fun load() = c
        override fun save(x: Credentials) { c = x }
        override fun clear() { c = null }
    }

    private class NoNetHttp : Http {
        // 测试里绝不该真的发请求——发了就说明某处忘了注入假实现。
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?) =
            HttpResponse(0, "测试环境不该发出真实请求")
        override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) =
            error("不该走到这里")
    }

    @Test fun `隐私政策和服务条款要有出口，且走 App 内网页`() {
        val client = DeepBrainClient(NoNetHttp(), MemStore(Credentials("r", "o", "e@x.com")), "https://api.test", "https://sb.test", "k")
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            ShennaoTheme { MeScreen(client, onOpenWeb = { p, t -> opened += p to t }, onSignOut = {}, http = NoNetHttp()) }
        }
        compose.onNodeWithText("隐私政策").assertIsDisplayed().performClick()
        compose.onNodeWithText("服务条款").assertIsDisplayed().performClick()
        assertEquals(listOf("/zh/privacy" to "隐私政策", "/zh/terms" to "服务条款"), opened)
    }

    @Test fun `查不到新版和已是最新要分开说，不能用同一句话`() {
        val client = DeepBrainClient(NoNetHttp(), MemStore(Credentials("r", "o", "e@x.com")), "https://api.test", "https://sb.test", "k")
        val http = object : Http {
            // 真实网络不通时 UrlHttp 是抛异常，不是回一个 status=0 的应答——
            // 假实现要还原这一点，否则测的是「JSON 解析失败」而不是「网络不通」。
            override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
                throw java.io.IOException("连不上")
            override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) = error("不该走到这里")
        }
        compose.setContent {
            ShennaoTheme { MeScreen(client, onOpenWeb = { _, _ -> }, onSignOut = {}, http = http) }
        }
        // 网络不通不等于「已是最新」——用户会因为这句话误以为不用管了
        compose.onNodeWithText("查不到有没有新版（网络不通）").assertExists()
    }

    // ---- 会议卡片：参考"智能纪要"改版（2026-09-03）----

    private fun sessionCard(
        stage: Stage = Stage.ANALYZED,
        title: String = "产品方向讨论会",
        startedAt: String? = "2026-08-28T11:16:00",
        durationMs: Long? = 1_800_000L,
    ) = SessionCard(
        sessionId = "s1", title = title, startedAt = startedAt, durationMs = durationMs,
        stage = stage, problem = null, transcriptId = "t1",
    )

    @Test fun `标题在前，日期和时长收成一行紧凑的元信息`() {
        compose.setContent {
            ShennaoTheme { ServedRow(sessionCard(), onOpen = {}) }
        }
        compose.onNodeWithText("产品方向讨论会").assertExists()
        // 日期和时长现在是同一行，用 · 连着——不是分开两处
        // （不测具体几点：day() 按系统时区转换，测试环境时区不保证跟设备一致）
        compose.onNodeWithText("30 分钟", substring = true).assertExists()
        compose.onNodeWithText("月", substring = true).assertExists()
    }

    @Test fun `分析完的条目不显示四步进度——对一场办完的事，四个绿勾只是噪音`() {
        compose.setContent {
            ShennaoTheme { ServedRow(sessionCard(stage = Stage.ANALYZED), onOpen = {}) }
        }
        compose.onNodeWithText("分析完").assertDoesNotExist()
    }

    @Test fun `还没分析完的条目要留着进度——这正是这一页存在的理由`() {
        compose.setContent {
            ShennaoTheme { ServedRow(sessionCard(stage = Stage.TRANSCRIBED), onOpen = {}) }
        }
        compose.onNodeWithText("分析完").assertExists()
    }
}
