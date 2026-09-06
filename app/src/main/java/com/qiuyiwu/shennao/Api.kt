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
    /** 对得上人物档案才有；有了名字就能点进人物页（012 P1-1） */
    val personId: String? = null,
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
/** 哪几场会里还挂着没认的说话人（012 P3-5） */
data class AwaitingTranscript(val transcriptId: String, val title: String, val count: Int)

data class Today(
    val counts: TodayCounts,
    val commitments: List<Commitment>,
    val insights: List<Insight>,
    val predictions: List<Prediction>,
    /** 迁移没跑。必须和「没有承诺」分开——后者用户永远不会主动报告 */
    val notReady: Boolean,
    val failed: Boolean,
    val awaitingSpeakerTranscripts: List<AwaitingTranscript> = emptyList(),
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
                        personId = r.optString("personId").takeIf { it.isNotBlank() && it != "null" },
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
            awaitingSpeakerTranscripts = buildList {
                val a = o.optJSONArray("awaitingSpeakerTranscripts")
                for (i in 0 until (a?.length() ?: 0)) {
                    val r = a!!.optJSONObject(i) ?: continue
                    val id = r.optString("transcriptId").takeIf { it.isNotBlank() } ?: continue
                    add(AwaitingTranscript(id, r.optString("title").ifBlank { "未命名" }, r.optInt("count")))
                }
            },
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

/**
 * 列表里怎么称呼一场会。纯逻辑，JVM 可测。
 *
 * 服务端有时把文件名当标题（`note20260829-140354`）、有时是默认的「手机录音」——
 * 一列这种东西读不出「这是哪场」。有时间就用「录音 · 8月29日 14:03」，
 * 至少能按时间找。真标题（分析起的名）照用。
 */
object SessionTitles {
    private val fileLike = Regex("""^(?:note|rec|REC|record|audio)?[-_ ]?\d{8}[-_T]?\d{4,6}(?:\.\w+)?$""")
    private val generic = setOf("", "手机录音", "录音", "灵魂卡录音", "Untitled", "untitled")

    fun display(title: String, whenLabel: String?): String {
        val t = title.trim()
        val bad = t in generic || fileLike.matches(t)
        return if (bad) (if (whenLabel != null) "录音 · $whenLabel" else "录音") else t
    }
}

/**
 * 卡住的原因给人看之前去掉内部前缀。服务端偶尔漏翻：「reaper 收尾：这场录音没有留下任何音频或转写」
 * ——「reaper」是后台任务的名字，用户不该看见。
 */
object Problems {
    private val internalPrefix = Regex("""^[A-Za-z][\w-]*\s*(收尾|清理|reaper)[:：]\s*""")
    fun humanize(problem: String): String = problem.trim().replace(internalPrefix, "").ifBlank { problem.trim() }
}

data class SessionCard(
    val sessionId: String,
    val title: String,
    val startedAt: String?,
    val durationMs: Long?,
    val stage: Stage,
    /** 卡住的原因。只在 FAILED 时有，且服务端保证是人话 */
    val problem: String?,
    val transcriptId: String?,
    /** 声音从哪来：web / macos / android / iot / upload。服务端没升级时是 null，界面就不分段 */
    val captureClient: String? = null,
    /** 入口：card / share / phone / other。由服务端从幂等键前缀派生；没有就 null，界面不分段 */
    val source: String? = null,
)

data class MeetingAtom(
    val id: String, val statement: String, val atomType: String,
    val quote: String, val epistemic: String, val subject: String?,
)

/** 一次分析的产物。一场分析常常是好几个方法合出来的。 */
data class MeetingAnalysis(
    val markdown: String?,
    val methods: List<String>,
    val routingReason: String?,
    val status: String,
)

/** 逐句原话。服务端没升级时列表为空，「原话」tab 退回只列被引用的片段。 */
data class Line(val startMs: Long?, val endMs: Long?, val speaker: String?, val text: String)

data class Meeting(
    val transcriptId: String,
    val title: String,
    /** 没有就是分析还没跑完。界面要说清楚，不是显示一片空白 */
    val summary: String?,
    val durationSec: Int?,
    val speakers: List<String>,
    val atoms: List<MeetingAtom>,
    val commitments: List<Commitment>,
    /** null = 没有分析。这时候看 [analysisAbsentReason] */
    val analysis: MeetingAnalysis?,
    /**
     * 没有分析时，为什么没有。
     *
     * 理由由服务端给，客户端不自己拿时长去和阈值比——那条规矩（不到 5 分钟
     * 不自动分析）只有服务端知道，抄一份到手机上，下次改阈值必然只改一处。
     */
    val analysisAbsentReason: String?,
    val segments: List<Line> = emptyList(),
    /** 在场的人里能对到人物档案的：名字 → 人物 id */
    val people: Map<String, String> = emptyMap(),
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

    /**
     * 根节点不对就抛，让 Client.get 变成 Failed。
     * 以前任何异常都回空列表，「记录」页会显示「还没有录过」——把「取不到」画成了「没有内容」（012 P0-9）。
     */
    fun parse(body: String): List<SessionCard> = run {
        val arr = JSONObject(body).optJSONArray("sessions") ?: throw IllegalStateException("应答里没有 sessions")
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
                captureClient = o.optString("captureClient").takeIf { it.isNotBlank() && it != "null" },
                source = o.optString("source").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    fun parseMeeting(body: String): Meeting? = runCatching {
        val o = JSONObject(body)
        val tid = o.optString("transcriptId").takeIf { it.isNotBlank() } ?: return null
        val sp = o.optJSONArray("speakers")
        val at = o.optJSONArray("atoms")
        val cm = o.optJSONArray("commitments")
        val sg = o.optJSONArray("segments")
        Meeting(
            segments = (0 until (sg?.length() ?: 0)).mapNotNull { i ->
                val l = sg!!.optJSONObject(i) ?: return@mapNotNull null
                val text = l.optString("text"); if (text.isBlank()) return@mapNotNull null
                Line(
                    startMs = if (l.isNull("startMs")) null else l.optLong("startMs"),
                    endMs = if (l.isNull("endMs")) null else l.optLong("endMs"),
                    speaker = l.optString("speaker").takeIf { it.isNotBlank() && it != "null" },
                    text = text,
                )
            },
            people = buildMap {
                val pp = o.optJSONArray("people")
                for (i in 0 until (pp?.length() ?: 0)) {
                    val r = pp!!.optJSONObject(i) ?: continue
                    val n = r.optString("name"); val id = r.optString("personId")
                    if (n.isNotBlank() && id.isNotBlank()) put(n, id)
                }
            },
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
            analysis = o.optJSONObject("analysis")?.let { a ->
                val ms = a.optJSONArray("methods")
                MeetingAnalysis(
                    markdown = a.optString("markdown").takeIf { it.isNotBlank() && it != "null" },
                    methods = (0 until (ms?.length() ?: 0)).mapNotNull {
                        ms!!.optString(it).takeIf { m -> m.isNotBlank() }
                    },
                    routingReason = a.optString("routingReason").takeIf { it.isNotBlank() && it != "null" },
                    status = a.optString("status"),
                )
            },
            analysisAbsentReason = o.optString("analysisAbsentReason")
                .takeIf { it.isNotBlank() && it != "null" },
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


// ---------------------------------------------------------------------------
// 人物：他说过什么、兑现了多少
//
// 用户说的「路」在这一层最实：一条孤立的承诺没有分量，
// 「他第三次这么说了」才有。

data class Person(
    val id: String,
    val name: String,
    val role: String?,
    val kept: Int,
    val broken: Int,
    val open: Int,
    /** null = 还没有任何一条有结论。给 0% 或 100% 都是拿数字撒谎 */
    val keptRate: Int?,
    val judgments: List<Insight>,
    val openCommitments: List<Commitment>,
)

object PersonParser {
    fun parse(body: String): Person? = runCatching {
        val o = JSONObject(body)
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val js = o.optJSONArray("judgments")
        val cs = o.optJSONArray("openCommitments")
        Person(
            id = id,
            name = o.optString("name").ifBlank { "未命名" },
            role = o.optString("role").takeIf { it.isNotBlank() && it != "null" },
            kept = o.optInt("kept"), broken = o.optInt("broken"), open = o.optInt("open"),
            keptRate = if (o.isNull("keptRate")) null else o.optInt("keptRate"),
            judgments = (0 until (js?.length() ?: 0)).mapNotNull { i ->
                val a = js!!.optJSONObject(i) ?: return@mapNotNull null
                Insight(
                    id = a.optString("id"), statement = a.optString("statement"),
                    atomType = "", quote = "", epistemic = a.optString("epistemic"),
                    subject = null,
                    transcriptId = a.optString("transcriptId").takeIf { it.isNotBlank() && it != "null" },
                )
            },
            openCommitments = (0 until (cs?.length() ?: 0)).mapNotNull { i ->
                val a = cs!!.optJSONObject(i) ?: return@mapNotNull null
                Commitment(
                    id = a.optString("id"), speakerName = o.optString("name"),
                    statement = a.optString("quote"), quote = a.optString("quote"),
                    saidDate = "", context = null,
                    dueDate = a.optString("dueDate").takeIf { it.isNotBlank() && it != "null" },
                    overdueDays = null, status = "open",
                    transcriptId = a.optString("transcriptId").takeIf { it.isNotBlank() && it != "null" },
                )
            },
        )
    }.getOrNull()
}


// ---------------------------------------------------------------------------
// 搜索：你问它
//
// 前面几层都是「它推给你」。第二大脑真正随身，是在你想不起来的那一刻
// 它就在口袋里——而那一刻往往发生在会议室里、路上，不在电脑前。

data class Hit(
    val kind: String,            // judgment / commitment / meeting
    val id: String,
    val text: String,
    val who: String?,
    val transcriptId: String?,
)

object SearchParser {
    fun parse(body: String): List<Hit> = run {
        val arr = JSONObject(body).optJSONArray("hits") ?: throw IllegalStateException("应答里没有 hits")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text")
            if (text.isBlank()) return@mapNotNull null   // 空文本的命中显示出来是一行空白
            Hit(
                kind = o.optString("kind"), id = o.optString("id"), text = text,
                who = o.optString("who").takeIf { it.isNotBlank() && it != "null" },
                transcriptId = o.optString("transcriptId").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/** 认人页：还没认的说话人，各配一句样本；加候选名单。 */
data class SpeakerRow(
    val id: String,
    val label: String,
    val inferredIdentity: String?,
    val confirmed: Boolean,
    val sampleText: String?,
    val sampleStartMs: Long?,
)
data class Candidate(val name: String, val role: String?, val source: String)
data class SpeakersPage(val speakers: List<SpeakerRow>, val candidates: List<Candidate>)

/**
 * 我名下的一张灵魂卡（服务端 iot_devices 里 provider=soulcard 的那行）。
 * monthly = 这张卡每月发多少积分（永久、随卡不随人）；granted = 这次调用刚补发了多少（跨月第一次打开才不是 0）。
 */
data class BoundCard(val deviceNo: String, val boundAt: String, val granted: Int, val monthly: Int)
data class CardsPage(val cards: List<BoundCard>, val monthly: Int)

/** 积分：余额 + 这个月用了多少。规格 010：显示「已用」不显示「剩余」，month 是差异化所在；老服务端没有就 null。 */
data class MonthUsage(val deep: Int, val quick: Int, val credits: Int)
data class Credits(val balance: Int, val month: MonthUsage?)

object CreditsParser {
    fun parse(json: String): Credits {
        val o = JSONObject(json)
        val m = o.optJSONObject("month")
        return Credits(o.optInt("balance", -1), m?.let { MonthUsage(it.optInt("deep"), it.optInt("quick"), it.optInt("credits")) })
    }
    /** 「我的」页那一行。纯逻辑，可测。 */
    fun usageLine(m: MonthUsage?): String? = m?.let {
        when {
            it.deep == 0 && it.quick == 0 -> "这个月还没做过判断"
            else -> "这个月：深判断 ${it.deep} 次 · 快判断 ${it.quick} 次 · 用了 ${it.credits} 积分"
        }
    }
}

object CardsParser {
    fun parse(json: String): CardsPage {
        val o = JSONObject(json)
        val arr = o.optJSONArray("cards") ?: org.json.JSONArray()
        return CardsPage(List(arr.length()) { card(arr.getJSONObject(it)) }, o.optInt("monthly", 0))
    }
    fun card(c: JSONObject) = BoundCard(c.optString("deviceNo"), c.optString("boundAt"), c.optInt("granted", 0), c.optInt("monthly", 0))
}

object SpeakersParser {
    fun parse(body: String): SpeakersPage = runCatching {
        val o = JSONObject(body)
        val sp = o.optJSONArray("speakers"); val cd = o.optJSONArray("candidates")
        SpeakersPage(
            speakers = (0 until (sp?.length() ?: 0)).mapNotNull { i ->
                val r = sp!!.optJSONObject(i) ?: return@mapNotNull null
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sm = r.optJSONObject("sample")
                SpeakerRow(
                    id = id, label = r.optString("label"),
                    inferredIdentity = r.optString("inferredIdentity").takeIf { it.isNotBlank() && it != "null" },
                    confirmed = r.optBoolean("confirmed", false),
                    sampleText = sm?.optString("text")?.takeIf { it.isNotBlank() },
                    sampleStartMs = sm?.let { if (it.isNull("startMs")) null else it.optLong("startMs") },
                )
            },
            candidates = (0 until (cd?.length() ?: 0)).mapNotNull { i ->
                val c = cd!!.optJSONObject(i) ?: return@mapNotNull null
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Candidate(name, c.optString("role").takeIf { it.isNotBlank() && it != "null" }, c.optString("source"))
            },
        )
    }.getOrElse { SpeakersPage(emptyList(), emptyList()) }
}
