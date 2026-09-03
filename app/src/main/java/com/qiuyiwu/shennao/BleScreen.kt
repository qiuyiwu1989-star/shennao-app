package com.qiuyiwu.shennao

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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

/**
 * 合并扫描结果：按设备地址去重，**广播了我们服务的排前面**，其余按信号强弱。
 *
 * 不按 service UUID 过滤扫描（那样会把设备自己滤掉，见 BleGatt.scan 的注释），
 * 所以列表里会混进周围其它蓝牙设备。排序就是替用户做的那一点筛选。
 */
private fun merge(old: List<BleDevice>, d: BleDevice): List<BleDevice> =
    (old.filter { it.id != d.id } + d)
        .sortedWith(compareByDescending<BleDevice> { it.advertisesOurService }.thenByDescending { it.rssi })

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
    val notice = LocalNotice.current
    val scope = rememberCoroutineScope()
    /*
     * **这一屏只是显示器。** 传输在 BleImportService 里跑。
     *
     * 以前 BleGatt / Importer 挂在这里的 remember 上，onDispose 直接 disconnect()——
     * 用户点了导入、切去看一眼别的，传输就断在半路（27 KB/s，一小时录音要传四分半）。
     * 更隐蔽的是传完之后的落盘也写在界面的 LaunchedEffect 里：文件已经从设备上
     * 完整传过来、就在内存里，这时候切走**它就没了**——设备那边显示传过，深脑里什么都没有。
     */
    var conn by remember { mutableStateOf(BleImportService.conn) }
    var devices by remember { mutableStateOf(BleImportService.devices) }
    var st by remember { mutableStateOf(BleImportService.state) }
    var denied by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(Readiness.READY) }
    var syncTotal by remember { mutableStateOf(0) }
    var syncDone by remember { mutableStateOf(0) }
    // 手动兜底列表要说清楚"这份是不是已经同步过了"——不然全部已同步之后
    // 回来看这一屏，会让人怀疑"是不是没传"。
    val registry = remember { com.qiuyiwu.shennao.ble.ImportedRegistry(ctx) }
    val currentOrgId = remember { com.qiuyiwu.shennao.PrefsStore(ctx).load()?.orgId ?: "" }

    // 轮询服务的状态。服务活着时它是真相，服务没起来时是默认值。
    // 200 毫秒足够跟上进度条，又不会为了一个 27 KB/s 的传输去空转 CPU。
    LaunchedEffect(Unit) {
        while (true) {
            conn = BleImportService.conn
            devices = BleImportService.devices
            st = BleImportService.state
            note = BleImportService.note
            syncTotal = BleImportService.syncTotal
            syncDone = BleImportService.syncDone
            BleImportService.consumeStaged()?.let { notice("已导入「$it」，正在推送到深脑") }
            delay(200)
        }
    }

    /*
     * 每次进来、以及每次回到前台都自查一遍前提。
     *
     * 用户可能在别的地方把蓝牙关了再回来——那时界面必须立刻改口，
     * 而不是等他点了扫描、拿到空列表、再去猜是哪里不对。
     */
    fun recheck() {
        val need = BlePermissions.required()
        val perm = need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        ready = BleGatt.readinessOf(ctx, perm)
    }
    LaunchedEffect(Unit) { recheck() }

    // **这里刻意没有 onDispose { disconnect() }。**
    // 那一行正是「点了导入、移开页面就停掉」的直接原因。连接的生命周期
    // 归服务管，界面来去与它无关；用户想停有通知栏上的「停止」和这一屏的按钮。

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) BleImportService.scan(ctx)
        else denied = true
    }

    fun startScan() {
        denied = false
        recheck()
        when (ready) {
            // 蓝牙没开就直接说，**不要去扫**——扫出来的空列表只会误导。
            Readiness.BLUETOOTH_OFF, Readiness.NO_BLE -> return
            Readiness.NO_PERMISSION -> { ask.launch(BlePermissions.required().toTypedArray()); return }
            Readiness.READY -> {}
        }
        BleImportService.scan(ctx)
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
        // 前提不满足时**第一时间说**，而且要在扫描按钮之前。
        // 蓝牙关着却让用户去点「查找设备」，得到的空列表只会把他指向错误的方向。
        if (ready != Readiness.READY && ready != Readiness.NO_PERMISSION) item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = DS.Radius.card) {
                Column(Modifier.padding(DS.Pad.tight)) {
                    Text(ready.message ?: "", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onErrorContainer)
                    if (ready.fixable) {
                        Spacer(Modifier.height(DS.Rhythm.element))
                        // 只给能真正解决问题的按钮。硬件不支持时给个按钮，点了没反应，
                        // 比不给更让人困惑。
                        Button(onClick = {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }) { Text("去打开蓝牙") }
                    }
                }
            }
        }
        if (denied) item {
            // 说对是哪个权限：旧系统上要的是定位，说成「蓝牙权限」用户会找不到开关
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = DS.Radius.card) {
                Text(BlePermissions.deniedHint(), Modifier.padding(DS.Pad.tight),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        note?.let { n -> item {
            Text(n, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.error)
        } }

        // ---- 1. 找设备 ----
        if (conn != BleState.READY) {
            item {
                Button(
                    onClick = { startScan() },
                    enabled = ready == Readiness.READY || ready == Readiness.NO_PERMISSION,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (conn == BleState.SCANNING) "重新查找" else "查找设备") }
            }
            // 连接失败的真实原因。Android 的 GATT 只给一个 status 数字，
            // 不翻出来的话用户只知道「连不上」，而 133 和 8 要做的事完全不同。
            if (conn == BleState.FAILED) BleImportService.lastError?.let { e -> item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = DS.Radius.card) {
                    Text(e, Modifier.padding(DS.Pad.tight),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }

            if (devices.isNotEmpty()) item {
                Text(
                    // 不按服务 UUID 过滤，所以列表里会有别的蓝牙设备。
                    // 直说，别让用户以为「这些都是录音笔」。
                    "附近所有蓝牙设备都列在这里——录音笔多半叫 CB08 或类似名字。" +
                        "带「疑似录音笔」的排在最前。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(devices, key = { it.id }) { d ->
                DsCard(Modifier.fillMaxWidth(), onClick = { BleImportService.connect(ctx, d.id) }) {
                    Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium,
                                     fontWeight = FontWeight.SemiBold)
                                if (d.advertisesOurService) {
                                    Spacer(Modifier.width(DS.Rhythm.element))
                                    Text("疑似录音笔", style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            // 信号强度直接给出来：连不上时它是唯一有用的线索。
                            // -80 以下基本连不稳，说出来省得反复试。
                            Text(
                                "信号 ${d.rssi} dBm" + if (d.rssi < -80) "（偏弱，靠近一点再连）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("连接", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (conn == BleState.SCANNING && devices.isEmpty()) item {
                Empty(
                    "还没找到",
                    "确认三件事：录音笔开着、手机蓝牙开着、" +
                        "而且它**没有连在电脑或别的手机上**——BLE 一次只能被一个主机连。",
                )
            }
        }

        // ---- 2. 同步 ----
        if (conn == BleState.READY) {
            // 一直在跑的同步进度条，跟 st 具体走到哪个子状态无关——
            // 一次自动同步会在 Listed → Downloading → Done → Downloading → …
            // 之间反复横跳，用户不该跟着这些内部状态切换看进度条一起消失又出现。
            if (syncTotal > 0) item {
                DsCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(DS.Pad.tight)) {
                        val finished = syncDone >= syncTotal
                        Text(
                            if (finished) "同步完成 · 共 $syncTotal 份"
                            else "正在自动同步 · 第 ${syncDone + 1} / $syncTotal 份",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(DS.Rhythm.tight))
                        LinearProgressIndicator(
                            progress = { if (syncTotal == 0) 0f else syncDone.toFloat() / syncTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!finished) {
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            Text("可以直接切走做别的，同步会在后台继续。",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            when (val s = st) {
                is ImportState.Idle -> item {
                    // 连上了就自动同步——不用用户先看列表、再一个个点。
                    // 之前是自动 list()，用户还要自己挑文件一个个导；
                    // 2026-09-03 用户反馈这不是"同步"，是"手动搬"，
                    // 改成连上直接同步全部还没导过的。
                    LaunchedEffect(conn) {
                        if (conn == BleState.READY) BleImportService.syncAll(ctx)
                    }
                    Loading()
                }
                is ImportState.Listing -> item { Loading() }
                is ImportState.Listed -> {
                    if (s.files.isEmpty()) item {
                        Empty("录音笔里是空的", "先用它录一段，再回来导。")
                    }
                    // key 不能只用文件名——2026-09-02 真实崩溃：CB08 曾经返回过
                    // 两条同名文件（重名本身不算错，设备允许），LazyColumn 要求
                    // key 全局唯一，撞了直接崩整个进程（IllegalArgumentException
                    // "Key ... was already used"）。带上下标就不会撞，
                    // 下载仍然传 f.name 原文——设备认的是名字，不是下标。
                    itemsIndexed(s.files, key = { i, f -> "$i:${f.name}" }) { _, f ->
                        val synced = BleImportService.connectedAddress?.let { registry.isImported(it, f.base, currentOrgId) } ?: false
                        DsCard(Modifier.fillMaxWidth(), onClick = { BleImportService.download(ctx, f.name) }) {
                            Row(Modifier.padding(DS.Pad.tight), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.base, style = MaterialTheme.typography.titleMedium)
                                    Text("${f.time / 60} 分 ${f.time % 60} 秒 · ${f.size / 1024} KB",
                                         style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    if (synced) "已同步" else "导入",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (synced) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // ---- 3. 传输中 ----
                is ImportState.Downloading -> item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.default)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(DS.Rhythm.element))
                            val f = s.fraction
                            if (f != null) LinearProgressIndicator({ f }, Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(DS.Rhythm.element))
                            Text(
                                // 字节数要给出来。一个只有百分比的进度条停住时，
                                // 用户分不清「慢」和「卡死」。
                                if (s.total > 0) "${s.got / 1024} / ${s.total / 1024} KB"
                                else "${s.got / 1024} KB（总长还没报过来）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            Text("蓝牙传输大约 27 KB 每秒，一小时的录音要四分半。",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- 4. 传完 ----
                //
                // **落盘不在这里做。** 它在 BleImportService 里，传完那一刻就做完了。
                // 以前这段写在界面的 LaunchedEffect 中：文件已经从设备上完整传过来、
                // 就在内存里，用户这时候切走，它就没了——设备那边显示传过，
                // 深脑里什么都没有，而两边都不会报错。
                is ImportState.Done -> item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.default)) {
                            Text("导好了", style = MaterialTheme.typography.titleMedium,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(DS.Rhythm.tight))
                            Text("${s.bytes.size / 1024} KB，正在推送到深脑。",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(DS.Rhythm.element))
                            Row {
                                Button(onClick = { BleImportService.list(ctx) }) { Text("再导一个") }
                                Spacer(Modifier.width(DS.Rhythm.element))
                                OutlinedButton(onClick = {
                                    BleImportService.stop(ctx); onDone()
                                }) { Text("去看进度") }
                            }
                        }
                    }
                }

                // ---- 失败：区分该重连还是该换 ----
                is ImportState.Failed -> item {
                    DsCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(DS.Pad.default)) {
                            Text(s.reason, style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(DS.Rhythm.element))
                            // 断线可以接着传，已经收到的字节还在——这是 27KB/s 链路上
                            // 最要紧的一句话。设备拒绝则没得续，只能重来。
                            if (!s.deviceSaid && BleImportService.received > 0) {
                                Button(onClick = { BleImportService.resume(ctx) }) {
                                    Text("接着传（已收 ${BleImportService.received / 1024} KB）")
                                }
                            } else {
                                Button(onClick = { BleImportService.list(ctx) }) {
                                    Text("回到文件列表")
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(DS.Rhythm.inner)) }
    }
}
