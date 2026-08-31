package com.qiuyiwu.shennao.record

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/*
 * 把没传完的段推上去——在 App 已经不在了之后。
 *
 * 为什么前台服务不够：前台服务活不过「用户从最近任务里把 App 划走」，
 * 也活不过系统在内存紧张时的清理。而那时没传完的段只躺在手机上，
 * 用户看到的是「录完了」，深脑那边什么都没有。
 *
 * WorkManager 是安卓唯一承认的「进程没了也要做完」的通道：它把任务写进自己的
 * 数据库，进程重启、甚至设备重启之后照样接着跑。
 *
 * 约束只加一条「有网」。不加充电/空闲之类的条件——录音是用户明确交代过的事，
 * 让它等到半夜充电才上传，等于把「录完自动进深脑」这句话作废。
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val pending = try {
            Resume.kick(applicationContext)
            Resume.pending(applicationContext)
        } catch (e: Exception) {
            // 抛出来当可重试处理。WorkManager 会按退避再来一次，
            // 而失败返回 Result.failure() 是永久放弃——那会把录音留在手机上没人管。
            return Result.retry()
        }
        // 还有剩就让 WorkManager 按退避再来。不自己循环重试：
        // 自己转圈会在没网时一直占着唤醒锁，而系统比我们更清楚什么时候该重试。
        return if (pending > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE = "shennao-upload"

        /**
         * 催一次。已经排着的就不重复排——KEEP 而不是 REPLACE：
         * REPLACE 会把正在跑的那次取消掉，而封段是每分钟一次，
         * 等于永远在取消、永远传不完。
         */
        fun kick(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(ctx)
                    .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
            }
        }
    }
}
