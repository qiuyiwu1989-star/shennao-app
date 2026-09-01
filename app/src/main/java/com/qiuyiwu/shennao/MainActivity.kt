package com.qiuyiwu.shennao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 第一期只做一条闭环：到期的承诺。
 *
 * 为什么先做这条，而不是洞察流：它是唯一有时间压力的东西——到期就是到期，
 * 今天不看明天就过了。洞察可以攒着慢慢读，承诺不行。而且它天然闭环，
 * 不用另外设计「看完该干嘛」。
 */

private sealed class Screen {
    object Loading : Screen()
    data class Login(val error: String? = null, val busy: Boolean = false) : Screen()
    data class Feed(val today: Today) : Screen()
    object Record : Screen()
    /** 从蓝牙录音笔导入。和「录」是同一件事的两个来源，所以进同一栏。 */
    object Ble : Screen()
    /** 一个人的页。从任何一条卡片走过来 */
    data class Person(val personId: String) : Screen()
    data class Broken(val message: String) : Screen()
    /** 一场会的详情。从历史点进来 */
    data class Meeting(val transcriptId: String) : Screen()
    /**
     * 在 App 内打开网页版的某一页，带登录态。逐句转写、播放、认人都在那边。
     *
     * **back 由调用方给，不猜。** 曾经在这里从 path 反推「返回哪去」
     * （取最后一段当会议 id），从「会议详情」进来时凑巧猜对；从「我的」
     * 打开首页（path=/zh）就会拿 "zh" 当会议 id 去查，查不到，
     * 用户点返回看到的是一个错误页而不是他刚才在的地方。
     */
    data class Web(val path: String, val title: String, val back: Screen) : Screen()
}

/**
 * 底部四栏。
 *
 * 刻意只有四个，而且是网页版的**子集 + 手机独有的「录」**：
 * 网页是铺开的（驾驶舱、记忆库、方法广场…），手机是一扫而过的场景，
 * 原样搬过来只会让它变难用。所以每一栏都要能回答一个当下的问题——
 *   今天：现在该看什么      录音：把这场会录下来
 *   在路上：录的东西到了吗   我的：账号和版本
 */
private enum class Tab(val label: String) {
    // 「搜索」改成「问」：人在路上想起来的是问题，不是关键词。
    // 关键词检索没删，它挪到了真正有用的那一刻——深脑说「依据不够」的时候。
    TODAY("今天"), RECORD("录音"), SEARCH("问"), HISTORY("会议"), ME("我的")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用——这一行本身不「显示」闪屏，
        // 它是把 Activity 主题从 Theme.App.Starting 切回正常主题的开关，
        // 闪屏内容早在系统绘制第一帧时就已经用 windowSplashScreenBackground
        // 画出来了。晚调用/漏调用的表现是闪屏画面卡住不消失。
        installSplashScreen()
        /*
         * 边到边 + 安全区。
         *
         * 不做这个，全面屏上内容会跑到状态栏和手势条底下——顶上的「返回」被刘海压住、
         * 底部导航栏被手势条盖掉一半。这是安卓上最刺眼的一处「这不是原生应用」。
         *
         * enableEdgeToEdge 之后**必须自己处理 inset**（下面 Scaffold 的
         * contentWindowInsets 和底栏），否则只是把问题从「有黑边」换成「被盖住」。
         */
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val client = Session.client(this)
        setContent { ShennaoTheme { App(client) } }
    }
}

