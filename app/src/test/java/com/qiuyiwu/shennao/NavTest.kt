package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/*
 * 导航是纯逻辑，所以它在 JVM 上跑得完。
 *
 * 这份测试要挡住的是老实现真出过的那几个错，不是「覆盖率」：
 *   · 同一个屏幕渲染在两个分支里，参数还不一样
 *   · 返回键在详情页直接退出 App
 *   · 跨栏跳转忘了同时改另一个变量
 */
class NavTest {

    @Test fun `开机在今天，五个栈各自见底`() {
        val n = NavState.initial()
        assertEquals(Tab.TODAY, n.tab)
        assertEquals(Route.Today, n.current)
        assertFalse(n.canPop)
        Tab.entries.forEach { assertEquals("${it.label} 的栈该只有栈底", 1, n.stacks.getValue(it).size) }
    }

    @Test fun `压详情之后能弹回原来那一栏`() {
        val n = NavState.initial().select(Tab.HISTORY).push(Route.Meeting("t1"))
        assertEquals(Route.Meeting("t1"), n.current)
        assertTrue(n.canPop)
        val back = n.pop()!!
        assertEquals("弹完该回会议栏的栈底", Route.History, back.current)
        assertEquals(Tab.HISTORY, back.tab)
    }

    /**
     * 老实现里，从「今天」点进一场会，返回会落到「会议」栏——因为返回的目的地
     * 是手写死的。**回到的不是他刚才在的地方**，用户会以为自己按错了。
     */
    @Test fun `从今天进详情，返回回到今天而不是会议栏`() {
        val n = NavState.initial().push(Route.Meeting("t1"))
        val back = n.pop()!!
        assertEquals(Tab.TODAY, back.tab)
        assertEquals(Route.Today, back.current)
    }

    @Test fun `每一栏的栈互不干扰`() {
        val n = NavState.initial()
            .select(Tab.HISTORY).push(Route.Meeting("t1"))
            .select(Tab.ME).push(Route.Web("/zh", "首页"))
        assertEquals(Route.Web("/zh", "首页"), n.current)
        // 切回会议栏，它还停在刚才那一场
        val back = n.select(Tab.HISTORY)
        assertEquals(Route.Meeting("t1"), back.current)
    }

    @Test fun `再点一次已选中的栏，弹回那一栏的栈底`() {
        val n = NavState.initial().select(Tab.HISTORY).push(Route.Meeting("t1"))
        val again = n.select(Tab.HISTORY)
        assertEquals(Route.History, again.current)
        assertFalse(again.canPop)
    }

    /**
     * 三层退法：详情 → 栈底 → 首栏 → 交还系统。
     * **交还系统只在首栏栈底发生**，别处按返回直接退出 App 是安卓上最刺眼的不成熟信号。
     */
    @Test fun `只有首栏栈底才把返回交还给系统`() {
        assertNull("今天栈底该交还系统", NavState.initial().pop())

        val other = NavState.initial().select(Tab.ME)
        assertEquals("别的栏栈底该先回今天", Tab.TODAY, other.pop()!!.tab)

        val deep = NavState.initial().select(Tab.ME).push(Route.Web("/zh", "首页"))
        assertEquals("有东西可弹就先弹", Tab.ME, deep.pop()!!.tab)
    }

    @Test fun `同一个位置连着压两次会被忽略`() {
        val n = NavState.initial().push(Route.Meeting("t1")).push(Route.Meeting("t1"))
        assertEquals(2, n.stacks.getValue(Tab.TODAY).size)
    }

    @Test fun `跨栏跳转是一个动作，不是两步`() {
        val n = NavState.initial().open(Tab.RECORD, Route.Ble)
        assertEquals(Tab.RECORD, n.tab)
        assertEquals(Route.Ble, n.current)
        assertEquals("弹完该回录音栏栈底", Route.Record, n.pop()!!.current)
    }

    @Test fun `栈底路由和栏一一对应，详情不占栈底`() {
        val roots = listOf(Route.Today, Route.Record, Route.Ask, Route.History, Route.Me)
        assertEquals("每一栏必须有且只有一个栈底", Tab.entries.size, roots.map { it.tab }.toSet().size)
        roots.forEach { assertFalse("${it} 是栈底，不该是详情", it.isDetail) }
        listOf(Route.Meeting("x"), Route.Person("x"), Route.Web("/a", "a"))
            .forEach { assertTrue("$it 该是详情", it.isDetail) }
    }
}

