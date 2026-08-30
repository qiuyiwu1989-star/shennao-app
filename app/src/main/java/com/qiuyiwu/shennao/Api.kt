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

data class TodayCounts(val overdue: Int, val total: Int, val awaitingSpeaker: Int)

data class Today(
    val counts: TodayCounts,
    val commitments: List<Commitment>,
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
        return Today(
            counts = TodayCounts(
                overdue = c?.optInt("overdue") ?: 0,
                total = c?.optInt("total") ?: 0,
                awaitingSpeaker = c?.optInt("awaitingSpeaker") ?: 0,
            ),
            commitments = rows,
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
    fun notification(t: Today): String? = when {
        t.notReady || t.failed -> null      // 系统自己有毛病时别去烦用户
        t.counts.overdue <= 0 -> null
        else -> "${t.counts.overdue} 条承诺过期了"
    }
}
