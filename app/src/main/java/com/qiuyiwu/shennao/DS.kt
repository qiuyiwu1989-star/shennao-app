package com.qiuyiwu.shennao

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 设计系统在安卓这一侧的落地。规格：docs/design/013-移动端设计系统.md。
 *
 * 这个文件是**唯一**允许出现字面 dp、字面控件形制的地方。屏幕代码只准用这里的
 * 节奏档和组件——RhythmGuardTest 钉着裸 dp 的基线，只准降不准涨。
 *
 * 2026-09-06 真机截图审出来的「粗糙」有四个根子，这里逐条对应：
 *   一、层级只靠描边：深底上一圈灰线画出来的是空心框 → DsCard 暗色下靠底色抬一级，不描边
 *   二、按钮没有等级：38 个 TextButton，「兑现了」和「去那场会」长得一样 → 四档按钮
 *   三、状态靠散文：「过期 3 天」「上传中」「录下来 → 送到 → …」都是一行字 → Pill
 *   四、设置页是一堆卡片墙 → DsGroup + DsRow 的列表行
 */
object DS {
    /** 纵向节奏。 */
    object Rhythm {
        /*
         * 疏密：一屏里必须同时有「挤在一起的」和「隔得很开的」，眼睛才分得出组。
         * 4.1.0 到处都是 12，读起来像一份表格；现在紧的更紧（hair 4）、松的更松（section 32）。
         */
        val page = 64.dp      // 页面级留白（列表底部）
        val block = 40.dp     // 区块之间
        val section = 32.dp   // 分区小标之前
        val inner = 20.dp     // 块内
        val element = 14.dp   // 卡片与卡片
        val tight = 8.dp      // 一组里两行之间
        val hair = 4.dp       // 标题与它的副行：紧贴，说明是同一件事
    }

    /** 圆角。控件 12 / 卡片 16 / 浮层 20 / 药丸。中间档一律不用。 */
    object Radius {
        val tiny = RoundedCornerShape(4.dp)
        val control = RoundedCornerShape(12.dp)
        val card = RoundedCornerShape(16.dp)
        val sheet = RoundedCornerShape(20.dp)
        val pill = RoundedCornerShape(980.dp)
    }

    /** 内边距。 */
    object Pad {
        val tight = PaddingValues(16.dp)
        /** 卡片内边距：左右比上下多一点，字才不贴边。 */
        val card = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
        val default = PaddingValues(20.dp)
        val focus = PaddingValues(24.dp)
        /** 页面左右留白。所有页型同一个值——同型必同宽。 */
        val screen = PaddingValues(horizontal = 20.dp)
        val row = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        /** 列表的内边距：左右同页面留白，底部留出一页级空白（手势条 + 悬浮按钮）。 */
        fun list(top: androidx.compose.ui.unit.Dp = Rhythm.element) =
            PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = Rhythm.page)
    }

    /** 尺寸。 */
    object Size {
        val hit = 48.dp        // 安卓命中区下限
        val icon = 20.dp
        val iconLarge = 32.dp
        val recordButton = 88.dp
        val progress = 18.dp
        val hairline = 1.dp
        val rule = 2.dp
        val quoteBar = 3.dp
    }

    /** 动效时长。产品页面不用回弹。 */
    object Motion {
        const val fast = 150
        const val base = 250
        const val slow = 400
    }
}

// ── 表面 ─────────────────────────────────────────────────────

/** 卡片的几种底。 */
enum class CardTone { PLAIN, INSET, ACCENT, RISK, WARN }

/**
 * 卡片。**浅色：白底 + 1px 发丝边；暗色：底色抬一级，不描边。**
 *
 * 不用阴影：一屏十几张带阴影的卡片叠在一起像一堆浮在半空的纸片，
 * 而这一屏要传达的是「这些判断是稳的」。
 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: CardTone = CardTone.PLAIN,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalDark.current
    val bg = when (tone) {
        CardTone.PLAIN -> cs.surface
        CardTone.INSET -> cs.surfaceContainer
        CardTone.ACCENT -> cs.primaryContainer
        CardTone.RISK -> cs.errorContainer
        CardTone.WARN -> Tone.WARN.colors().bg
    }
    val border = if (!dark && tone == CardTone.PLAIN) BorderStroke(1.dp, cs.outlineVariant) else null
    val colors = CardDefaults.cardColors(containerColor = bg)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = DS.Radius.card,
             colors = colors, border = border, elevation = elevation) { content() }
    } else {
        Card(modifier = modifier, shape = DS.Radius.card,
             colors = colors, border = border, elevation = elevation) { content() }
    }
}

/**
 * 一组列表行。行与行之间是缩进的发丝线，整组一张卡。
 * 设置页用它，不用一行一张卡——那是「卡片墙」。
 */
