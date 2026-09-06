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
import com.qiuyiwu.shennao.R
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
        private const val NOTIF_ID = com.qiuyiwu.shennao.Notif.RECORDING

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
        /**
         * 已录多久。
         *
         * 这个值必须**每次读都是新的**：之前它由 15 秒一轮的轮询循环复制，
         * 于是界面上的计时器每 15 秒才跳一次，读起来像卡住了。
         * 改成直接问录音器——它在读音频的循环里持续更新（200 毫秒一次）。
         */
        @Volatile private var recorderRef: Recorder? = null
        val elapsedMs: Long get() = recorderRef?.elapsedMs ?: 0L

        /** 当前音量 0..1。给声波用——它是「确实在录到声音」的唯一直观证据。 */
        val level: Float get() = recorderRef?.level ?: 0f
        @Volatile var pendingSegments: Int = 0; private set
        /**
         * **录音本身**的问题：麦克风打不开、被抢走。只有这一类该出现在录音页。
         *
         * 之前它和上传失败共用一个变量，于是一按「开始录音」，屏幕上跳出来的是
         * 某条几小时前卡住的会话的「冻结清单失败 409」——用户完全没法把它
         * 和自己刚做的动作联系起来，只会以为是这次录音出了问题。
         */
        @Volatile var micError: String? = null; private set

        /** **上传**的问题。属于「会议」那一栏，不该打扰正在录音的人。 */
        @Volatile var uploadProblem: String? = null; private set

        /**
         * 当前这场在服务端的会话 id。设本场热词要用它。
         *
         * 它在**第一段传上去之后**才有——建会话是上传的第一步，而上传是
         * 封完第一段（一分钟）才开始的。所以界面上这个功能会有一分钟不可用，
         * 必须说清楚在等什么，不能只给一个灰掉的按钮。
         */
        @Volatile var serverSessionId: String? = null; private set

        /** 实时字幕：最近几句。只显示，不落盘不上传——实时稿不进长期记忆。 */
        @Volatile var captions: List<String> = emptyList(); private set
        @Volatile var captionState: String? = null; private set

        fun start(ctx: Context, title: String, scene: String? = null) {
            val i = Intent(ctx, RecordingService::class.java)
                .setAction(ACTION_START).putExtra("title", title).putExtra("scene", scene)
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
    private var realtime: Realtime? = null

    override fun onCreate() {
        super.onCreate()
        vault = FileVault(File(filesDir, "recordings"))
        recorder = Recorder(vault) { kick() }
        recorderRef = recorder
        uploader = Uploader(
            UrlHttp(), vault, BuildConfig.API_BASE,
            auth = { force -> com.qiuyiwu.shennao.Session.authFor(applicationContext, force) },
        )
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                /*
                 * startForeground 会抛。
                 *
                 * 安卓 12 起，从后台启动前台服务是被禁止的
                 * （ForegroundServiceStartNotAllowedException）。不接住就是**整个 App 崩掉**——
                 * 而崩的时候用户正想录一场会。
                 *
                 * 2026-09-01 在模拟器上撞出来的：111 个单测和 9 条界面测试都抓不到，
                 * 因为它们都不启动真正的服务。这正是把模拟器接进来的理由。
                 */
                try {
                    startForeground(NOTIF_ID, notification("正在录音", "深脑正在录这场会"))
                } catch (e: Exception) {
                    micError = "系统不让在后台开始录音——请先打开深脑，再点开始。"
                    recording = false
                    state = RecordState.IDLE
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 先把上次被杀时留下的半截录音补封了，再开新的一场——
                // 不然它们会一直躺在磁盘上，用户以为录到了，其实一直没传。
                scope.launch { recorder.recoverOrphans(); kick() }
                val title = intent.getStringExtra("title") ?: "手机录音"
                // 场合只认词表里的：intent 是公开面，别把任意字符串带到服务端去吃 400
                val scene = intent.getStringExtra("scene")?.takeIf { Scenes.isKnown(it) }
                if (recorder.start(title, System.currentTimeMillis(), scene) == null) {
                    micError = "麦克风打不开——检查权限，或者有别的应用正占着它"
                    stopSelf()
                    return START_NOT_STICKY
                }
                recording = true
                state = RecordState.RECORDING
                micError = null
                startPump()
            }
            ACTION_STOP -> {
                stopCaptions()
                recorder.stop()
                recording = false
                state = RecordState.IDLE
                serverSessionId = null
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
            var tick = 0L
            while (isActive) {
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
                    micError = "麦克风被别的应用占着，录音已停止。已录到的部分会照常推送。"
                    launch { drainUntilEmpty() }
                    return@launch
                }
                // 推送 15 秒一轮就够了（网络往返不必更勤），
                // 但通知栏的计时要每秒走——它是用户判断「还在录吗」的唯一依据。
                if (tick % 15 == 0L) drainOnce()
                tick++
                delay(1_000)
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

    /** 把当前这场的服务端 id 抄到伴生对象上，供界面设热词。 */
    private fun publishSessionId() {
        val local = recorder.currentSession ?: return
        val id = vault.readMeta(local)?.serverSessionId
        serverSessionId = id
        // 会话 id 一到手就开字幕。它要等第一段传上去才有（约一分钟），
        // 所以字幕天然比录音晚一分钟起——界面上要说清楚在等什么。
        if (id != null && realtime == null && recorder.isRecording) startCaptions(id)
    }

    /**
     * 开实时字幕。失败一律只影响字幕。
     *
     * 拿不到 ticket 的情况很多（服务端没配、会话状态不对、网络不通），
     * 每一种都不该让正在进行的录音有任何变化。
     */
    private fun startCaptions(sessionId: String) {
        scope.launch {
            val auth = com.qiuyiwu.shennao.Session.authFor(applicationContext) ?: return@launch
            val r = runCatching {
                UrlHttp().request(
                    "POST", "${BuildConfig.API_BASE}/api/recordings/$sessionId/realtime-ticket",
                    mapOf(
                        "Authorization" to "Bearer ${auth.first}",
                        "x-deepbrain-org-id" to auth.second,
                        "Content-Type" to "application/json",
                    ), "{}",
                )
            }.getOrNull() ?: return@launch
            if (r.status >= 400) {
                // 503 = 服务端没开这个功能。说清楚，不要让人以为是自己网络的问题。
                captionState = if (r.status == 503) "这台服务器没开实时字幕" else null
                return@launch
            }
            val o = runCatching { org.json.JSONObject(r.body) }.getOrNull() ?: return@launch
            val url = o.optString("gatewayUrl").takeIf { it.isNotBlank() } ?: return@launch
            val ticket = o.optString("ticket").takeIf { it.isNotBlank() } ?: return@launch

            val rt = Realtime(
                onSegment = { text, isFinal ->
                    // 只留最近几句。字幕是「现在在说什么」，不是一份稿子——
                    // 攒成长列表既占内存，也会让人去读它而不是听会。
                    val next = captions.toMutableList()
                    if (next.isNotEmpty() && !isFinal) next[next.lastIndex] = text else next.add(text)
                    captions = next.takeLast(4)
                },
                onState = { captionState = it },
            )
            realtime = rt
            recorder.realtime = rt
            rt.start(url, ticket, System.currentTimeMillis() - recorder.elapsedMs)
        }
    }

    /** 先关字幕再收尾录音——反了会让最后一段等在网络上。 */
    private fun stopCaptions() {
        recorder.realtime = null
        realtime?.stop()
        realtime = null
        captions = emptyList()
        captionState = null
    }

    private fun drainOnce() {
        // 走同一把锁：WorkManager 那条路也在推同一批文件
        val results = runCatching { synchronized(Resume.lock) { uploader.drainAll() } }.getOrElse { return }
        publishSessionId()
        pendingSegments = vault.sessions().sumOf { s ->
            vault.segments(s).count { it.state != Segment.State.UPLOADED }
        }
        // 只把不可重试的错报给界面。网络抖一下就弹一条红字，
        // 用户学会的第一件事就是无视它——那时候真出事也没人看了。
        results.values.filterIsInstance<DrainResult.Failed>()
            .firstOrNull { !it.retryable }?.let { uploadProblem = it.message }
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
        stopCaptions()
        recorder.stop()
        recording = false
        recorderRef = null
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
            .setSmallIcon(R.drawable.ic_notification)
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
