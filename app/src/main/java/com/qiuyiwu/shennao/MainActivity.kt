package com.qiuyiwu.shennao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
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
    /** 一个人的页。从任何一条卡片走过来 */
    data class Person(val personId: String) : Screen()
    data class Broken(val message: String) : Screen()
    /** 一场会的详情。从历史点进来 */
    data class Meeting(val transcriptId: String) : Screen()
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
    TODAY("今天"), RECORD("录音"), SEARCH("搜索"), HISTORY("会议"), ME("我的")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
        enabled = screen is Screen.Meeting || screen is Screen.Person ||
                  (screen is Screen.Feed && tab != Tab.TODAY)
    ) {
        when {
            screen is Screen.Meeting -> { tab = Tab.HISTORY; scope.launch { load() } }
            screen is Screen.Person -> scope.launch { load() }
            else -> tab = Tab.TODAY
        }
    }

    // 登录、加载、出错这三种状态不该有底栏——底栏在那时点了也没用，
    // 只会让人以为「是不是我点错地方了」。
    val s0 = screen
    val chrome = s0 is Screen.Feed || s0 is Screen.Record ||
                 s0 is Screen.Meeting || s0 is Screen.Person

    Scaffold(
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

                is Screen.Record -> RecordScreen(onBack = { tab = Tab.TODAY; scope.launch { load() } })

                is Screen.Meeting -> MeetingScreen(client, s.transcriptId,
                    onBack = { tab = Tab.HISTORY; scope.launch { load() } })

                is Screen.Person -> PersonScreen(client, s.personId,
                    onBack = { scope.launch { load() } },
                    onOpen = { tid -> screen = Screen.Meeting(tid) })

                is Screen.Feed -> when (tab) {
                    Tab.RECORD -> RecordScreen(onBack = { tab = Tab.TODAY; scope.launch { load() } })
                    Tab.SEARCH -> SearchScreen(client) { tid -> screen = Screen.Meeting(tid) }
                    Tab.HISTORY -> HistoryScreen(
                        client = client,
                        onRecord = { tab = Tab.RECORD; screen = Screen.Record },
                        onOpen = { tid -> screen = Screen.Meeting(tid) },
                    )
                    Tab.ME -> MeScreen(client) {
                        client.signOut()
                        tab = Tab.TODAY
                        screen = Screen.Login()
                    }
                    Tab.TODAY -> TodayScreen(
                        today = s.today,
                        // 下钻走浏览器：那场会的完整转写、播放、认人都在网页里，
                        // 在 App 里再实现一遍是重复造，而且必然比网页那份旧。
                        onOpenTranscript = { tid ->
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("${BuildConfig.API_BASE}/zh/transcript/$tid")))
                        },
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
                                if (r !is ApiResult.Ok) load()
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

@Composable
private fun TabIcon(t: Tab) {
    val v = when (t) {
        Tab.TODAY -> Icons.Outlined.Home
        Tab.RECORD -> MicOutlined
        Tab.SEARCH -> Icons.Outlined.Search
        Tab.HISTORY -> Icons.Outlined.List
        Tab.ME -> Icons.Outlined.Person
    }
    // 明确用 material3 的 Icon：material 和 material3 各有一个同名可组合项，
    // 不指明会是重载歧义。
    androidx.compose.material3.Icon(v, contentDescription = t.label)
}

@Composable
private fun LoginScreen(state: Screen.Login, onSubmit: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("深脑", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("登录后看你的下文", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("邮箱") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Spacer(Modifier.height(10.dp))
            Text(state.error, color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(email.trim(), pw) },
            enabled = !state.busy && email.isNotBlank() && pw.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "登录中…" else "登录") }
    }
}
