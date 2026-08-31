package com.qiuyiwu.shennao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = Session.client(this)
        setContent { MaterialTheme { App(client) } }
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

    Surface(Modifier.fillMaxSize()) {
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

            is Screen.Record -> RecordScreen(onBack = { scope.launch { load() } })

            is Screen.Feed -> TodayScreen(
                today = s.today,
                onRecord = { screen = Screen.Record },
                // 下钻先用外跳浏览器：那场会的完整转写、播放、认人都在网页里，
                // 在 App 里再实现一遍是重复造，而且必然比网页那份旧。
                // 等「录」做完、App 有了自己的内容再说。
                onOpenTranscript = { tid ->
                    ctx.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("${BuildConfig.API_BASE}/zh/transcript/$tid")))
                },
                onRefresh = { scope.launch { load() } },
            )

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