@Composable
private fun App(client: DeepBrainClient) {
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val cache = remember { Cache(java.io.File(ctx.cacheDir, "mobile")) }
    var stale by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val r = withContext(Dispatchers.IO) { client.today() }
        if (r is ApiResult.Ok) {
            stale = null
            // 存原始 json 而不是解析后的对象：解析规则会随版本变，
            // 存对象等于把当前这版的理解冻在磁盘上。
            withContext(Dispatchers.IO) {
                client.rawTodayOrNull()?.let { cache.save(Cache.TODAY, it) }
            }
            screen = Screen.Feed(r.value)
            return
        }
        if (r is ApiResult.Unauthorized) { screen = Screen.Login(); return }
        // 取不到就拿上次的显示。地铁里、信号差的会议室——这时候一个转圈
        // 等于这个东西在最需要它的场合不能用。
        val c = withContext(Dispatchers.IO) { cache.load(Cache.TODAY) }
        if (c != null) {
            val parsed = runCatching { TodayParser.parse(c.body) }.getOrNull()
            if (parsed != null) {
                stale = Cache.staleLabel(c.savedAt, System.currentTimeMillis()) ?: "离线 · 刚才的"
                screen = Screen.Feed(parsed)
                return
            }
        }
        screen = Screen.Broken((r as? ApiResult.Failed)?.message ?: "取数失败")
    }

    LaunchedEffect(Unit) { load() }

    // 上次是不是崩溃退出的，有就传一次。放在这里而不是 Application：
    // 那时进程还没跑稳，起网络请求容易跟别的初始化抢资源；这里已经是
    // 「正常启动」的健康时机。不需要登录态——见 Crash.kt 的说明。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Crash.uploadLastIfAny(ctx, UrlHttp(), BuildConfig.API_BASE) }
    }

    /*
     * 安卓 13 起通知要用户点头。清单里声明了但从不申请，等于通知永远不显示——
     * 而「建好了没接上」正是这一层最容易犯、又最不会被发现的错：
     * 没有人会来报「我没收到通知」，他只会觉得这个 App 没这个功能。
     *
     * 在登录之后才问：一个还没登录的人不知道你要提醒他什么，
     * 开屏就弹权限框的应用，大多数人会直接拒。
     */
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(screen) {
        if (screen is Screen.Feed && android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 上次没传完的录音，开 App 就接着传。服务可能在传完前就被系统杀掉了，
    // 没有这一步，那些段会一直躺在手机上，而录音页写着「下次打开接着传」。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { com.qiuyiwu.shennao.record.Resume.kick(ctx) } }
        // 这次没推完的交给 WorkManager：它能活过 App 被划掉，甚至活过重启
        com.qiuyiwu.shennao.record.UploadWorker.kick(ctx)
        // 每天提醒一次。网页做不到这件事——它没法在你不打开它的时候提醒你，
        // 而这一层内容恰恰是有时效的。
        Remind.schedule(ctx)
    }

    var tab by remember { mutableStateOf(Tab.TODAY) }

    /*
     * 系统返回键。
     *
     * 之前没接：在会议详情里按返回会**直接退出 App**——这是安卓上最刺眼的
     * 「不成熟」信号，用户会以为自己把东西弄丢了。
     * 三层退法：详情 → 历史 → 今天 → 交还给系统（此时退出才是对的）。
     */
    androidx.activity.compose.BackHandler(
        enabled = screen is Screen.Meeting || screen is Screen.Person || screen is Screen.Ble ||
                  (screen is Screen.Feed && tab != Tab.TODAY)
    ) {
        when {
            screen is Screen.Meeting -> { tab = Tab.HISTORY; scope.launch { load() } }
            screen is Screen.Person -> scope.launch { load() }
            screen is Screen.Ble -> { tab = Tab.RECORD; screen = Screen.Record }
            else -> tab = Tab.TODAY
        }
    }

    // 登录、加载、出错这三种状态不该有底栏——底栏在那时点了也没用，
    // 只会让人以为「是不是我点错地方了」。
    val s0 = screen
    val chrome = s0 is Screen.Feed || s0 is Screen.Record ||
                 s0 is Screen.Meeting || s0 is Screen.Person || s0 is Screen.Ble

    val notices = remember { androidx.compose.material3.SnackbarHostState() }
    val notice: (String) -> Unit = { msg ->
        scope.launch {
            // 先把上一条挤掉。排队等三秒再弹的提示，说的已经不是眼前这件事了。
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(msg, withDismissAction = true)
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(notices) },
        // 内容区自己让开状态栏；底栏由 NavigationBar 让开手势条（它自带 inset）。
        // 两处都交给系统算，不写死 dp——不同机型的刘海和手势条高度不一样。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
            .only(androidx.compose.foundation.layout.WindowInsetsSides.Top),
        bottomBar = {
            if (chrome) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            // 「今天」要重新取数：底栏的意义是「回到那一屏」，
                            // 而不是「回到十分钟前那一屏」。
                            if (t == Tab.TODAY) scope.launch { load() }
                            if (t == Tab.RECORD) screen = Screen.Record
                            else if (screen is Screen.Record && t != Tab.RECORD) scope.launch { load() }
                        },
                        icon = { TabIcon(t) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { pad ->
        androidx.compose.runtime.CompositionLocalProvider(LocalNotice provides notice) {
        Surface(Modifier.fillMaxSize().padding(pad)) {
            when (val s = screen) {
                is Screen.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                is Screen.Login -> LoginScreen(s) { email, pw ->
                    scope.launch {
                        screen = Screen.Login(busy = true)
                        when (val r = withContext(Dispatchers.IO) { client.signIn(email, pw) }) {
                            is ApiResult.Ok -> load()
                            is ApiResult.Failed -> screen = Screen.Login(error = r.message)
                            else -> screen = Screen.Login(error = "登录失败")
                        }
                    }
                }

                is Screen.Record -> RecordScreen(
                    onBack = { tab = Tab.TODAY; scope.launch { load() } },
                    onImport = { screen = Screen.Ble },
                )

                is Screen.Ble -> BleScreen(onDone = { tab = Tab.HISTORY; scope.launch { load() } })

                is Screen.Web -> MeetingWebScreen(client, s.path, s.title,
                    onBack = { screen = s.back })

                is Screen.Meeting -> MeetingScreen(client, s.transcriptId,
                    onBack = { tab = Tab.HISTORY; scope.launch { load() } },
                    onOpenWeb = { path, title -> screen = Screen.Web(path, title, back = s) })

                is Screen.Person -> PersonScreen(client, s.personId,
                    onBack = { scope.launch { load() } },
                    onOpen = { tid -> screen = Screen.Meeting(tid) },
                    onRecord = { tab = Tab.RECORD; screen = Screen.Record })

                is Screen.Feed -> when (tab) {
                    Tab.RECORD -> RecordScreen(
                        onBack = { tab = Tab.TODAY; scope.launch { load() } },
                        onImport = { screen = Screen.Ble },
                    )
                    Tab.SEARCH -> AskScreen(client) { tid -> screen = Screen.Meeting(tid) }
                    Tab.HISTORY -> HistoryScreen(
                        client = client,
                        onRecord = { tab = Tab.RECORD; screen = Screen.Record },
                        onOpen = { tid -> screen = Screen.Meeting(tid) },
                    )
                    Tab.ME -> MeScreen(
                        client = client,
                        onOpenWeb = { path, title -> screen = Screen.Web(path, title, back = s) },
                        onSignOut = {
                            client.signOut()
                            tab = Tab.TODAY
                            screen = Screen.Login()
                        },
                    )
                    Tab.TODAY -> TodayScreen(
                        today = s.today,
                        // 跟「会议」栏一致：先进 App 内的会议详情（判断、承诺、
                        // 谁在场），要看逐句转写再从那一页点进网页版。
                        //
                        // 这里原来是甩给系统浏览器一个裸链接——跟很早以前
                        // 「在网页里看完整转写」那个按钮是同一个错：用户在
                        // Chrome 里多半没登录，点开「今天」的一条判断，
                        // 看到的是深脑的登录页，而他刚刚明明就在 App 里登着。
                        onOpenTranscript = { tid -> screen = Screen.Meeting(tid) },
                        onRecord = { tab = Tab.RECORD; screen = Screen.Record },
                        onRefresh = { scope.launch { load() } },
                        staleLabel = stale,
                        onPullRefresh = { load() },
                        onSettle = { id, action ->
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { client.settleCommitment(id, action) }
                                // 落账失败要说出来。界面已经先显示「已记」了——
                                // 乐观更新用起来顺手，但失败时必须收回来，
                                // 否则账本上没有这一笔，而用户以为记过了。
                                if (r !is ApiResult.Ok) {
                                    load()
                                    notice(when (r) {
                                        is ApiResult.Failed -> "没记上：${r.message}"
                                        is ApiResult.Unauthorized -> "没记上：登录失效了，重新登录后再点一次"
                                        else -> "没记上，请再点一次"
                                    })
                                }
                            }
                        },
                    )
                }

                // 「取不到」和「没有内容」在屏幕上长得一样，但对用户是两件事。
                // 统一走 Broken，绝不画成空屏。
                is Screen.Broken -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Broken(s.message) { scope.launch { load() } }
                }
            }
        }
        }
    }
}

