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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "从录音笔导入", NotificationManager.IMPORTANCE_LOW)
        )
        gatt = BleGatt(this)
        val imp = Importer(gatt)
        importer = imp
        gatt.observeState { conn = it; lastError = gatt.lastError; refresh() }
        gatt.onNotify(Proto.CHAR_NOTIFY) { bytes ->
            parser.feed(bytes).forEach { imp.onFrame(it) }
            onImporterMoved(imp)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        // startForeground 必须在服务被拉起后很短时间内调用，所以放在最前面，
        // 且在任何可能抛的动作之前——晚一步系统会直接判 ANR 杀掉。
        runCatching { startForeground(NOTIF_ID, notification("正在从录音笔导入", "保持蓝牙开着就行")) }

        when (intent?.action) {
            ACTION_SCAN -> {
                devices = emptyList()
                gatt.scan { d ->
                    if (devices.none { it.id == d.id }) devices = devices + d
                }
            }
            ACTION_CONNECT -> {
                gatt.stopScan()
                intent.getStringExtra(EXTRA_ADDRESS)?.let { gatt.connect(it) }
            }
            ACTION_LIST -> importer?.let { it.startListing(); onImporterMoved(it) }
            ACTION_DOWNLOAD -> {
                val name = intent.getStringExtra(EXTRA_FILE)
                val entry = (state as? ImportState.Listed)?.files?.firstOrNull { it.name == name }
                picked = entry
                if (entry != null) importer?.let { it.startDownload(entry); onImporterMoved(it) }
            }
            ACTION_RESUME -> importer?.let { it.resume(); onImporterMoved(it) }
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
        if (s is ImportState.Done && was !is ImportState.Done) finish(s)
        ensureTicking(imp)
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
            } else note = "落盘失败，请重试"
            refresh()
        }.start()
    }

    private fun refresh() {
        val (title, text) = when (val s = state) {
            is ImportState.Downloading ->
                "正在导入 ${s.name}" to (
                    if (s.total > 0) "${s.got / 1024} / ${s.total / 1024} KB"
                    else "${s.got / 1024} KB"
                )
            is ImportState.Listing -> "正在读录音笔" to "列文件中"
            is ImportState.Done -> "导好了" to "正在推送到深脑"
            is ImportState.Failed -> "导入中断" to s.reason
            else -> "从录音笔导入" to "保持蓝牙开着就行"
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
