package com.qiuyiwu.shennao

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qiuyiwu.shennao.ble.*
import com.qiuyiwu.shennao.record.FileVault
import com.qiuyiwu.shennao.record.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 从录音笔导入。
 *
 * 这一屏最要紧的不是好看，是**始终说清楚在哪一步**：
 * 导一个小时的录音要四分半（实测带宽 27 KB/s），中间任何一刻用户都可能
 * 怀疑「是不是卡了」。所以每一步都有名字，进度带着字节数，
 * 断了明说「断了，可以接着传」而不是回到起点。
 */
@Composable
fun BleScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gatt = remember { BleGatt(ctx) }
    val parser = remember { FrameParser("AE22") }
    val importer = remember { Importer(gatt) }

    var conn by remember { mutableStateOf(BleState.IDLE) }
    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }
    var st by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    var denied by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<FileEntry?>(null) }

    DisposableEffect(Unit) {
        gatt.observeState { conn = it }
        gatt.onNotify(Proto.CHAR_NOTIFY) { bytes ->
            parser.feed(bytes).forEach { importer.onFrame(it) }
            st = importer.state
        }
        onDispose { gatt.disconnect() }
    }

    // 设备可能一声不吭。不主动查的话界面会永远转圈。
    LaunchedEffect(st) {
        while (st is ImportState.Listing || st is ImportState.Downloading) {
            delay(1000)
            st = importer.tick()
        }
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) { devices = emptyList(); gatt.scan { d -> devices = (devices + d).distinctBy { it.id } } }
        else denied = true
    }

    fun startScan() {
        denied = false
        val need = BlePermissions.required()
        val ok = need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (ok) { devices = emptyList(); gatt.scan { d -> devices = (devices + d).distinctBy { it.id } } }
        else ask.launch(need.toTypedArray())
    }

    ListPage(
        title = "从录音笔导入",
        subtitle = when (conn) {
            BleState.SCANNING -> "正在找附近的录音笔…"
            BleState.CONNECTING -> "正在连接…"
            BleState.READY -> "已连上"
            BleState.DISCONNECTED -> "连接断了"
            BleState.FAILED -> "连不上"
            else -> "打开录音笔的蓝牙，然后点「查找设备」"
        },
        isEmpty = false,
        empty = {},
    ) {
        if (denied) item {
            // 说对是哪个权限：旧系统上要的是定位，说成「蓝牙权限」用户会找不到开关
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = DS.Radius.card) {
                Text(BlePermissions.deniedHint(), Modifier.padding(16.dp),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        note?.let { n -> item {
            Text(n, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        } }

        // ---- 1. 找设备 ----
        if (conn != BleState.READY) {
            item {
                Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)) {
                    Text(if (conn == BleState.SCANNING) "重新查找" else "查找设备")
                }
            }
            items(devices, key = { it.id }) { d ->
                DsCard(Modifier.fillMaxWidth(), onClick = { gatt.connect(d.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.SemiBold)
                            // 信号强度直接给出来：连不上时它是唯一有用的线索
                            Text("信号 ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("连接", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (conn == BleState.SCANNING && devices.isEmpty()) item {
                Empty("还没找到", "确认录音笔开着、且没有被别的手机连着。")
            }
        }

        // ---- 2. 列文件 ----
        if (conn == BleState.READY) {
            when (val s = st) {
                is ImportState.Idle -> item {
                    Button(onClick = { importer.startListing(); st = importer.state },
                           modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("看看里面有什么")
                    }
                }
                is ImportState.Listing -> item { Loading() }
                is ImportState.Listed -> {
                    if (s.files.isEmpty()) item {
                        Empty("录音笔里是空的", "先用它录一段，再回来导。")
                    }
                    items(s.files, key = { it.name }) { f ->
                        DsCard(Modifier.fillMaxWidth(), onClick = {
                            picked = f
                            importer.startDownload(f)
                            st = importer.state
                        }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.base, style = MaterialTheme.typography.titleSmall)
                                    Text("${f.time / 60} 分 ${f.time % 60} 秒 · ${f.size / 1024} KB",
                                         style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("导入", style = MaterialTheme.typography.labelMedium,
                                     color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // ---- 3. 传输中 ----
                is ImportState.Downloading -> item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(10.dp))
                            val f = s.fraction
                            if (f != null) LinearProgressIndicator({ f }, Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                // 字节数要给出来。一个只有百分比的进度条停住时，
                                // 用户分不清「慢」和「卡死」。
                                if (s.total > 0) "${s.got / 1024} / ${s.total / 1024} KB"
                                else "${s.got / 1024} KB（总长还没报过来）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("蓝牙传输大约 27 KB 每秒，一小时的录音要四分半。",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- 4. 传完，交给上传链路 ----
                is ImportState.Done -> item {
                    LaunchedEffect(s.name) {
                        val entry = picked
                        val started = Ingest.startedAtFrom(s.name)
                        if (entry == null || started == null) {
                            // 解不出录音时刻就不要往下走：拿「现在」顶的话，
                            // 一场三天前的会会排到今天，而深脑的时间轴建立在它上面。
                            note = "这个文件名里没有录音时刻，暂时导不了：${s.name}"
                        } else {
                            val ok = withContext(Dispatchers.IO) {
                                Ingest.stage(
                                    FileVault(File(ctx.filesDir, "recordings")),
                                    s.bytes, entry.base, entry.time * 1000, started,
                                )
                            }
                            if (ok != null) {
                                UploadWorker.kick(ctx)
                                note = null
                            } else note = "落盘失败，请重试"
                        }
                    }
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text("导好了", style = MaterialTheme.typography.titleMedium,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("${s.bytes.size / 1024} KB，正在推送到深脑。",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            Row {
                                Button(onClick = {
                                    importer.startListing(); st = importer.state
                                }) { Text("再导一个") }
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(onClick = onDone) { Text("去看进度") }
                            }
                        }
                    }
                }

                // ---- 失败：区分该重连还是该换 ----
                is ImportState.Failed -> item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(s.reason, style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            // 断线可以接着传，已经收到的字节还在——这是 27KB/s 链路上
                            // 最要紧的一句话。设备拒绝则没得续，只能重来。
                            if (!s.deviceSaid && importer.received > 0) {
                                Button(onClick = { importer.resume(); st = importer.state }) {
                                    Text("接着传（已收 ${importer.received / 1024} KB）")
                                }
                            } else {
                                Button(onClick = { importer.startListing(); st = importer.state }) {
                                    Text("回到文件列表")
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
