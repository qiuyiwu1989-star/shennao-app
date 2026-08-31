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


// ---------------------------------------------------------------------------
// 「我录过的会走到哪了」
//
// 这一层存在的理由：在此之前手机是只写不读的。录完就断了——一条卡住的录音
// 只在服务器日志里有痕迹，而用户看到的是「我录完了」。

/** 链路四站。名字按用户能理解的说法取，不用服务端的内部状态名。 */
enum class Stage { RECORDED, DELIVERED, TRANSCRIBED, ANALYZED, FAILED, UNKNOWN }

data class SessionCard(
    val sessionId: String,
    val title: String,
    val startedAt: String?,
    val durationMs: Long?,
    val stage: Stage,
    /** 卡住的原因。只在 FAILED 时有，且服务端保证是人话 */
    val problem: String?,
    val transcriptId: String?,
)

data class MeetingAtom(
    val id: String, val statement: String, val atomType: String,
    val quote: String, val epistemic: String, val subject: String?,
)

data class Meeting(
    val transcriptId: String,
    val title: String,
    /** 没有就是分析还没跑完。界面要说清楚，不是显示一片空白 */
    val summary: String?,
    val durationSec: Int?,
    val speakers: List<String>,
    val atoms: List<MeetingAtom>,
    val commitments: List<Commitment>,
)

object SessionsParser {
    /** 认不出的状态归 UNKNOWN，不猜。猜错会让「还在传」显示成「已送到」。 */
    private fun stageOf(s: String) = when (s) {
        "recorded" -> Stage.RECORDED
        "delivered" -> Stage.DELIVERED
        "transcribed" -> Stage.TRANSCRIBED
        "analyzed" -> Stage.ANALYZED
        "failed" -> Stage.FAILED
        else -> Stage.UNKNOWN
    }

    fun parse(body: String): List<SessionCard> = runCatching {
        val arr = JSONObject(body).optJSONArray("sessions") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("sessionId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SessionCard(
                sessionId = id,
                title = o.optString("title").ifBlank { "未命名录音" },
                startedAt = o.optString("startedAt").takeIf { it.isNotBlank() && it != "null" },
                durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs"),
                stage = stageOf(o.optString("stage")),
                problem = o.optString("problem").takeIf { it.isNotBlank() && it != "null" },
                transcriptId = o.optString("transcriptId").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.getOrElse { emptyList() }

    fun parseMeeting(body: String): Meeting? = runCatching {
        val o = JSONObject(body)
        val tid = o.optString("transcriptId").takeIf { it.isNotBlank() } ?: return null
        val sp = o.optJSONArray("speakers")
        val at = o.optJSONArray("atoms")
        val cm = o.optJSONArray("commitments")
        Meeting(
            transcriptId = tid,
            title = o.optString("title").ifBlank { "未命名" },
            summary = o.optString("summary").takeIf { it.isNotBlank() && it != "null" },
            durationSec = if (o.isNull("durationSec")) null else o.optInt("durationSec"),
            speakers = (0 until (sp?.length() ?: 0)).mapNotNull { sp!!.optString(it).takeIf { s -> s.isNotBlank() } },
            atoms = (0 until (at?.length() ?: 0)).mapNotNull { i ->
                val a = at!!.optJSONObject(i) ?: return@mapNotNull null
                MeetingAtom(
                    a.optString("id"), a.optString("statement"), a.optString("atomType"),
                    a.optString("quote"), a.optString("epistemic"),
                    a.optString("subject").takeIf { it.isNotBlank() && it != "null" },
                )
            },
            commitments = (0 until (cm?.length() ?: 0)).mapNotNull { i ->
                val c = cm!!.optJSONObject(i) ?: return@mapNotNull null
                Commitment(
                    id = c.optString("id"),
                    speakerName = c.optString("speaker").ifBlank { "未指明" },
                    statement = c.optString("quote"),
                    quote = c.optString("quote"),
                    saidDate = "",
                    context = null,
                    dueDate = c.optString("dueDate").takeIf { it.isNotBlank() && it != "null" },
                    overdueDays = null,
                    status = "open",
                    transcriptId = tid,
                )
            },
        )
    }.getOrNull()
}
