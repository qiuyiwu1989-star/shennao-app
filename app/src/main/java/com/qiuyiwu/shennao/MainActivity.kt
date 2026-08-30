package com.qiuyiwu.shennao

import android.content.Context
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

/** 凭证存在 SharedPreferences。第一版先这样，上真机验通之后换 EncryptedSharedPreferences。 */
private class PrefsStore(ctx: Context) : CredentialStore {
    private val p = ctx.getSharedPreferences("shennao", Context.MODE_PRIVATE)
    override fun load(): Credentials? {
        val rt = p.getString("refresh", null) ?: return null
        val org = p.getString("org", null) ?: return null
        return Credentials(rt, org, p.getString("email", "") ?: "")
    }
    override fun save(c: Credentials) {
        p.edit().putString("refresh", c.refreshToken)
            .putString("org", c.orgId).putString("email", c.email).apply()
    }
    override fun clear() { p.edit().clear().apply() }
}

private sealed class Screen {
    object Loading : Screen()
    data class Login(val error: String? = null, val busy: Boolean = false) : Screen()
    data class Feed(val today: Today) : Screen()
    data class Broken(val message: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = DeepBrainClient(
            http = UrlHttp(),
            store = PrefsStore(this),
            apiBase = BuildConfig.API_BASE,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        )
        setContent { MaterialTheme { App(client) } }
    }
}

@Composable
private fun App(client: DeepBrainClient) {
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        screen = Screen.Loading
        screen = when (val r = withContext(Dispatchers.IO) { client.today() }) {
            is ApiResult.Ok -> Screen.Feed(r.value)
            is ApiResult.Unauthorized -> Screen.Login()
            is ApiResult.Failed -> Screen.Broken(r.message)
        }
    }

    LaunchedEffect(Unit) { load() }

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

            is Screen.Feed -> FeedScreen(s.today) { scope.launch { load() } }

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

@Composable
private fun FeedScreen(today: Today, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("下文", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))

        // 「没有承诺」和「这块坏了」在界面上长得一样，而后者用户永远不会主动报告。
        when {
            today.notReady -> Hint("下文还没准备好——服务端的迁移还没跑。")
            today.failed -> Hint("取数失败了，不是「没有承诺」。")
            today.counts.total == 0 && today.counts.awaitingSpeaker > 0 ->
                Hint("有 ${today.counts.awaitingSpeaker} 条问不了——还没认出是谁说的。到网页里认一下人。")
            today.counts.total == 0 -> Hint("还没有承诺被记下来。")
            else -> Text(
                if (today.counts.overdue > 0) "${today.counts.overdue} 条过期，共 ${today.counts.total} 条"
                else "共 ${today.counts.total} 条，都还没到期",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        today.commitments.forEach { CommitmentCard(it); Spacer(Modifier.height(10.dp)) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新") }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CommitmentCard(c: Commitment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.speakerName, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 逾期用文字说清楚，不只用颜色——颜色在阳光下和色觉障碍面前都不可靠
                val od = c.overdueDays
                Text(
                    when {
                        od != null && od > 0 -> "过期 $od 天"
                        c.dueDate != null -> c.dueDate
                        else -> "期限待确认"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (od != null && od > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 原话是主角，不是摘要——「他当时是这么说的」才问得出口
            Text("「${c.quote}」", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(c.saidDate.takeIf { it.isNotBlank() }, c.context).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
