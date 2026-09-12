package com.qiuyiwu.shennao.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.qiuyiwu.shennao.MainActivity
import com.qiuyiwu.shennao.Notif
import com.qiuyiwu.shennao.R
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/*
 * 定时聆听（2026-09-12 邱：「可以设置全天录的定时」，参考 Hemory）。
 *
 * 设一次：哪几天、几点到几点。到点提醒你开始，到结束提醒你停止。
 *
 * **为什么是提醒不是自动开**：Android 14 起，后台不许自己拉起麦克风类前台服务，
 * 那是系统的隐私门，绕不过。所以到点推一条通知，点它 App 到前台再开——
 * 那一下是用户按的，系统才放行。Hemory 也是这么做的，文案里要把这一点说清楚，
 * 否则人会以为「定了时怎么没自己录」。
 *
 * 用 WorkManager 一次性任务排到下一个事件，跑完再排下一个。不用 AlarmManager 精确闹钟：
 * 那要多要一个权限，而提早晚个几分钟对「提醒你开始」无所谓。
 */
object ListenSchedule {
    const val CHANNEL = "listen"
    private const val UNIQUE = "shennao-listen-schedule"
    private const val PREFS = "shennao-listen-schedule"
    const val EXTRA_ACTION = "listen_action"
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"

    /** 一周用 Calendar 的常量：MONDAY=2 … SUNDAY=1。默认工作日 9:00–18:00。 */
    data class Config(
        val enabled: Boolean = false,
        val days: Set<Int> = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
        val startMin: Int = 9 * 60,
        val endMin: Int = 18 * 60,
    ) {
        val valid: Boolean get() = days.isNotEmpty() && endMin > startMin
    }

    /** 下一个事件：什么时候、是开始还是结束。纯函数，JVM 可测。没有（没选天 / 没开）返回 null。 */
    fun nextEvent(nowMs: Long, zone: TimeZone, cfg: Config): Pair<Long, Boolean>? {
        if (!cfg.enabled || !cfg.valid) return null
        val c = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        // 往后看 8 天：今天可能已经过了结束时间，下一个事件在下周同一天
        for (offset in 0..7) {
            val day = (c.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (day.get(Calendar.DAY_OF_WEEK) !in cfg.days) continue
            val start = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, cfg.startMin / 60); set(Calendar.MINUTE, cfg.startMin % 60) }
            val end = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, cfg.endMin / 60); set(Calendar.MINUTE, cfg.endMin % 60) }
            if (start.timeInMillis > nowMs) return start.timeInMillis to true
            if (end.timeInMillis > nowMs) return end.timeInMillis to false
        }
        return null
    }

    /** 时段那一行的话：「工作日 09:00–18:00」。纯逻辑。 */
    fun summary(cfg: Config): String {
        val names = mapOf(Calendar.MONDAY to "一", Calendar.TUESDAY to "二", Calendar.WEDNESDAY to "三", Calendar.THURSDAY to "四",
                          Calendar.FRIDAY to "五", Calendar.SATURDAY to "六", Calendar.SUNDAY to "日")
        val week = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val all = names.keys
        val days = when (cfg.days) {
            week -> "工作日"
            all -> "每天"
            else -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                .filter { it in cfg.days }.joinToString("") { names[it]!! }.let { "周$it" }
        }
        return "$days ${clock(cfg.startMin)}–${clock(cfg.endMin)}"
    }

    fun clock(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val def = Config()
        return Config(
            enabled = p.getBoolean("enabled", false),
            days = p.getString("days", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: def.days,
            startMin = p.getInt("start", def.startMin),
            endMin = p.getInt("end", def.endMin),
        )
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", cfg.enabled)
            .putString("days", cfg.days.joinToString(","))
            .putInt("start", cfg.startMin).putInt("end", cfg.endMin)
            .apply()
        schedule(ctx)
    }

    /** 排到下一个事件。没有事件就把排着的取消。开 App 时也调一次：WorkManager 的任务可能被系统清过。 */
    fun schedule(ctx: Context) {
        val cfg = load(ctx)
        val next = nextEvent(System.currentTimeMillis(), TimeZone.getDefault(), cfg)
        val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
        if (next == null) { runCatching { wm.cancelUniqueWork(UNIQUE) }; return }
        val (at, start) = next
        val req = OneTimeWorkRequestBuilder<ListenNudgeWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf("start" to start))
            .build()
        runCatching { wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req) }
    }

    fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "定时聆听", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到了设定的时段提醒你开始、结束时提醒你停止"
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** 到点了：推一条。点它进 App 就开（或停）——那一下是用户按的，系统才放行麦克风。 */
    fun nudge(ctx: Context, start: Boolean) {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, if (start) 31 else 32,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ACTION, if (start) ACTION_START else ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(if (start) "到点了，开始聆听？" else "到点了，停止聆听？")
            .setContentText(if (start) "点一下就开。系统不让深脑自己打开麦克风，这一下得你来按。" else "点一下就停。今天录下的会传到深脑。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(Notif.LISTEN_NUDGE, n) }
    }

    /** 通知点进来：开或停。返回 true = 处理了。要在 Activity 前台时调，麦克风才开得起来。 */
    fun handle(ctx: Context, intent: Intent?): Boolean {
        val a = intent?.getStringExtra(EXTRA_ACTION) ?: return false
        intent.removeExtra(EXTRA_ACTION)
        when (a) {
            ACTION_START -> if (!RecordingService.listening) RecordingService.listen(ctx)
            ACTION_STOP -> if (RecordingService.listening) RecordingService.stopListening(ctx)
        }
        return true
    }
}

class ListenNudgeWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val start = inputData.getBoolean("start", true)
        val ctx = applicationContext
        // 已经在听就不催开；没在听就不催停。
        val listening = RecordingService.listening
        if (start != listening) ListenSchedule.nudge(ctx, start)
        ListenSchedule.schedule(ctx)
        return Result.success()
    }
}
