package com.qiuyiwu.shennao

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」：账号、版本、更新。
 *
 * 更新入口放在这里而不是首页：它是低频动作，而首页的位置应该留给
 * 每天都要看的东西。但**检查要自动做一次**——用户不会主动来点，
 * 而停在旧版的代价是「录了传不上去」这类他自己看不出的问题。
 */
@Composable
fun MeScreen(client: DeepBrainClient, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        scope.launch {
            state = withContext(Dispatchers.IO) { Update.check(UrlHttp(), BuildConfig.VERSION_CODE) }
            checking = false
        }
    }
    // 进来就自动查一次。用户不会主动来点，而停在旧版是看不出来的。
    LaunchedEffect(Unit) { check() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DS.Pad.default),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)

        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Text("账号", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text(client.signedInEmail() ?: "未登录",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("版本", style = MaterialTheme.typography.titleSmall)
                        Text("v${BuildConfig.VERSION_NAME}",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { check() }) { Text("检查更新") }
                }

                when (val s = state) {
                    is UpdateState.Available -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Text("有新版 v${s.release.versionName} · ${mb(s.release.sizeBytes)} MB",
                             style = MaterialTheme.typography.bodyMedium,
                             fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Button(
                            onClick = {
                                // 交给系统浏览器下载并安装。应用内静默安装需要
                                // 特权，一个从网页分发的包不该去要那种权限。
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release.url)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("下载新版") }
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        Text("装新版不用卸载旧的，登录状态和没传完的录音都会留着。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.UpToDate -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        Text("已是最新", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Unknown -> {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        // 「查不到」和「已是最新」必须分开说：网络不通不等于没有新版
                        Text("查不到有没有新版（${s.reason}）",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                    null -> Unit
                }
            }
        }

        DsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(DS.Pad.tight)) {
                Text("在网页里打开", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(DS.Rhythm.tight))
                Text("完整的转写、播放、认人、记忆库都在网页版。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(DS.Rhythm.element))
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.API_BASE}/zh")))
                }) { Text("打开深脑网页版") }
            }
        }

        var signOut by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { signOut = true }, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
        if (signOut) ConfirmDialog(
            title = "退出登录？",
            // 用户最担心的是「我还没传完的录音会不会没」。直接回答它。
            detail = "还没传完的录音会留在手机上，重新登录后接着传。",
            confirmLabel = "退出",
            onConfirm = onSignOut,
            onDismiss = { signOut = false },
        )
        Spacer(Modifier.height(DS.Rhythm.element))
    }
}

private fun mb(b: Long) = "%.1f".format(b / 1048576.0)
