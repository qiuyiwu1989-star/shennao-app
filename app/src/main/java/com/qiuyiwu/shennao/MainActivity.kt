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
        receiveShare(intent)
    }

    /** singleTask：App 已经在跑时，分享进来走这里而不是 onCreate。 */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    /*
     * 分享进来的文件：落盘 → 交给上传管道 → 说一句。
     *
     * 只做这三件。不在这里问场合、不在这里改名——那些进来之后随时能改，
     * **一个分享动作只该按一次按钮**。落完立刻 kick UploadWorker：
     * 它能活过 App 被划掉，用户分享完就切走是常态。
     */
    private fun receiveShare(intent: android.content.Intent?) {
        val uris = com.qiuyiwu.shennao.record.ShareIn.urisOf(intent)
        if (uris.isEmpty()) return
        // 同一个 intent 只处理一次：转屏、从最近任务回来都会再次拿到它
        intent?.removeExtra(android.content.Intent.EXTRA_STREAM)
        val ctx = applicationContext
        Thread {
            val vault = com.qiuyiwu.shennao.record.FileVault(java.io.File(ctx.filesDir, "recordings"))
            val results = uris.map { com.qiuyiwu.shennao.record.ShareIn.stage(ctx, vault, it) }
            val staged = results.filterIsInstance<com.qiuyiwu.shennao.record.ShareIn.Result.Staged>()
            val skipped = results.filterIsInstance<com.qiuyiwu.shennao.record.ShareIn.Result.Skipped>()
            if (staged.isNotEmpty()) com.qiuyiwu.shennao.record.UploadWorker.kick(ctx)
            val msg = buildString {
                if (staged.size == 1) append("已收进深脑：${staged[0].title}，正在上传")
                else if (staged.size > 1) append("已收进 ${staged.size} 段，正在上传")
                // 跳过的要说原因。只说「1 个失败」和「设备没这份」在屏幕上长得一样。
                skipped.forEach { append(if (isEmpty()) "" else "；").append("${it.title}：${it.why}") }
                if (staged.isNotEmpty()) append("。到「记录」看进度")
            }
            runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }
}

