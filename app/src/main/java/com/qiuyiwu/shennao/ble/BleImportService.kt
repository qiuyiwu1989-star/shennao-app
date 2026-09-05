package com.qiuyiwu.shennao.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.qiuyiwu.shennao.MainActivity
import com.qiuyiwu.shennao.R
import com.qiuyiwu.shennao.record.FileVault
import com.qiuyiwu.shennao.record.UploadWorker
import java.io.File

/*
 * 从录音笔导入的前台服务。
 *
 * **为什么必须是服务。** 之前 BleGatt / FrameParser / Importer 全挂在
 * BleScreen 的 remember 上，DisposableEffect 的 onDispose 里直接 disconnect()——
 * 用户点了导入、切去看别的东西，传输就断了。实测带宽 27 KB/s，一小时录音要传
 * 四分半；「传这四分半必须一直盯着这一屏」不是一个能用的产品。
 *
 * 更隐蔽的一处：传完之后的 Ingest.stage（落盘 + 入上传队列）原来写在
 * 界面的 LaunchedEffect 里。也就是说**文件已经从设备上传完了、内存里拿着，
 * 这时候切走，它就没了**——设备那边显示传过，深脑里什么都没有。
 *
 * 状态用 companion 的 @Volatile 字段暴露，与 RecordingService 同一套写法：
 * 界面轮询，服务活着时它是真相。不引入额外的 IPC。
 */
class BleImportService : Service() {

    companion object {
        const val ACTION_SCAN = "scan"
        const val ACTION_CONNECT = "connect"
        const val ACTION_LIST = "list"
        const val ACTION_DOWNLOAD = "download"
        const val ACTION_STOP = "stop"
        const val ACTION_RESUME = "resume"
        const val ACTION_SYNC_ALL = "sync_all"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_FILE = "file"

        private const val CHANNEL = "ble-import"
        private const val NOTIF_ID = 2

        /** 服务没起来时这些是默认值——界面据此显示「还没开始」，不是「坏了」。 */
        @Volatile var running: Boolean = false; private set
        @Volatile var conn: BleState = BleState.IDLE; private set
        @Volatile var state: ImportState = ImportState.Idle; private set
        @Volatile var devices: List<BleDevice> = emptyList(); private set
        /**
         * 落盘结果。传完 ≠ 导入成功——还要解出录音时刻、写进 vault。
         * 这一步失败过而界面显示「导好了」，所以它单独有一个字段。
         */
        @Volatile var staged: String? = null; private set
        @Volatile var note: String? = null; private set
        /** GATT 失败的真实原因。133 和 8 要做的事完全不同，不报出来无从查起。 */
        @Volatile var lastError: String? = null; private set
        /** 已经收到多少字节。断线续传的按钮上要写明——27 KB/s 的链路上这是最要紧的一句。 */
        @Volatile var received: Long = 0L; private set
        /**
         * 自动同步的整体进度。**只在真的有一批文件排着队的时候才非零**——
         * 手动点单个文件下载时不动它，界面据此分辨「我在自动同步」还是
         * 「用户自己在挑一个文件」。
         */
        @Volatile var syncTotal: Int = 0; private set
        @Volatile var syncDone: Int = 0; private set
        /** 当前连着的设备地址。界面要用它去问 ImportedRegistry「这份文件同步过没有」。 */
        @Volatile var connectedAddress: String? = null; private set
        /**
         * 连着的这张卡的电量 / 容量 / 固件。连上就问一次，应答到了就并进来。
         * **TYPE=0 是 2026-09-05 按协议文档新写的，没有真机验证**——解不出的字段是 null，
         * 界面上显示「—」，不编数。
         */
        @Volatile var info: DeviceInfo = DeviceInfo(); private set

        /**
         * 一键同步：列完文件后自动挨个下载**还没导过的**，不用用户一个个点。
         *
         * 2026-09-03 用户反馈：现在的流程是"连上→自己看列表→自己点每一个
         * 文件→自己点下一个"，这不是"同步"，是"手动搬"。BLE 一次只能下
         * 一个文件（协议本身的限制），所以"同时下载"做不到，但"排好队、
         * 一个接一个、不用用户守着"是能做到的——这正是这里要做的事。
         */
        fun syncAll(ctx: Context) = send(ctx, ACTION_SYNC_ALL)

        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)

