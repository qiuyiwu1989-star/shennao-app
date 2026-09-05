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
 * 「有新判断」——四类通知里之前缺的那一类。
 *
 * 分析在服务端跑，几分钟后才完。App 这时多半已经被划走了，
 * 所以要有一个不依赖界面的东西去看「哪一场刚分析完」，看到了就推一条。
 *
 * **只在「有新判断」时推，并且把最狠的那一条带出来。**
 * 推「分析完成了」没有意义，推「三个人带着三个答案散场了」才会让人点开。
 * 没有判断（一场会就是没结论）就不推——那不是打扰的理由。
 */
object NewJudgments {
    private const val UNIQUE = "new-judgments"
    private const val CHANNEL = "new-judgments"
    private const val PREFS = "new_judgments"
    private const val NOTIF_BASE = 4000

    /** 每次开 App 排一次（KEEP）；上传完之后再补一次 2 分钟后的单次检查，别让人等 15 分钟。 */
    fun schedule(ctx: Context) {
        val periodic = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }

    fun checkSoon(ctx: Context) {
        val once = OneTimeWorkRequestBuilder<Worker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("$UNIQUE-soon", ExistingWorkPolicy.REPLACE, once) }
    }

    // ── 纯逻辑，JVM 可测 ────────────────────────────────────────

    /**
     * 这一轮新分析完的。**第一次跑不推**：把已经分析完的全记下来当基线，
     * 否则装上 App 的那一刻会收到几十条历史通知。
     */
    fun newlyAnalyzed(known: Set<String>?, cards: List<SessionCard>): List<SessionCard> {
        val analyzed = cards.filter { it.stage == Stage.ANALYZED && it.transcriptId != null }
        if (known == null) return emptyList()
        return analyzed.filter { it.transcriptId !in known }
    }

    fun baselineOf(cards: List<SessionCard>): Set<String> =
        cards.filter { it.stage == Stage.ANALYZED }.mapNotNull { it.transcriptId }.toSet()

    /**
     * 通知正文：最狠的那一条。亲证优先——推一条猜想出去，人点开发现没原话支撑，
     * 下次就不点了。一条判断都没有就返回 null，**不推**。
     */
    fun headline(m: Meeting): String? {
        val pick = m.atoms.firstOrNull { it.epistemic == "attested" }
            ?: m.atoms.firstOrNull { it.epistemic == "inferred" }
            ?: return null
        return pick.statement.take(60)
    }

    fun title(m: Meeting): String = "${m.title} · 出了 ${m.atoms.size} 条判断"

    // ── 落地 ──────────────────────────────────────────────────

    fun known(ctx: Context): Set<String>? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("analyzed", null)

    fun remember(ctx: Context, ids: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("analyzed", ids).apply()
    }

    fun post(ctx: Context, transcriptId: String, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "有新判断", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "一场会分析完、读出判断时通知一次"
                }
            )
        }
        val tap = PendingIntent.getActivity(
            ctx, transcriptId.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TRANSCRIPT, transcriptId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap).setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + (transcriptId.hashCode() and 0xFFF), n) }
    }

    class Worker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val client = Session.client(ctx)
            val r = runCatching { client.sessions() }.getOrNull() ?: return Result.retry()
            if (r !is ApiResult.Ok) return Result.success()   // 没登录就算了，不重试
            val cards = r.value
            val known = known(ctx)
            val fresh = newlyAnalyzed(known, cards)
            fresh.forEach { c ->
                val tid = c.transcriptId ?: return@forEach
                val m = (runCatching { client.meeting(tid) }.getOrNull() as? ApiResult.Ok)?.value ?: return@forEach
                val text = headline(m) ?: return@forEach   // 没判断不推
                post(ctx, tid, title(m), text)
            }
            remember(ctx, (known ?: emptySet()) + baselineOf(cards))
            return Result.success()
        }
    }
}
