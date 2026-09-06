package com.qiuyiwu.shennao

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「刚录完这场，屏幕上要留下痕迹」——2026-09-02 用户实录暴露的问题：
 * 录完一场 8 分钟的会、全部段传完之后，录音页的文案变回了
 * 「点一下开始录这场会」，跟从没录过时一字不差。用户看到的字面意思
 * 就是「什么都没发生过」，而服务端那边转写分析全跑完了。
 *
 * `JustFinishedCard` 是从 RecordingService 的真实状态机里拆出来的
 * 纯展示组件——真实的「什么时候该显示它」判断耦合在 RecordingService
 * 的 companion object（故意 private set，界面只能照着念，不能自己维护
 * 一份状态），这里只测「显示的内容对不对」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordConfirmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `还在传的时候要说清楚还差几段，不能装作什么都没发生`() {
        compose.setContent {
            ShennaoTheme { JustFinishedCard(minutes = 8, pending = 3, onOpen = null) }
        }
        compose.onNodeWithText("刚录的这场（约 8 分钟）还有 3 段在传").assertExists()
    }

    @Test fun `全部送达之后要明确说「送到了」，不能回到待命文案`() {
        compose.setContent {
            ShennaoTheme { JustFinishedCard(minutes = 8, pending = 0, onOpen = {}) }
        }
        compose.onNodeWithText("刚录的这场（约 8 分钟）已经送到深脑了").assertExists()
        compose.onNodeWithText("转写和分析在那边跑，跑完会出现在「记录」里。").assertExists()
    }

    @Test fun `送达之后要给一条去看的路`() {
        var opened = false
        compose.setContent {
            ShennaoTheme { JustFinishedCard(minutes = 1, pending = 0, onOpen = { opened = true }) }
        }
        compose.onNodeWithText("去「记录」看").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test fun `没有跳转出口时也不能崩，只是没有按钮`() {
        compose.setContent {
            ShennaoTheme { JustFinishedCard(minutes = 1, pending = 0, onOpen = null) }
        }
        compose.onNodeWithText("去「会议」看").assertDoesNotExist()
    }

    // ---- 本地卡住的录音要能删掉（2026-09-02 用户实录：两条早年时间轴
    // 断裂的老 bug，服务端永远不会接受，之前没有任何办法清掉）----

    private fun localSession(dir: String = "note20260831-215551", recording: Int = 0) =
        LocalSession(
            dir = dir,
            meta = com.qiuyiwu.shennao.record.SessionMeta(
                clientRequestId = dir, title = dir, startedAtEpochMs = 0L, finished = true),
            total = 56, done = 2, recording = recording,
        )

    @Test fun `正常的本地会话给一条删除的路，要问一句才真的删`() {
        var deleted = false
        compose.setContent {
            ShennaoTheme { SessionRow(localSession()) { deleted = true } }
        }
        compose.onNodeWithText("删掉这条").assertIsDisplayed().performClick()
        assertFalse("点了按钮不该立刻删——这是不可逆动作", deleted)
        compose.onNodeWithText("删掉").assertIsDisplayed().performClick()
        assertTrue(deleted)
    }

    @Test fun `正在录的那条绝不能给删除入口——这是唯一一份还没落地的音频`() {
        compose.setContent {
            ShennaoTheme { SessionRow(localSession(recording = 1)) { fail("正在录的不该能删") } }
        }
        compose.onNodeWithText("删掉这条").assertDoesNotExist()
    }
}
