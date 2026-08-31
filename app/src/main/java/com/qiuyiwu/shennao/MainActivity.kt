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
    TODAY("今天"), RECORD("录音"), HISTORY("在路上"), ME("我的")
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

    suspend fun load() {
        screen = Screen.Loading
        screen = when (val r = withContext(Dispatchers.IO) { client.today() }) {
            is ApiResult.Ok -> Screen.Feed(r.value)
            is ApiResult.Unauthorized -> Screen.Login()
            is ApiResult.Failed -> Screen.Broken(r.message)
        }
    }

    LaunchedEffect(Unit) { load() }

    // 上次没传完的录音，开 App 就接着传。服务可能在传完前就被系统杀掉了，
    // 没有这一步，那些段会一直躺在手机上，而录音页写着「下次打开接着传」。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { com.qiuyiwu.shennao.record.Resume.kick(ctx) } }
        // 这次没推完的交给 WorkManager：它能活过 App 被划掉，甚至活过重启
        com.qiuyiwu.shennao.record.UploadWorker.kick(ctx)
    }

    var tab by remember { mutableStateOf(Tab.TODAY) }

    // 登录、加载、出错这三种状态不该有底栏——底栏在那时点了也没用，
    // 只会让人以为「是不是我点错地方了」。
    val s0 = screen
    val chrome = s0 is Screen.Feed || s0 is Screen.Record || s0 is Screen.Meeting

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

                is Screen.Feed -> when (tab) {
                    Tab.RECORD -> RecordScreen(onBack = { tab = Tab.TODAY; scope.launch { load() } })
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
                    )
                }

                is Screen.Broken -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { load() } }) { Text("重试") }
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
        Tab.HISTORY -> Icons.Outlined.Send
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