/*
 * 结构门禁：每个 Route 在渲染分支里只准出现一次。
 *
 * 这条是这次重构存在的理由。老实现里 RecordScreen 出现在两个分支——
 * `Screen.Record` 一次、`Screen.Feed` + `Tab.RECORD` 又一次，
 * 而且两处的 `onOpenHistory` 一个会重新取数一个不会。**同一个界面两种行为，
 * 类型系统看不出来，评审也看不出来**，只有用户在某条路径上发现列表没刷新。
 *
 * 用读源码的方式测，和 ThemeParityTest 同一个路子：测的是不变量，不是实现细节。
 */
class NavRenderTest {

    private fun mainActivity(): String =
        File("src/main/java/com/qiuyiwu/shennao/MainActivity.kt").readText()

    /** 渲染分派那一段：`when (val r = nav.current) { ... }`。 */
    private fun renderBlock(): String {
        val src = mainActivity()
        val start = src.indexOf(RENDER_MARK)
        assertTrue(
            "MainActivity 里找不到渲染分派的锚点「$RENDER_MARK」——" +
                "要么改名了，要么这条门禁被绕过去了。改名的话把这里一起改。",
            start >= 0,
        )
        return src.substring(start)
    }

    @Test fun `每个 Route 在渲染里只出现一次`() {
        val block = renderBlock()
        val names = listOf("Today", "Record", "Ask", "History", "Me", "Ble", "Meeting", "Person", "Web")
        val dup = names.filter { n ->
            Regex("""\bis\s+Route\.$n\b|\bRoute\.$n\s*->""").findAll(block).count() != 1
        }
        assertTrue(
            "这些 Route 在渲染分支里不是恰好出现一次：$dup。" +
                "出现零次就是它渲染不出来，出现两次就是分支重复。",
            dup.isEmpty(),
        )
    }

    /**
     * **这条才是那个 bug 的门禁。**
     *
     * 第一版我断言的是「每个 Route 只出现一次」——写完拿老 bug 试了一遍，
     * 它没抓住：把 `RecordScreen` 塞进 `is Route.Ble ->` 的分支里，
     * `Route.Record` 仍然只出现一次，测试照样绿。
     *
     * 真正被违反的不变量是**「一个屏幕只能由一个分支渲染」**。
     * 老实现里 RecordScreen 出现在两个分支、`onOpenHistory` 一个会重新取数
     * 一个不会——同一个界面两种行为，看代码看不出来。
     *
     * 教训记在这儿：门禁写完必须拿它要防的那个 bug 试一次，
     * **一条永远会通过的测试比没有测试更坏**，因为它让人以为守住了。
     */
    @Test fun `每个屏幕只由一个分支渲染`() {
        val block = renderBlock()
        val screens = listOf(
            "TodayScreen", "RecordScreen", "BleScreen", "AskScreen", "HistoryScreen",
            "MeScreen", "MeetingScreen", "PersonScreen", "MeetingWebScreen",
        )
        val bad = screens.mapNotNull { s ->
            val n = Regex("""\b$s\s*\(""").findAll(block).count()
            if (n == 1) null else "$s×$n"
        }
        assertTrue(
            "这些屏幕不是恰好被渲染一次：$bad。" +
                "两次就是「同一个界面两种行为」——这正是重构要根除的那个 bug。",
            bad.isEmpty(),
        )
    }

    /**
     * 老实现里位置有两个真源（`screen` 和 `tab`），跳转要同时改两个，
     * 13 处改 tab、21 处改 screen、4 处必须写在同一行。**这是那个 bug 的机器本体。**
     */
    @Test fun `不再有独立的 tab 或 screen 可变状态`() {
        val bad = Regex("""\bvar\s+(tab|screen)\b""").findAll(mainActivity()).map { it.value }.toList()
        assertTrue("位置的真源只有 NavState，不该再有独立的：$bad", bad.isEmpty())
    }

    @Test fun `位置只装在一个可变状态里`() {
        val n = Regex("""var\s+\w+\s+by\s+remember\s*\{\s*mutableStateOf<AppState>""")
            .findAll(mainActivity()).count()
        assertEquals("应当恰好有一个 AppState 可变状态承载位置", 1, n)
    }

    companion object {
        /** 改渲染分派的写法时，把这个锚点一起改，别把门禁改没了。 */
        const val RENDER_MARK = "when (val r = nav.current)"
    }
}
