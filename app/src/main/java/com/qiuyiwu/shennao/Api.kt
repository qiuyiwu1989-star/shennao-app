package com.qiuyiwu.shennao

import org.json.JSONObject

/*
 * 深脑接口的契约层。
 *
 * 刻意做成纯 Kotlin、零安卓依赖：解析和判断都能在 JVM 上单测。
 * Mac 端今天反复吃的亏是「编译过 ≠ 能跑」——逻辑一旦埋进 Activity，
 * 就只能装到手机上才知道对不对。
 *
 * 鉴权走原生 Bearer 通道：Authorization: Bearer <supabase token> +
 * x-deepbrain-org-id。这条通道原本只覆盖录音链路那 12 个接口，
 * /api/mobile 那一族是为手机端新开的面，复用同一套解析。
 *
 * 注：这个文件里的块注释一律不用 markdown 的星号强调——
 * 「星号 + 斜杠」会被 Kotlin 当成注释结束符，整份文件从那里开始错乱，
 * 而报错只说「Unclosed comment」，指向文件末尾，跟真正的位置差了几十行。
 */

/** 一条到期/在途的承诺。字段全是服务端格式化好的——客户端不碰日期计算。 */
data class Commitment(
    val id: String,
    val speakerName: String,
    val statement: String,
    /** 原话，逐字。已过核验闸门 */
    val quote: String,
    val saidDate: String,
    /** 出处，一般是那场会的标题 */
    val context: String?,
    val dueDate: String?,
    /** 负数=还没到；正数=逾期天数；null=待确认 */
    val overdueDays: Int?,
    val status: String,
    /** 往回走的路：从这条承诺回到那场会 */
    val transcriptId: String?,
)

/** 一条新洞察。带着往回走的路。 */
data class Insight(
    val id: String,
    val statement: String,
    val atomType: String,
    /** 原话。空串 = 这条是推断的，没有直接原话支撑 */
    val quote: String,
    /** attested 亲证 / inferred 推断 / conjecture 猜想。
     *  必须显示——手机是一扫而过的场景，一条猜想会被当成事实。 */
    val epistemic: String,
    val subject: String?,
    val transcriptId: String?,
)

/** 一条该给说法的预测。往前走的路。 */
data class Prediction(
    val id: String,
    val statement: String,
    /** 看到什么就算应验/落空。为空说明这条当初没写清怎么算数——那本身是个信号 */
    val observableSignal: String?,
    val subject: String?,
    val dueAt: String?,
    val overdueDays: Int?,
)

data class TodayCounts(val overdue: Int, val total: Int, val awaitingSpeaker: Int)

data class Today(
    val counts: TodayCounts,
    val commitments: List<Commitment>,
    val insights: List<Insight>,
    val predictions: List<Prediction>,
    /** 迁移没跑。必须和「没有承诺」分开——后者用户永远不会主动报告 */
    val notReady: Boolean,
    val failed: Boolean,
)

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    /** 登录失效。界面要引导重新登录，而不是显示「加载失败」 */
    object Unauthorized : ApiResult<Nothing>()
    data class Failed(val message: String) : ApiResult<Nothing>()
}

object TodayParser {
    /**
     * 解析 /api/mobile/today。
     *
     * 缺字段一律给安全默认值，不抛异常：手机上一次解析崩溃就是整屏白，
     * 而服务端多加一个字段是常态。宁可少显示一行，不能整页挂掉。
     */
    fun parse(body: String): Today {
        val o = JSONObject(body)
        val c = o.optJSONObject("counts")
        val list = o.optJSONArray("commitments")
        val rows = buildList {
            for (i in 0 until (list?.length() ?: 0)) {
                val r = list!!.optJSONObject(i) ?: continue
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    Commitment(
                        id = id,
                        speakerName = r.optString("speakerName"),
                        statement = r.optString("statement"),
                        quote = r.optString("quote"),
                        saidDate = r.optString("saidDate"),
                        context = r.optString("context").takeIf { it.isNotBlank() },
                        dueDate = r.optString("dueDate").takeIf { it.isNotBlank() },
                        overdueDays = if (r.isNull("overdueDays")) null else r.optInt("overdueDays"),
                        status = r.optString("status", "open"),
                        transcriptId = r.optString("transcriptId").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        val insights = buildList {
            val a = o.optJSONArray("insights")
            for (i in 0 until (a?.length() ?: 0)) {
                val r = a!!.optJSONObject(i) ?: continue
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(Insight(
                    id = id,
                    statement = r.optString("statement"),
                    atomType = r.optString("atomType"),
                    quote = r.optString("quote"),
                    epistemic = r.optString("epistemic"),
                    subject = r.optString("subject").takeIf { it.isNotBlank() },
                    transcriptId = r.optString("transcriptId").takeIf { it.isNotBlank() },
                ))
            }
        }
        val predictions = buildList {
            val a = o.optJSONArray("predictions")
            for (i in 0 until (a?.length() ?: 0)) {
                val r = a!!.optJSONObject(i) ?: continue
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(Prediction(
                    id = id,
                    statement = r.optString("statement"),
                    observableSignal = r.optString("observableSignal").takeIf { it.isNotBlank() },
                    subject = r.optString("subject").takeIf { it.isNotBlank() },
                    dueAt = r.optString("dueAt").takeIf { it.isNotBlank() },
                    overdueDays = if (r.isNull("overdueDays")) null else r.optInt("overdueDays"),
                ))
            }
        }
        return Today(
            counts = TodayCounts(
                overdue = c?.optInt("overdue") ?: 0,
                total = c?.optInt("total") ?: 0,
                awaitingSpeaker = c?.optInt("awaitingSpeaker") ?: 0,
            ),
            commitments = rows,
            insights = insights,
            predictions = predictions,
            notReady = o.optBoolean("notReady", false),
            failed = o.optBoolean("failed", false),
        )
    }

    /**
     * 该不该推通知，以及推什么。
     *
     * 判据故意只看逾期，不看总数：在途的承诺不该半夜把人吵醒，
     * 而逾期的今天不问、明天就更难开口。返回 null = 别推。
     */
    fun notification(t: Today): String? {
        if (t.notReady || t.failed) return null   // 系统自己有毛病时别去烦用户
        val parts = buildList {
            if (t.counts.overdue > 0) add("${t.counts.overdue} 条承诺过期")
            if (t.predictions.isNotEmpty()) add("${t.predictions.size} 条预测该给说法")
        }
        // 洞察不进通知：它没有时间压力，攒着慢慢看就行。
        // 什么都往通知里塞，用户很快就会把通知关掉——那时真正要紧的也送不到了。
        return parts.takeIf { it.isNotEmpty() }?.joinToString("，")
    }
}