@Composable
private fun TabIcon(t: Tab) {
    val v = when (t) {
        Tab.TODAY -> Icons.Outlined.Home
        Tab.RECORD -> MicOutlined
        // 放大镜说的是「搜索」。这一栏现在是问答，图标得跟着改口，
        // 否则底栏和屏幕里说的是两件事。
        Tab.SEARCH -> Icons.Outlined.Send
        Tab.HISTORY -> Icons.Outlined.List
        Tab.ME -> Icons.Outlined.Person
    }
    // 明确用 material3 的 Icon：material 和 material3 各有一个同名可组合项，
    // 不指明会是重载歧义。
    //
    // contentDescription 用栏目名而不是 null：底栏是这个 App 唯一的导航，
    // 读屏用户看不到旁边的文字标签时，就只剩这一句。
    androidx.compose.material3.Icon(v, contentDescription = t.label)
}

@Composable
private fun LoginScreen(state: Screen.Login, onSubmit: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(DS.Pad.focus),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("深脑", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(DS.Rhythm.tight))
        Text("登录后看你的下文", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(DS.Rhythm.inner))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("邮箱") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(DS.Rhythm.element))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Spacer(Modifier.height(DS.Rhythm.element))
            Text(state.error, color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(DS.Rhythm.inner))
        Button(
            onClick = { onSubmit(email.trim(), pw) },
            enabled = !state.busy && email.isNotBlank() && pw.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "登录中…" else "登录") }
    }
}
