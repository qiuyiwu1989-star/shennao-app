package com.qiuyiwu.shennao.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.drawable.Icon
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
        /**
         * 录音此刻的真实状态。
         *
         * v0.3 只有一个 recording 布尔，而它是在「按下开始」时置真、
         * 「按下停止」时置假的——录音线程因为来电死掉时它不会变。
         * 表现是界面一直显示「正在录音」、计时器停在原地，其实一个字都没在录。
         * 现在它照抄录音器的状态，不自己维护。
         */
        @Volatile var state: RecordState = RecordState.IDLE; private set
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
                state = RecordState.RECORDING
                lastError = null
                startPump()
            }
            ACTION_STOP -> {
                recorder.stop()
                recording = false
                state = RecordState.IDLE
                // 停止这一刻最要紧：接下来这个服务就要退了，剩下的段要有人接手
                UploadWorker.kick(applicationContext)
                // 停止之后不能立刻退出服务：还有分段没传完。
                // 转成一条「正在上传」的通知继续跑，传完了再自己退。
                updateNotification("正在上传", "录音已停止，正在推送到深脑", canStop = false)
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
                state = recorder.state
                recording = recorder.isRecording
                // 通知栏也要说真话。中断时还挂着「正在录音 12:34」，
                // 用户看一眼就放心地继续开会了。
                when (state) {
                    RecordState.INTERRUPTED -> updateNotification(
                        "录音中断了", "麦克风被占用，正在抢回来。已录 ${fmt(elapsedMs)} 都在")
                    RecordState.GAVE_UP -> updateNotification(
                        "录音已停止", "麦克风抢不回来。已录 ${fmt(elapsedMs)} 正在推送", canStop = false)
                    else -> updateNotification("正在录音", "已录 ${fmt(elapsedMs)}")
                }
                if (state == RecordState.GAVE_UP) {
                    // 录音线程自己放弃了。把已经录到的传完就收工——
                    // 让服务空转着不会让麦克风回来。
                    lastError = "麦克风被别的应用占着，录音已停止。已录到的部分会照常推送。"
                    launch { drainUntilEmpty() }
                    return@launch
                }
                drainOnce()
                delay(15_000)
            }
        }
    }

    /**
     * 有新段封好了，立刻催一次，不用等下一个轮询周期。
     *
     * 同时排一个 WorkManager 任务：服务活不过「用户把 App 从最近任务里划走」，
     * 而那一刻没传完的段只躺在手机上——用户以为已经进深脑了。
     */
    private fun kick() {
        scope.launch { drainOnce() }
        UploadWorker.kick(applicationContext)
    }

    private fun drainOnce() {
        // 走同一把锁：WorkManager 那条路也在推同一批文件
        val results = runCatching { synchronized(Resume.lock) { uploader.drainAll() } }.getOrElse { return }
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

    /**
     * 用户把 App 从最近任务里划走了。
     *
     * **什么都不做是对的**：录音是用户明确交代过的长活，划走 App 只是
     * 「把界面收起来」，不是「停止录音」。安卓的默认行为（stopWithTask=false）
     * 本来就不会停掉已启动的服务，这里显式写出来，是为了下一个人不会
     * 顺手加个 stopSelf() 来「清理」——那会让整场会在切走的一瞬间没掉。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 故意不调 stopSelf()
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

    private fun notification(title: String, text: String, canStop: Boolean = true): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
        // 通知栏里直接能停。录音时 App 是藏在后台的——没有这个按钮，
        // 想停就得先把 App 从一堆应用里翻出来，而「开完会顺手停掉」
        // 本该是一个动作的事。
        if (canStop) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "停止", stop,
                ).build()
            )
        }
        return b.build()
    }

    private fun updateNotification(title: String, text: String, canStop: Boolean = true) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(title, text, canStop))
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s < 3600) "%d:%02d".format(s / 60, s % 60)
        else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
