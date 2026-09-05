package com.qiuyiwu.shennao

/*
 * 导航的唯一真源。
 *
 * ## 为什么要有这个文件
 *
 * 之前 MainActivity 里是两个独立的 `remember`：`screen: Screen`（9 个变体）和
 * `tab: Tab`（5 栏）。它们不正交，于是每次跳转都得手工把两个一起改对——
 * 13 处改 tab、21 处改 screen、其中 4 处必须写在同一行里。**漏一处就是一个 bug，
 * 而编译器不会说话。**
 *
 * 漏出来的样子是真的：`RecordScreen` 被渲染在两个地方（`Screen.Record` 一次，
 * `Screen.Feed` + `Tab.RECORD` 又一次），两处传的参数还不一样——前者的
 * `onOpenHistory` 会重新取数，后者不会。同一个界面，两种行为，看代码看不出来。
 *
 * ## 模型
 *
 * **每一栏各自一个回退栈。** 位置 = (当前是哪一栏, 那一栏的栈)。
 * 返回键 = 弹当前栈；栈只剩栈底就交还给系统。
 *
 * 这样一来「在哪」只有一个答案，`Route` 与屏幕一一对应，
 * 「同一个屏幕出现在两个分支里」在结构上就不可能了——`NavRenderTest` 钉着它。
 *
 * ## 这里没有 Loading / Login / Broken
 *
 * 它们不是「位置」，是**整个 App 的阶段**：还没取到数、没登录、取数挂了。
 * 那三种情况下底栏点了也没用，用户也不该觉得自己「在某一栏里」。
 * 所以它们在 [AppState] 那一层，不进导航。
 *
 * 混进来会有一个具体的坏处：登录态一变，每一栏的栈都得跟着清一遍。
 * 分开之后，`AppState` 一换，`NavState` 整个重建，不用逐栏收拾。
 */

/** 底栏。顺序即显示顺序。 */
enum class Tab(val label: String) {
    TODAY("今天"),
    RECORD("录音"),
    SEARCH("问"),
    HISTORY("会议"),
    ME("我的"),
}

/**
 * 一个可以停留的位置。
 *
 * **每个 Route 在渲染的 `when` 里只准出现一次**——这是 `NavRenderTest` 的断言，
 * 也是这次重构要根除的那个 bug 的门禁。
 */
sealed interface Route {

    /** 这个位置属于哪一栏。null = 沉浸式，压在所有栏之上，不带底栏。 */
    val tab: Tab?

    // ── 五个栈底，与 Tab 一一对应 ────────────────────────────────
    data object Today : Route { override val tab = Tab.TODAY }
    data object Record : Route { override val tab = Tab.RECORD }
    data object Ask : Route { override val tab = Tab.SEARCH }
    data object History : Route { override val tab = Tab.HISTORY }
    data object Me : Route { override val tab = Tab.ME }

    // ── 可以压栈的详情 ──────────────────────────────────────────
    /** 从录音笔导入。和「录」是同一件事的两个来源，所以压在「录音」栏上。 */
    data object Ble : Route { override val tab = Tab.RECORD }
    data class Meeting(val transcriptId: String) : Route { override val tab: Tab? = null }
    data class Person(val personId: String) : Route { override val tab: Tab? = null }

    /**
     * 在 App 内打开网页版的某一页，带登录态。
     *
     * **不再自己记 `back`。** 老实现里每个 Web 都带一个 `back: Screen`，
     * 因为那时没有栈，返回去哪只能由调用方现算——算错过：从 path 反推会议 id 时，
     * 从「我的」打开首页（path=/zh）会拿 "zh" 去查，用户点返回看到的是错误页。
     * 现在压在栈上，弹出来自然就是他刚才在的地方。
     */
    data class Web(val path: String, val title: String) : Route { override val tab: Tab? = null }

    /** 详情类的 Route 没有自己的栈底，压在跳转发起的那一栏上。 */
    val isDetail: Boolean get() = tab == null
}

/** 五个栈底。改这里就等于改「每一栏进去先看到什么」。 */
private val ROOTS: Map<Tab, Route> = mapOf(
    Tab.TODAY to Route.Today,
    Tab.RECORD to Route.Record,
    Tab.SEARCH to Route.Ask,
    Tab.HISTORY to Route.History,
    Tab.ME to Route.Me,
)

/**
 * 位置的唯一真源。
 *
 * 所有跳转都返回一个新的 [NavState]，不存在「改一半」的中间态——
 * 这正是老实现最容易出错的地方。
 */
data class NavState(
    val tab: Tab,
    val stacks: Map<Tab, List<Route>>,
) {
    /** 现在这一屏。栈永远非空，所以这里不会抛。 */
    val current: Route get() = stacks.getValue(tab).last()

    /** 当前栈还能不能弹。 */
    val canPop: Boolean get() = stacks.getValue(tab).size > 1

    /** 压一个详情进当前栈。**同一个位置连着压两次会被忽略**——防手抖双击。 */
    fun push(route: Route): NavState {
        if (route == current) return this
        val stack = stacks.getValue(tab)
        return copy(stacks = stacks + (tab to stack + route))
    }

    /**
     * 弹一层。返回 null 表示**该交还给系统**（栈底且已在首栏）。
     *
     * 不在首栏、且栈已经见底时，先回首栏——这是安卓上的常规退法，
     * 也避免「在第三栏按返回直接退出 App」那种最刺眼的不成熟感。
     */
    fun pop(): NavState? = when {
        canPop -> copy(stacks = stacks + (tab to stacks.getValue(tab).dropLast(1)))
        tab != Tab.TODAY -> copy(tab = Tab.TODAY)
        else -> null
    }

    /**
     * 切栏。
     *
     * **再点一次已选中的栏，把那一栏弹回栈底。**这是底栏的通行语义：
     * 「回到那一屏」，而不是「回到十分钟前那一屏」。
     */
    fun select(next: Tab): NavState =
        if (next == tab) copy(stacks = stacks + (tab to listOf(ROOTS.getValue(tab))))
        else copy(tab = next)

    /** 切到某一栏并压一个详情。跨栏跳转用它，免得调用方分两步写。 */
    fun open(tab: Tab, route: Route): NavState = copy(tab = tab).push(route)

    companion object {
        /** 开机位置：今天，五个栈各自见底。 */
        fun initial(): NavState = NavState(Tab.TODAY, ROOTS.mapValues { (_, r) -> listOf(r) })
    }
}

/**
 * App 的阶段。导航只在 [Ready] 里存在。
 *
 * 分成三段而不是把 Loading/Login/Broken 塞进 Route，理由见文件头。
 * 还有一条：**「取不到」和「没有内容」是两件事**，前者走 [Broken] 带重试，
 * 后者是 [Ready] 里的空态。绝不画成同一屏。
 */
sealed interface AppState {
    data object Loading : AppState
    data class Login(val error: String? = null, val busy: Boolean = false) : AppState
    data class Broken(val message: String) : AppState
    data class Ready(val nav: NavState) : AppState
}
