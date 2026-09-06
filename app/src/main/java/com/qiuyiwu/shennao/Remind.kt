package com.qiuyiwu.shennao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

/*
 * 到期提醒。
 *
 * **这是手机端相对网页的唯一优势**：网页没法在你不打开它的时候提醒你。
 * 而这一层内容恰恰是有时效的——一条今天到期的承诺，明天再问就更难开口。
 * 在此之前 App 里有一套「该不该提醒」的判断（Api.notification），
 * 却没有任何东西去跑它，等于建好了没接上。
 *
 * 一天一次，不是有变化就推。理由：
 *   · 承诺到期是按天算的，小时级的精度没有意义
 *   · 推得勤，用户学会的第一件事是把通知关掉——那时真正要紧的也送不到了
 */

object Remind {
    const val CHANNEL = "due"
    private const val UNIQUE = "shennao-daily"
    private const val NOTIF_ID = Notif.DAILY_REMIND
    private const val PREFS = "shennao-remind"

    /** 每天几点提醒。早上九点：一天开始时看到「今天有三条要问」还来得及安排。 */
    const val HOUR = 9

    /**
     * 距离下一个提醒时刻还有多久。纯函数，可单测——
     * 差一天或者差一个时区，用户就会在半夜被叫醒，而那种 bug 只发生在别人身上。
     */
    fun initialDelayMs(nowMs: Long, zone: java.util.TimeZone, hour: Int = HOUR): Long {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val target = (c.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= nowMs) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - nowMs
    }

    /**
     * 同一天同样的内容不重复推。
     *
     * 没有这一条，WorkManager 因为各种原因重跑时会把同一句话推第二遍，
     * 而用户对「重复的通知」的容忍度比对「没有通知」低得多。
     */
    fun shouldNotify(lastKey: String?, todayKey: String): Boolean = lastKey != todayKey

    fun keyOf(dayStamp: String, message: String): String = "$dayStamp|$message"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs(System.currentTimeMillis(), java.util.TimeZone.getDefault()),
                             TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(ctx)
                // KEEP：每次开 App 都 REPLACE 的话，初始延迟会被不断重置，
                // 一个每天开好几次 App 的用户将永远等不到第一次提醒。
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "到期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "承诺到期、预测该给说法时提醒一次"
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun post(ctx: Context, message: String) {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("今天有要问的事")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    fun lastKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

    fun forget(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    fun rememberKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", key).apply()
    }
}

class DailyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val r = runCatching { Session.client(ctx).today() }.getOrNull() ?: return Result.retry()
        if (r !is ApiResult.Ok) {
            // 没登录就别再重试了：重试解决不了没登录，只会每天空跑。
            return if (r is ApiResult.Unauthorized) Result.success() else Result.retry()
        }
        val msg = TodayParser.notification(r.value) ?: return Result.success()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val key = Remind.keyOf(day, msg)
        if (!Remind.shouldNotify(Remind.lastKey(ctx), key)) return Result.success()
        Remind.post(ctx, msg)
        Remind.rememberKey(ctx, key)
        return Result.success()
    }
}
