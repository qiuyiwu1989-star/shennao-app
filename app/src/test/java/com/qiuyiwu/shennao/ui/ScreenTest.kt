package com.qiuyiwu.shennao.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qiuyiwu.shennao.*
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
@Config(sdk = [34])
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
        compose.onNodeWithText("兑现了").performClick()
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
}
