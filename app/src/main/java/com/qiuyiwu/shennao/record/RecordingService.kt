package com.qiuyiwu.shennao.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.qiuyiwu.shennao.BuildConfig
import com.qiuyiwu.shennao.MainActivity
import com.qiuyiwu.shennao.UrlHttp
import kotlinx.coroutines.*
import java.io.File

/*
 * 录音的前台服务。
 *
 * 为什么必须是前台服务：安卓会冻结切到后台的进程。开会时人一定会切走看别的东西，
 * 录音写在 Activity 里等于「一低头就断」。前台服务 + 常驻通知是系统唯一承认的
 * 「我正在做一件用户知情的长活」。
 *
 * 服务同时管两件事：录音线程、和一个每 15 秒跑一次的上传轮询。
 * 放一起是因为它们共享同一个 vault，而分成两个进程要处理的并发远比省下的复杂度多。
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        /** 界面轮询这几个字段。服务活着时它是真相，服务没起来时都是默认值。 */
        @Volatile var recording: Boolean = false; private set
        @Volatile var elapsedMs: Long = 0; private set
        @Volatile var pendingSegments: Int = 0; private set
        @Volatile var lastError: String? = null; private set

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, RecordingService::class.java)
                .setAction(ACTION_START).putExtra("title", title)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var vault: FileVault
    private lateinit var recorder: Recorder
    private lateinit var uploader: Uploader
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pump: Job? = null

    override fun onCreate() {
        super.onCreate()
        vault = FileVault(File(filesDir, "recordings"))
        recorder = Recorder(vault) { kick() }
        uploader = Uploader(
            UrlHttp(), vault, BuildConfig.API_BASE,
            auth = { force -> com.qiuyiwu.shennao.Session.authFor(applicationContext, force) },
        )
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, notification("正在录音", "深脑正在录这场会"))
                // 先把上次被杀时留下的半截录音补封了，再开新的一场——
                // 不然它们会一直躺在磁盘上，用户以为录到了，其实一直没传。
                scope.launch { recorder.recoverOrphans(); kick() }
                val title = intent.getStringExtra("title") ?: "手机录音"
                if (recorder.start(title, System.currentTimeMillis()) == null) {
                    lastError = "麦克风打不开——检查权限，或者有别的应用正占着它"
                    stopSelf()
                    return START_NOT_STICKY
                }
                recording = true
                lastError = null
                startPump()
            }
            ACTION_STOP -> {
                recorder.stop()
                recording = false
                // 停止之后不能立刻退出服务：还有分段没传完。
                // 转成一条「正在上传」的通知继续跑，传完了再自己退。
                updateNotification("正在上传", "录音已停止，正在推送到深脑")
                scope.launch { drainUntilEmpty() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startPump() {
        pump?.cancel()
        pump = scope.launch {
            while (isActive) {
                elapsedMs = recorder.elapsedMs
                updateNotification("正在录音", "已录 ${fmt(elapsedMs)}")
                drainOnce()
                delay(15_000)
            }
        }
    }

    /** 有新段封好了，立刻催一次，不用等下一个轮询周期。 */
    private fun kick() { scope.launch { drainOnce() } }

    private fun drainOnce() {
        val results = runCatching { uploader.drainAll() }.getOrElse { return }
        pendingSegments = vault.sessions().sumOf { s ->
            vault.segments(s).count { it.state != Segment.State.UPLOADED }
        }
        // 只把不可重试的错报给界面。网络抖一下就弹一条红字，
        // 用户学会的第一件事就是无视它——那时候真出事也没人看了。
        results.values.filterIsInstance<DrainResult.Failed>()
            .firstOrNull { !it.retryable }?.let { lastError = it.message }
    }

    private suspend fun drainUntilEmpty() {
        repeat(40) {                       // 最多试 40 轮（约 10 分钟），之后交给下次启动
            drainOnce()
            if (pendingSegments == 0) {
                pump?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            delay(15_000)
        }
        pump?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder.stop()
        recording = false
        pump?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 通知 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "录音", NotificationManager.IMPORTANCE_LOW).apply {
            description = "录音进行中的常驻提示"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(title: String, text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(title, text))
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s < 3600) "%d:%02d".format(s / 60, s % 60)
        else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