@Composable
fun DsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    DsCard(modifier.fillMaxWidth()) { Column(content = content) }
}

/** 组内的分隔线。左边留出和行内文字对齐的缩进。 */
@Composable
fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/**
 * 列表行：标题 / 副标题 / 右侧值或箭头。
 *
 * `trailing` 是一段文字（版本号、状态词）；`onClick` 非空时右边给箭头。
 * 想放按钮的用 `trailingContent`。
 */
@Composable
fun DsRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val base = Modifier.fillMaxWidth().heightIn(min = DS.Size.hit)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(DS.Pad.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(DS.Rhythm.element)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, color = titleColor)
            if (subtitle != null) {
                Spacer(Modifier.height(DS.Rhythm.hair))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DS.Rhythm.element))
            Text(trailing, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailingContent != null) { Spacer(Modifier.width(DS.Rhythm.element)); trailingContent() }
        if (onClick != null) { Spacer(Modifier.width(DS.Rhythm.tight)); Chevron() }
    }
}

/** 「›」。用图标不用字符——字符在不同字体里粗细不一。 */
@Composable
fun Chevron(color: Color = MaterialTheme.colorScheme.outline) {
    Icon(
        Icons.Outlined.KeyboardArrowRight,
        contentDescription = null, tint = color, modifier = Modifier.size(DS.Size.icon),
    )
}

/** 分区小标：小号、灰、字距拉开、上面留一大段。列表里「还在手机上」这种。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, top: Boolean = true) {
    Text(
        text, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = if (top) DS.Rhythm.section else 0.dp, bottom = DS.Rhythm.tight),
    )
}

/** 分区标题 + 一句说明。详情页的「读出来的判断 / 决定、信号、矛盾」。 */
@Composable
fun SectionHead(title: String, hint: String? = null) {
    Column(Modifier.padding(top = DS.Rhythm.section, bottom = DS.Rhythm.tight)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (hint != null) {
            Spacer(Modifier.height(DS.Rhythm.hair))
            Text(hint, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 卡片底栏：一条发丝线，下面一行动作。
 * 卡片分三区——头（谁、什么时候）、身（读文）、底（能做什么）。
 * 4.1.0 四行等距叠着，像表格；有了这条线，「读」和「做」就分开了。
 */
@Composable
fun CardFooter(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = DS.Rhythm.element)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = DS.Rhythm.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DS.Rhythm.tight),
            content = content,
        )
    }
}

// ── 状态 ─────────────────────────────────────────────────────

/**
 * 药丸：一个词说清状态。「过期 3 天」「上传中」「有原话」「猜想」。
 * 语义靠 [Tone]，但文字必须自己说清——阳光下和色觉障碍面前颜色都不可靠。
 */
@Composable
fun Pill(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val c = tone.colors()
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = c.fg, maxLines = 1,
        modifier = modifier.background(c.bg, DS.Radius.pill).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 一条提示块：底色 + 一段话。「录音被系统停过」「这条为什么没有分析」。 */
@Composable
fun NoticeBox(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier,
              action: (@Composable () -> Unit)? = null) {
    val c = tone.colors()
    Column(modifier.fillMaxWidth().background(c.bg, DS.Radius.control).padding(DS.Pad.tight)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.fg)
        if (action != null) { Spacer(Modifier.height(DS.Rhythm.tight)); action() }
    }
}