        fun scan(ctx: Context) = send(ctx, ACTION_SCAN)
        fun connect(ctx: Context, address: String) =
            send(ctx, ACTION_CONNECT) { it.putExtra(EXTRA_ADDRESS, address) }
        fun list(ctx: Context) = send(ctx, ACTION_LIST)
        fun download(ctx: Context, name: String) =
            send(ctx, ACTION_DOWNLOAD) { it.putExtra(EXTRA_FILE, name) }
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** 界面消费掉一次性的提示，避免它一直挂着。 */
        fun consumeStaged(): String? { val s = staged; staged = null; return s }

        private fun send(ctx: Context, action: String, fill: (Intent) -> Unit = {}) {
            val i = Intent(ctx, BleImportService::class.java).setAction(action)
            fill(i)
            // 已经起来的服务用 startService 就够；没起来时必须 startForegroundService，
            // 否则安卓 8 以上直接抛 IllegalStateException。
            runCatching {
                if (running) ctx.startService(i) else ctx.startForegroundService(i)
            }
        }
    }

    private lateinit var gatt: BleGatt
    private val parser = FrameParser("AE22")
    private var importer: Importer? = null
    /** 当前在下的那一条。落盘要用它的时长和文件名。 */
    private var picked: FileEntry? = null
    /** 超时看门狗。设备可能一声不吭，没人问的话状态会永远停在 Downloading。 */
    private var ticker: Thread? = null
    /** 自动同步排队中还没下的文件。为空 = 没有在自动同步（手动模式）。 */
    private var syncQueue: MutableList<FileEntry> = mutableListOf()
    private lateinit var registry: ImportedRegistry
    /** 列表刚回来时，是不是该自动接上同步——区分「用户手动点了查看列表」。 */
    private var autoSyncOnListed = false
    /**
     * 当前同步这一批时用的账号 id。只在 beginSync 读一次——PrefsStore 每次
     * 构造都要碰系统 keystore（EncryptedSharedPreferences），一批几十个
     * 文件的话，每份文件完成都重新读一遍纯粹是浪费。
     */
    private var syncOrgId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "从灵魂卡导入", NotificationManager.IMPORTANCE_LOW)
        )
        gatt = BleGatt(this)
        val imp = Importer(gatt)
        importer = imp
        gatt.observeState {
            conn = it; lastError = gatt.lastError
            if (it == BleState.READY) {
                // 刚连上：先对表，再问三个数。
                // 对表是因为灵魂卡的文件名带录音时刻，它的钟偏了整条时间轴都跟着偏。
                // 四条都是发出去就完的命令，不等应答——应答到了在下面的 onNotify 里并进 info。
                info = DeviceInfo()
                gatt.write(Proto.buildSyncTime(System.currentTimeMillis()))
                gatt.write(Proto.buildFrame(Proto.T.CTRL, Proto.CtrlCmd.BAT_REQ))
                gatt.write(Proto.buildFrame(Proto.T.CTRL, Proto.CtrlCmd.CAP_REQ))
                gatt.write(Proto.buildFrame(Proto.T.CTRL, Proto.CtrlCmd.FW_REQ))
                // 连上就同步全部还没导过的（2026-09-03 用户反馈「手动搬」不算同步）。
                // 以前这句在 BleScreen 的 LaunchedEffect 里；靠近即同步时没有界面在，所以挪到服务里——
                // 服务是真相，界面只是看。
                if (imp.state is ImportState.Idle) {
                    autoSyncOnListed = true
                    imp.startListing(); onImporterMoved(imp)
                }
            } else if (it == BleState.DISCONNECTED || it == BleState.IDLE) {
                info = DeviceInfo()
            }
            refresh()
        }
        gatt.onNotify(Proto.CHAR_NOTIFY) { bytes ->
            parser.feed(bytes).forEach { f ->
                imp.onFrame(f)
                info = DeviceInfo.merge(info, f)
            }
            onImporterMoved(imp)
        }
        registry = ImportedRegistry(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        // startForeground 必须在服务被拉起后很短时间内调用，所以放在最前面，
        // 且在任何可能抛的动作之前——晚一步系统会直接判 ANR 杀掉。
        runCatching { startForeground(NOTIF_ID, notification("正在从灵魂卡导入", "保持蓝牙开着就行")) }

        when (intent?.action) {
            ACTION_SCAN -> {
                devices = emptyList()
                gatt.scan { d ->
                    if (devices.none { it.id == d.id }) devices = devices + d
                }
            }
            ACTION_CONNECT -> {
                gatt.stopScan()
                intent.getStringExtra(EXTRA_ADDRESS)?.let {
                    connectedAddress = it
                    gatt.connect(it)
                }
            }
            ACTION_LIST -> {
                autoSyncOnListed = false
                importer?.let { it.startListing(); onImporterMoved(it) }
            }
            ACTION_SYNC_ALL -> {
                autoSyncOnListed = true
                importer?.let { it.startListing(); onImporterMoved(it) }
            }
            ACTION_DOWNLOAD -> {
                val name = intent.getStringExtra(EXTRA_FILE)
                val entry = (state as? ImportState.Listed)?.files?.firstOrNull { it.name == name }
                picked = entry
                if (entry != null) importer?.let { it.startDownload(entry); onImporterMoved(it) }
            }
            ACTION_RESUME -> importer?.let { it.resume(); onImporterMoved(it) }
            // 同步中途断线，用户点了「接着传」——这仍然算在同一次同步里，
            // 完成之后要继续排队里剩下的文件，不能表现得像一次孤立的手动下载。
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    /**
     * 每次导入器状态可能变了都调一次。
     *
     * 传完（Done）时**立刻在服务里落盘**，不留给界面——界面随时会没。
     */
    private fun onImporterMoved(imp: Importer) {
        val s = imp.state
        val was = state
        state = s
        received = imp.received
        lastError = gatt.lastError
        refresh()
        if (s is ImportState.Listed && autoSyncOnListed) {
            autoSyncOnListed = false
            beginSync(s.files)
        }
        if (s is ImportState.Done && was !is ImportState.Done) finish(s)
        // 设备明确拒绝这一个文件（deviceSaid=true）：这份录音这次导不了，
        // 但不该因为一个坏文件让整批同步停下——跳过它，接着同步排队里
        // 剩下的。断线（deviceSaid=false）不在这里处理：那是可续传的，
        // 用户点「接着传」时走的是 resume()，picked 没变，队列自然接得上。
        if (s is ImportState.Failed && s.deviceSaid && syncQueue.isNotEmpty()) {
            syncQueue.removeAt(0)
            note = "跳过了一份文件（设备说没有），接着同步剩下的"
            if (syncQueue.isNotEmpty()) downloadNextInQueue() else refresh()
        }
        ensureTicking(imp)
    }

    /**
     * 列完文件后，筛掉已经导过的，把剩下的排成一队，自动挨个下载。
     *
     * **判据只信本地账本，不信设备。** 录音笔自己不知道"这份是不是导过"——
     * 它只会老老实实报出它存着的每一个文件。跳不跳过，完全是手机这边
     * 记不记得住的事。
     */
    private fun beginSync(files: List<FileEntry>) {
        val addr = connectedAddress ?: ""
        syncOrgId = com.qiuyiwu.shennao.PrefsStore(this).load()?.orgId ?: ""
        syncQueue = files.filterNot { registry.isImported(addr, it.base, syncOrgId) }.toMutableList()
        syncTotal = syncQueue.size
        syncDone = 0
        if (syncQueue.isEmpty()) { note = null; refresh(); return }
        downloadNextInQueue()
    }

    private fun downloadNextInQueue() {
        val entry = syncQueue.firstOrNull() ?: return
        picked = entry
        importer?.let { it.startDownload(entry); onImporterMoved(it) }
    }

    /**
     * 只在「在等设备回话」的时候跑看门狗，等到了就自己停。
     *
     * 这个循环以前在界面的 LaunchedEffect 里——也就是说切走之后，
     * 一个不吭声的设备再也不会被判超时，进度条会一直停在那儿，
     * 而用户以为还在传。
     */
    @Synchronized
    private fun ensureTicking(imp: Importer) {
        val busy = state is ImportState.Listing || state is ImportState.Downloading
        if (!busy || ticker?.isAlive == true) return
        ticker = Thread {
            while (state is ImportState.Listing || state is ImportState.Downloading) {
                Thread.sleep(1000)
                val s = imp.tick()
                if (s !== state) {
                    val was = state
                    state = s
                    refresh()
                    if (s is ImportState.Done && was !is ImportState.Done) finish(s)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun finish(s: ImportState.Done) {
        val entry = picked
        val started = Ingest.startedAtFrom(s.name)
        if (entry == null || started == null) {
            // 解不出录音时刻就不往下走：拿「现在」顶的话，一场三天前的会
            // 会排到今天，而深脑的时间轴建立在它上面。
            note = "这个文件名里没有录音时刻，暂时导不了：${s.name}"
            // 这一份卡住了，但不能让它把整批同步也卡住——照样往后走。
            advanceQueuePast(entry)
            refresh()
            return
        }
        // 落盘可能要几百毫秒（几 MB 的写），不放主线程。
        Thread {
            val ok = runCatching {
                Ingest.stage(
                    FileVault(File(filesDir, "recordings")),
                    s.bytes, entry.base, entry.time * 1000, started,
                )
            }.getOrNull()
            if (ok != null) {
                UploadWorker.kick(this)
                note = null
                staged = entry.base
                // 记进本地账本——下次同步（甚至下次连这支笔）不用再传一遍。
                // 手动下载单个文件（不在自动同步批次里）时 syncOrgId 还没读过——
                // 现读一次。单个文件的这次读盘代价可以忽略，跟批量同步时
                // 「每份文件都读一遍」是完全不同的量级。
                connectedAddress?.let { addr ->
                    val org = syncOrgId.ifEmpty { com.qiuyiwu.shennao.PrefsStore(this).load()?.orgId ?: "" }
                    if (org.isNotEmpty()) registry.markImported(addr, entry.base, org)
                }
                advanceQueuePast(entry, success = true)
            } else {
                note = "落盘失败，请重试"
                // 落盘失败不标记「已导入」——留在队列里等下次同步重试，
                // 但这一轮不再自动往下走：接连失败多半是共同原因（比如存储满了），
                // 一次性把剩下的都试一遍只会把同一个错误重复报很多次。
            }
            refresh()
        }.start()
    }

    /** 这一份处理完了（不管成功与否），把它从同步队列挪走，继续下一份。 */
    private fun advanceQueuePast(entry: FileEntry?, success: Boolean = false) {
        if (syncQueue.isEmpty() || syncQueue.first() != entry) return
        syncQueue.removeAt(0)
        if (success) syncDone++
        if (syncQueue.isNotEmpty()) downloadNextInQueue()
        else if (syncTotal > 0) {
            // 一批同步完了：进度通知转成结果，别留一条已经没用的进度条。
            // 用另一个 id，不覆盖前台那条——前台通知归服务生命周期管。
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIF_ID + 1,
                    notification("$syncDone 段已从灵魂卡导入", "正在推送到深脑。到「记录」看每一段走到哪一站。"),
                )
            }
            // 上传完了分析就会跟上，两分钟后去看一眼有没有新判断
            com.qiuyiwu.shennao.NewJudgments.checkSoon(this)
        }
    }

    private fun refresh() {
        // 排着队自动同步时，标题带上整体进度——这是这一整块功能唯一
        // 直接给用户看的地方：他很可能已经切去别的应用，通知栏是唯一入口。
        val syncPrefix = if (syncTotal > 0) "同步 ${syncDone + 1}/$syncTotal · " else ""
        val (title, text) = when (val s = state) {
            is ImportState.Downloading ->
                "${syncPrefix}正在导入 ${s.name}" to (
                    if (s.total > 0) "${s.got / 1024} / ${s.total / 1024} KB"
                    else "${s.got / 1024} KB"
                )
            is ImportState.Listing -> "正在读灵魂卡" to "列文件中"
            is ImportState.Done -> "${syncPrefix}导好了" to "正在推送到深脑"
            is ImportState.Failed -> "导入中断" to s.reason
            is ImportState.Listed ->
                if (syncTotal > 0 && syncDone >= syncTotal) "同步完成" to "共 $syncTotal 份，已推送到深脑"
                else "从灵魂卡导入" to "保持蓝牙开着就行"
            else -> "从灵魂卡导入" to "保持蓝牙开着就行"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(title, text))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BleImportService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap).setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停止", stop,
                ).build()
            )
            .build()
    }

    private fun shutdown() {
        runCatching { gatt.stopScan() }
        runCatching { gatt.disconnect() }
        running = false
        conn = BleState.IDLE
        state = ImportState.Idle
        devices = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 进程被杀时也要断干净，否则下次连会撞上一个还没释放的 GATT 客户端
        // （一个 App 只有 32 个）。
        runCatching { gatt.disconnect() }
        running = false
        super.onDestroy()
    }
}