@Composable
private fun App(client: DeepBrainClient) {
    /*
     * **位置只有一个真源。**
     *
     * 之前是 `screen` 和 `tab` 两个 remember，跳转要同时改对两个——13 处改 tab、
     * 21 处改 screen、4 处必须写在同一行。漏一处就是一个 bug，而编译器不会说话。
     * 漏出来的样子是真的：RecordScreen 渲染在两个分支里，两处的 onOpenHistory
     * 一个会重新取数一个不会。
     *
     * 现在位置装在 AppState.Ready(nav) 里，每一栏各自一个回退栈。见 Nav.kt。
     */
    var st by remember { mutableStateOf<AppState>(AppState.Loading) }
    /** 取到的「今天」。它是数据不是位置，所以不进 NavState。 */
    var today by remember { mutableStateOf<Today?>(null) }
    var stale by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cache = remember { Cache(java.io.File(ctx.cacheDir, "mobile")) }

    /** 保住当前位置地取数：登录态还在的话，不要把人踢回「今天」。 */
    suspend fun load(keepNav: Boolean = true) {
        val r = withContext(Dispatchers.IO) { client.today() }
        val nav = (st as? AppState.Ready)?.nav?.takeIf { keepNav } ?: NavState.initial()
        if (r is ApiResult.Ok) {
            stale = null
            // 存原始 json 而不是解析后的对象：解析规则会随版本变，
            // 存对象等于把当前这版的理解冻在磁盘上。
            withContext(Dispatchers.IO) {
                client.rawTodayOrNull()?.let { cache.save(Cache.TODAY, it) }
            }
            today = r.value
            st = AppState.Ready(nav)
            return
        }
        if (r is ApiResult.Unauthorized) { today = null; st = AppState.Login(); return }
        // 取不到就拿上次的显示。地铁里、信号差的会议室——这时候一个转圈
        // 等于这个东西在最需要它的场合不能用。
        val c = withContext(Dispatchers.IO) { cache.load(Cache.TODAY) }
        val parsed = c?.let { runCatching { TodayParser.parse(it.body) }.getOrNull() }
        if (parsed != null) {
            stale = Cache.staleLabel(c.savedAt, System.currentTimeMillis()) ?: "离线 · 刚才的"
            today = parsed
            st = AppState.Ready(nav)
            return
        }
        st = AppState.Broken((r as? ApiResult.Failed)?.message ?: "取数失败")
    }

    LaunchedEffect(Unit) { load(keepNav = false) }

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
    LaunchedEffect(st) {
        if (st is AppState.Ready && android.os.Build.VERSION.SDK_INT >= 33) {
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

    /*
     * 系统返回键。
     *
     * 之前没接：在会议详情里按返回会**直接退出 App**——这是安卓上最刺眼的
     * 「不成熟」信号，用户会以为自己把东西弄丢了。
     *
     * 现在不用再手工枚举组合：pop() 返回 null 就表示该交还给系统。
     * 三层退法（详情 → 栈底 → 首栏 → 系统）全在 NavState.pop() 里，
     * 加新屏幕不用回来补分支。
     */
    val ready = st as? AppState.Ready
    androidx.activity.compose.BackHandler(enabled = ready?.nav?.pop() != null) {
        ready?.nav?.pop()?.let { st = AppState.Ready(it) }
    }

    val notices = remember { androidx.compose.material3.SnackbarHostState() }
    val notice: (String) -> Unit = { msg ->
        scope.launch {
            // 先把上一条挤掉。排队等三秒再弹的提示，说的已经不是眼前这件事了。
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(msg, withDismissAction = true)
        }
    }

    /** 换一个位置。所有跳转都走它，不存在「改一半」的中间态。 */
    fun go(next: NavState) { st = AppState.Ready(next) }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(notices) },
        /*
         * 录音是「今天」右下的主按钮，不占底栏。
         *
         * 录是动作不是地方：放进导航栏占掉五分之一的常驻位置，却只在按下那一秒
         * 有意义——而且老实现正因为它既是栏又是屏，RecordScreen 被渲染在两个地方。
         * 只在「今天」栈底显示：别的屏各有各的主动作，再叠一个会抢。
         */
        floatingActionButton = {
            val nav = ready?.nav ?: return@Scaffold
            if (nav.current == Route.Today) {
                FloatingActionButton(
                    onClick = { go(nav.push(Route.Record)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    androidx.compose.material3.Icon(MicOutlined, contentDescription = "录音")
                }
            }
        },
        // 内容区自己让开状态栏；底栏由 NavigationBar 让开手势条（它自带 inset）。
        // 两处都交给系统算，不写死 dp——不同机型的刘海和手势条高度不一样。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
            .only(androidx.compose.foundation.layout.WindowInsetsSides.Top),
        bottomBar = {
            // 登录、加载、出错这三种状态不该有底栏——底栏在那时点了也没用，
            // 只会让人以为「是不是我点错了地方」。
            val nav = ready?.nav ?: return@Scaffold
            // 沉浸式（录音中）不带底栏：那时候点底栏也没用，显示出来只会让人以为点错了。
            if (!nav.showChrome) return@Scaffold
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = nav.tab == t,
                        onClick = {
                            go(nav.select(t))
                            // 「今天」要重新取数：底栏的意义是「回到那一屏」，
                            // 而不是「回到十分钟前那一屏」。
                            if (t == Tab.TODAY) scope.launch { load() }
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
            when (val s = st) {
                is AppState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                is AppState.Login -> LoginScreen(s) { email, pw ->
                    scope.launch {
                        st = AppState.Login(busy = true)
                        when (val r = withContext(Dispatchers.IO) { client.signIn(email, pw) }) {
                            is ApiResult.Ok -> load(keepNav = false)
                            is ApiResult.Failed -> st = AppState.Login(error = r.message)
                            else -> st = AppState.Login(error = "登录失败")
                        }
                    }
                }

                // 「取不到」和「没有内容」在屏幕上长得一样，但对用户是两件事。
                // 统一走 Broken，绝不画成空屏。
                is AppState.Broken -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Broken(s.message) { scope.launch { load(keepNav = false) } }
                }

                is AppState.Ready -> {
                    val nav = s.nav
                    /*
                     * 渲染分派。**每个 Route 在这里只准出现一次**——
                     * NavRenderTest 钉着这条。老实现里 RecordScreen 出现两次、
                     * 参数还不一样，就是从这里漏出去的。
                     */
                    when (val r = nav.current) {
                        is Route.Today -> {
                            val t = today
                            if (t == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                            else TodayScreen(
                                today = t,
                                // 跟「会议」栏一致：先进 App 内的会议详情（判断、承诺、
                                // 谁在场），要看逐句转写再从那一页点进网页版。
                                //
                                // 这里原来是甩给系统浏览器一个裸链接——跟很早以前
                                // 「在网页里看完整转写」那个按钮是同一个错：用户在
                                // Chrome 里多半没登录，点开「今天」的一条判断，
                                // 看到的是深脑的登录页，而他刚刚明明就在 App 里登着。
                                onOpenTranscript = { tid -> go(nav.push(Route.Meeting(tid))) },
                                onRecord = { go(nav.push(Route.Record)) },
                                onRefresh = { scope.launch { load() } },
                                staleLabel = stale,
                                onPullRefresh = { load() },
                                onSettle = { id, action ->
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) { client.settleCommitment(id, action) }
                                        // 落账失败要说出来。界面已经先显示「已记」了——
                                        // 乐观更新用起来顺手，但失败时必须收回来，
                                        // 否则账本上没有这一笔，而用户以为记过了。
                                        if (res !is ApiResult.Ok) {
                                            load()
                                            notice(when (res) {
                                                is ApiResult.Failed -> "没记上：${res.message}"
                                                is ApiResult.Unauthorized -> "没记上：登录失效了，重新登录后再点一次"
                                                else -> "没记上，请再点一次"
                                            })
                                        }
                                    }
                                },
                            )
                        }

                        is Route.Record -> RecordScreen(
                            onBack = { nav.pop()?.let { go(it) }; scope.launch { load() } },
                            onImport = { go(nav.push(Route.Ble)) },
                            onOpenHistory = { go(nav.select(Tab.RECORDS)) },
                        )

                        is Route.Ble -> BleScreen(onDone = { go(nav.select(Tab.RECORDS)) })

                        is Route.Ask -> AskScreen(client) { tid -> go(nav.push(Route.Meeting(tid))) }

                        is Route.Records -> HistoryScreen(
                            client = client,
                            onRecord = { go(nav.push(Route.Record)) },
                            onOpen = { tid -> go(nav.push(Route.Meeting(tid))) },
                            onOpenBle = { go(nav.push(Route.Ble)) },
                        )

                        is Route.Me -> MeScreen(
                            client = client,
                            onOpenWeb = { path, title -> go(nav.push(Route.Web(path, title))) },
                            onSignOut = {
                                client.signOut()
                                today = null
                                st = AppState.Login()
                            },
                        )

                        is Route.Meeting -> MeetingScreen(
                            client, r.transcriptId,
                            onBack = { nav.pop()?.let { go(it) } },
                            onOpenWeb = { path, title -> go(nav.push(Route.Web(path, title))) },
                        )

                        is Route.Person -> PersonScreen(
                            client, r.personId,
                            onBack = { nav.pop()?.let { go(it) } },
                            onOpen = { tid -> go(nav.push(Route.Meeting(tid))) },
                            onRecord = { go(nav.push(Route.Record)) },
                        )

                        is Route.Web -> MeetingWebScreen(
                            client, r.path, r.title,
                            // 返回去哪不再由调用方现算——弹栈弹出来的自然就是他刚才在的地方。
                            onBack = { nav.pop()?.let { go(it) } },
                        )
                    }
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
        Tab.RECORDS -> Icons.Outlined.List
        // 放大镜说的是「搜索」。这一栏是问答，图标得跟着改口，
        // 否则底栏和屏幕里说的是两件事。
        Tab.ASK -> Icons.Outlined.Send
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
private fun LoginScreen(state: AppState.Login, onSubmit: (String, String) -> Unit) {
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