// ── 按钮：四档 ────────────────────────────────────────────────
/*
 * 实心（PrimaryButton）   一屏最多一个：开始录、下载新版、还是分析这条
 * 填充（TonalButton）     卡片上的记账动作：兑现了 / 取消了 / 应验了 / 对 / 不对
 * 链接（LinkButton）      去别处看：去那场会 / 回到原话 / 从灵魂卡导入
 * 安静（QuietButton）     低频、不想抢眼的：删掉这条 / 别再看 / 退出登录
 *
 * 判据：**点了会发生什么**。跳走 → 链接；记一笔 → 填充；只有一个且有后果 → 实心。
 */

@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = DS.Size.hit),
        shape = DS.Radius.control,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(DS.Size.progress), strokeWidth = 2.dp,
                                      color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(DS.Rhythm.tight))
        }
        Text(text)
    }
}

@Composable
fun TonalButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 36.dp),
        shape = DS.Radius.control,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(DS.Size.icon - 2.dp))
            Spacer(Modifier.width(DS.Rhythm.tight))
        }
        Text(text)
    }
}

/**
 * 链接式文字按钮：去别处看。用「可点的文字」那个蓝（secondary）。
 */
@Composable
fun LinkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick, modifier = modifier.heightIn(min = DS.Size.hit), contentPadding = contentPadding,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
        content = content,
    )
}

/** 安静按钮：灰字。危险的事不在这里变红——红留给确认框，那才是不可逆的一步。 */
@Composable
fun QuietButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(),
) {
    TextButton(
        onClick = onClick, enabled = enabled, contentPadding = contentPadding,
        modifier = modifier.heightIn(min = DS.Size.hit),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(text) }
}

/** 圆形图标按钮，48dp 命中区。顶栏的返回、分享。 */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true,
               tint: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(DS.Size.hit)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

// ── 选择 ─────────────────────────────────────────────────────

/**
 * 选择 chip：填充底，不描边。选中 = 品牌浅底 + 品牌字。
 * 默认的 FilterChip 是一圈细线——深底上就是一排空心框。
 */
@Composable
fun DsChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.secondaryContainer else cs.surfaceVariant
    val fg = if (selected) cs.onSecondaryContainer else cs.onSurface
    Surface(
        onClick = onClick, shape = DS.Radius.pill, color = bg,
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg,
                 fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

/**
 * 页内 tab。字是 ink 不是蓝，下划线是 ink——蓝只留给可点的文字和实心动作，
 * 一屏三种东西都是蓝的时候，蓝就不再表示任何事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, scrollable: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val indicator: @Composable (List<TabPosition>) -> Unit = { pos ->
        if (selected < pos.size) TabRowDefaults.SecondaryIndicator(
            Modifier.tabIndicatorOffset(pos[selected]), height = 2.dp, color = cs.onSurface,
        )
    }
    val tabs: @Composable () -> Unit = {
        labels.forEachIndexed { i, l ->
            Tab(
                selected = selected == i, onClick = { onSelect(i) },
                selectedContentColor = cs.onSurface, unselectedContentColor = cs.onSurfaceVariant,
                text = {
                    Text(l, style = MaterialTheme.typography.titleSmall,
                         fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Medium)
                },
            )
        }
    }
    val divider: @Composable () -> Unit = { HorizontalDivider(color = cs.outlineVariant) }
    if (scrollable) ScrollableTabRow(
        selectedTabIndex = selected, edgePadding = 20.dp, containerColor = Color.Transparent,
        contentColor = cs.onSurface, indicator = indicator, divider = divider, tabs = tabs,
    ) else TabRow(
        selectedTabIndex = selected, containerColor = Color.Transparent,
        contentColor = cs.onSurface, indicator = indicator, divider = divider, tabs = tabs,
    )
}

// ── 顶栏 ─────────────────────────────────────────────────────

/**
 * 详情页顶栏：返回箭头 + 右侧动作。**返回交还给平台**——箭头，不是「返回」两个字。
 * 标题不放这里，放正文第一行（中文标题常常一行放不下）。
 */
@Composable
fun TopBar(onBack: () -> Unit, actions: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
        Spacer(Modifier.weight(1f))
        actions?.invoke(this)
    }
}

/** 一块圆角的填充区域（输入框底、嵌块）。 */
fun Modifier.inset(color: Color) = this.clip(DS.Radius.control).background(color)
