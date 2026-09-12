package com.qiuyiwu.shennao

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import java.util.Calendar
import java.util.TimeZone

/*
 * 记录页的时间线形态（V5 第 3 轮，spec 013 §3.2 / 3.3）。
 *
 * 邱 09-11 说记录页参考妙记：妙记默认就是按时间找的单列。双栏内容卡适合「浏览」，
 * 但压低了标题、日期、时长的识别效率——找「上周三那场」要一张张认。所以默认时间线单列，
 * 内容卡作为可切换的另一种看法；顶上一条周带，哪天录过一眼看见，点一天只看那天（「录完放到日历里」）。
 */

/** 本周七天。周一起。纯逻辑，JVM 可测。 */
object Week {
    fun dayStarts(nowMs: Long, zone: TimeZone): List<Long> {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            // 退到周一：Calendar 的 SUNDAY=1 … SATURDAY=7
            val back = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_YEAR, -back)
        }
        return (0 until 7).map { i -> (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }.timeInMillis }
    }

    /** 一天的键：yyyy-MM-dd，按给定时区。 */
    fun key(ms: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 服务端的 ISO 时刻（UTC）落在本地的哪一天。解不出就 null，不猜。 */
    fun keyOfIso(iso: String?, zone: TimeZone): String? {
        if (iso == null) return null
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val d = runCatching { f.parse(iso.take(19)) }.getOrNull() ?: return null
        return key(d.time, zone)
    }

    fun dayOfMonth(ms: Long, zone: TimeZone): Int = Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)
    private val names = listOf("一", "二", "三", "四", "五", "六", "日")
    fun weekdayLabel(index: Int): String = names[index]
}

/** 周带：七个圆，有录音的下面一个点，选中的填色。再点一次取消。 */
@Composable
fun WeekStrip(
    days: List<Long>, marked: Set<String>, selected: String?, zone: TimeZone, todayKey: String,
    onSelect: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, ms ->
            val k = Week.key(ms, zone)
            val on = k == selected
            val has = k in marked
            val isToday = k == todayKey
            Column(
                Modifier.clickable { onSelect(if (on) null else k) }.padding(vertical = DS.Rhythm.hair),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Week.weekdayLabel(i), style = MaterialTheme.typography.labelMedium,
                     color = if (isToday) cs.primary else cs.onSurfaceVariant)
                Spacer(Modifier.height(DS.Rhythm.hair))
                Box(
                    Modifier.size(DS.Size.iconLarge).background(if (on) cs.primary else cs.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(Week.dayOfMonth(ms, zone).toString(), style = MaterialTheme.typography.labelMedium,
                         color = if (on) cs.onPrimary else if (has) cs.onSurface else cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(DS.Rhythm.hair))
                Box(Modifier.size(DS.Rhythm.hair).background(if (has) cs.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
            }
        }
    }
}

/** 记录页两种看法。记在本机，下次打开还是它。 */
enum class RecordsView { TIMELINE, CARDS }
object RecordsViewPref {
    private const val PREFS = "shennao-ui"
    fun load(ctx: android.content.Context): RecordsView =
        if (ctx.getSharedPreferences(PREFS, 0).getString("records_view", null) == "cards") RecordsView.CARDS else RecordsView.TIMELINE
    fun save(ctx: android.content.Context, v: RecordsView) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("records_view", if (v == RecordsView.CARDS) "cards" else "timeline").apply()
    }
}

/** 时间线的一行：标题、时间 · 时长 · 来源、站点药丸、一两行摘要。 */
@Composable
fun TimelineRow(s: SessionCard, whenLabel: String?, onOpen: (String) -> Unit) {
    val open: (() -> Unit)? = s.transcriptId?.let { id -> { onOpen(id) } }
    Column(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClick = open) else Modifier)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val title = SessionTitles.display(s.title, whenLabel)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 标题本身就是「录音 · 时间」的话，元信息里不再重复那个时间
                val timeInTitle = title != s.title
                val meta = listOfNotNull(whenLabel.takeIf { !timeInTitle }, s.durationMs?.let { minutesLabel(it) }, SourceFilter.label(s.source)).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(DS.Rhythm.hair))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 太短没分析的不叫「分析中」（V5 1.6 的判据）
            (if (skippedShort(s) != null) "没分析" to Tone.NEUTRAL else stagePill(s.stage))
                ?.let { (label, tone) -> Spacer(Modifier.width(DS.Rhythm.element)); Pill(label, tone) }
        }
        val summary = s.highlight?.text?.takeIf { it.isNotBlank() }
        if (summary != null) {
            Spacer(Modifier.height(DS.Rhythm.tight))
            Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(DS.Rhythm.element))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 今天页顶上那一句「最近一次录音去哪了」。纯逻辑，JVM 可测。 */
object LatestLine {
    fun of(local: List<LocalSession>, served: List<SessionCard>): String? {
        val busy = local.firstOrNull { it.recording > 0 || it.done < it.total }
        if (busy != null) {
            val title = SessionTitles.display(busy.meta.title, stamp(busy.meta.startedAtEpochMs))
            return if (busy.recording > 0) "$title · 正在录" else "$title · 还在手机上，送到了 ${busy.done}/${busy.total} 段"
        }
        val s = served.maxByOrNull { it.startedAt ?: "" } ?: return null
        val title = SessionTitles.display(s.title, s.startedAt?.let(::dayOf))
        val where = when (s.stage) {
            Stage.RECORDED -> "等转写"
            Stage.DELIVERED -> "转写中"
            Stage.TRANSCRIBED -> if (skippedShort(s) != null) "没分析（太短）" else "分析中"
            Stage.ANALYZED -> "分析完了"
            Stage.FAILED -> "失败了"
            Stage.UNKNOWN -> "送到了"
        }
        return "$title · $where"
    }
    private fun dayOf(iso: String): String = runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(f.parse(iso.take(19))!!)
    }.getOrElse { iso.take(10) }
}
