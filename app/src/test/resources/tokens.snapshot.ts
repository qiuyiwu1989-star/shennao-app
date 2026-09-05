// ⚠️ 快照，不是真源。
// 真源：qiuyiwu1989-star/shennao 的 packages/ui/src/tokens.ts
// 摘自提交：97a4ae426930160a56111a58a59e4f53b79ec18a
// 摘录于：2026-09-05
//
// 谁改这个文件都要同时说明真源那边也改了。ThemeParityTest 里有一条
// 「快照与真源一致」的测试——只要本机能同时看到两个仓库，它就会跑。
// 看不到主仓库时那条测试跳过，其余三条仍然拿这份快照当基准。

/**
 * 深脑设计 token —— 唯一真源。见 docs/DESIGN-SYSTEM.md
 *
 * 为什么在这里而不是 tailwind.config：token 有两类消费者。
 *   DOM  → Tailwind class（tailwind.config.ts 从本文件 import）
 *   图表 → hex 字符串（Canvas / SVG 拿不到 class，KnowledgeGraph 一类组件直接 import）
 * 只出 class 不出 hex，图表就只能自己硬编码一份——那正是原子类型色一度散落六处的根因。
 *
 * 每组语义色四档：
 *   surface 浅底 / border 描边 / text 文字（对比度已过 AA）/ solid 实心底（配白字）
 */

/**
 * 中性色。冷调灰阶，深脑的默认色就是它。
 *
 * **边框分两种，别混。** WCAG 1.4.11 只对「识别控件所必需的视觉信息」要 3:1：
 *   装饰性分隔（卡片描边、列表分隔线）—— 没有对比度要求，用 100 / 200。
 *   控件边界（输入框、次按钮、勾选框）—— 必须 ≥3:1，用 **300 起步**。
 * 200 对白只有 1.44。它当卡片描边没问题，当输入框边框就是 AA 不合格 ——
 * 输入框的边框是唯一告诉人「这里能打字」的东西，糊掉就没有别的线索了。
 * 300 为此从 #9096a4 压到 #898f9d（深 7/255，肉眼看不出，2.96 → 3.24）。
 */
export const ink = {
  50: '#f6f7f9',
  100: '#e8eaef',
  200: '#d3d7e0',
  300: '#898f9d',  // 控件边界档：对白 3.24 / 对页底 3.02，双双过 1.4.11 的 3:1（原 #9096a4 只有 2.96）
  400: '#646b7d',
  500: '#545b6b',
  600: '#3f4654',
  700: '#2a2f3a',
  800: '#1c1c1e',
  900: '#0d1117',
} as const

/** 品牌色。focus 用于链接与小字（白底 6.54:1）；bright 只用于焦点环、辉光与大字（4.02:1）。 */
export const brand = {
  focus: '#0052d9',
  focusBright: '#007aff',
  focusSoft: '#e8f0ff',
  iris: '#6725ff',
  irisSoft: '#f0eaff',
  /**
   * 渐变亮端。只用于营销页深色区块上的渐变与描边字 —— 那里的底是深色，
   * 亮端才拉得开；放到白底上这两个值都不到 2:1，别拿它们写正文。
   * 立成 token 是因为首页原先把这两个值硬写在四处 `linear-gradient(...)` 里，
   * 而 style 属性里的 hex 是 Tailwind 管不到的地方 —— 越是管不到越要给名字。
   */
  focusLight: '#5ac8fa',
  irisLight: '#a78bfa',
} as const

/** 表面色。页面底与卡片底 —— 算对比度时的「底」就是它们，不该在调用处写死 #ffffff。 */
export const surface = { page: ink[50], card: '#ffffff' } as const

/**
 * 语义色。表达「发生了什么」。
 * 形制约定：状态标签不带边框，以便与带边框的类型标签区分（见 atom）。
 *
 * 八个名字、六个值 —— 名字按角色取，值可以重合（text 与 solidStrong 同色是有意的）。
 * 之所以不压到四档：hover 需要比静态态深一档，压成四档会让 hover 变成空操作，
 * 界面会丢掉「这里可点」的反馈。角色齐全比档位少更重要。
 *   surface  浅底        surfaceStrong  浅底的 hover / 稍深的底（图标容器、计数徽章）
 *   border   描边        borderStrong   描边的 hover
 *   text     文字        textStrong     强调文字
 *   solid    实心底(配白字)  solidStrong  实心底的 hover
 *
 * solid 的定义是「配白字仍过 AA 的最浅一档」，所以四组取的 Tailwind 档位并不相同
 * （risk/info 取 600 就够，ok/warn 必须取 700）。按角色定而不是按数字对齐 —— 数字对齐好看，
 * 但会让绿底白字停在 3.77:1。之前站里的 bg-emerald-500 + 白字只有 2.5:1。
 */
export const state = {
  ok:   { surface: '#ecfdf5', surfaceStrong: '#d1fae5', border: '#a7f3d0', borderStrong: '#6ee7b7', text: '#047857', textStrong: '#065f46', solid: '#047857', solidStrong: '#065f46' },
  warn: { surface: '#fffbeb', surfaceStrong: '#fef3c7', border: '#fde68a', borderStrong: '#fcd34d', text: '#b45309', textStrong: '#92400e', solid: '#b45309', solidStrong: '#92400e' },
  risk: { surface: '#fef2f2', surfaceStrong: '#fee2e2', border: '#fecaca', borderStrong: '#fca5a5', text: '#b91c1c', textStrong: '#991b1b', solid: '#dc2626', solidStrong: '#b91c1c' },
  info: { surface: '#eff6ff', surfaceStrong: '#dbeafe', border: '#bfdbfe', borderStrong: '#93c5fd', text: '#1d4ed8', textStrong: '#1e40af', solid: '#2563eb', solidStrong: '#1d4ed8' },
  /**
   * 第五档「升温」（2026-09-01 立）。表达**有动静**，不表达好坏。
   *
   * 它是被驾驶舱逼出来的，不是设计上想要多一个色：那一页要同时说
   * 「这条是新的」和「这条在变热」，而「新」已经占了 info 蓝，就在隔壁两个元素。
   * ok/warn/risk 都自带好坏判断，升温没有 —— 一个话题热起来可能是好事也可能是坏事。
   * 四档里没有一个能借。
   *
   * **前提条件（借来的，得还）**：viz.series 第 3 槽也是青（#0d9488），
   * 按「状态色是保留色」的规矩这本该冲突。之所以现在不冲突，是因为
   * **升温只出现在驾驶舱，series 只出现在知识词云与 Sparkline，二者从不同屏**。
   * 哪天要在同一个面里同时用，就得先把 series 重排 —— 注意八槽色板过不了
   * all-pairs（换槽 3 为紫会让紫撞紫 ΔE 3.9，比现在还差），重排的正解是砍到 graph4。
   */
  rising: { surface: '#f0fdfa', surfaceStrong: '#ccfbf1', border: '#99f6e4', borderStrong: '#5eead4', text: '#0f766e', textStrong: '#115e59', solid: '#0f766e', solidStrong: '#115e59' },
} as const

/**
 * 领域色 —— 七种判断原子类型。表达「这是什么」。
 * 与 state 共用色相，靠形制区分：类型标签必带同色边框，状态标签必不带。
 */
export const atom = {
  decision:      { surface: '#eff6ff', border: '#bfdbfe', text: '#1d4ed8', solid: '#2563eb' },
  judgment:      { surface: '#eef2ff', border: '#c7d2fe', text: '#4338ca', solid: '#4f46e5' },
  signal:        { surface: '#faf5ff', border: '#e9d5ff', text: '#7e22ce', solid: '#9333ea' },
  contradiction: { surface: '#fffbeb', border: '#fde68a', text: '#b45309', solid: '#d97706' },
  principle:     { surface: '#ecfdf5', border: '#a7f3d0', text: '#047857', solid: '#059669' },
  fact:          { surface: '#f6f7f9', border: '#d3d7e0', text: '#3f4654', solid: '#646b7d' },
  question:      { surface: '#fff7ed', border: '#fed7aa', text: '#c2410c', solid: '#ea580c' },
} as const

export type AtomToken = keyof typeof atom
export type StateToken = keyof typeof state


/**
 * 可视化色板。见 docs/DESIGN-SYSTEM.md §4.7
 *
 * 这两组不是挑出来的，是算出来的 —— 用 dataviz 校验器跑过色盲分离度、正常视力分离度、
 * 彩度下限与对比度。改动必须重跑校验，通不过不许上。
 *
 * series 用于折线/柱状/堆叠这类「相邻比较」的图形：按槽位顺序取，永不循环，
 *        第 9 个系列折进「其他」或改小倍数，不生成新色。
 *        实测最差相邻：色盲 ΔE 13.6 / 正常 16.8（底线 8 / 15）。
 * graph  用于图谱/散点/气泡这类「任意两色都可能相邻」的图形：上限四槽。
 *        八槽在这里全线崩，不是保守，是算出来的。
 *        实测最差全对：色盲 ΔE 10.3 / 正常 19.6。
 *
 * 刻意不含红与琥珀 —— 那是状态色（危险/待办），状态色是保留色，永不充当第 N 个系列。
 */
export const viz = {
  /** 分类色板，固定顺序 */
  series: ['#1d4ed8', '#ea580c', '#0d9488', '#b45309', '#0891b2', '#15803d', '#7e22ce', '#db2777'],
  /**
   * 分类色板的「配白字」档，六槽。用于色块里直接压白字的场合（驾驶舱的主题气泡）。
   *
   * 为什么不能直接用 series：series 是**画在白底上**校验的（对底 ≥3:1），
   * 它的橙 3.56、青 3.68、teal 3.74 —— 压白字全部不到 4.5。
   * 这跟 state.solid 是同一个道理：角色不同，档位就不同，不是数字对齐的事。
   *
   * 六槽都过白字 AA（最低 #db2777 的 4.60）。刻意不含青绿 ——
   * 那一档已经归 state.rising，而升温和主题气泡就在驾驶舱同一屏。
   *
   * **相邻 CVD 最差 7.0（deutan），落在 6–8 的地板区**，按规矩只有配了第二重编码才合法。
   * 这里配齐了两重：气泡里印着数字，气泡下面就是主题名 —— 识别从不依赖颜色。
   * 哪天要把它用到没有标签的图形上（纯色块图例、堆叠条），这条前提就不成立了，
   * 得先重排色板再用。
   *
   * 原先这套值叫 BLOB_COLORS，写死在 cockpit 里，八个槽其实只有六个色
   * （第 7、8 槽是前两个的复制，也就是把「循环取色」写进了数据里）。
   * 分类色永不循环：第七个主题不生成新色，落到 ink-500。
   */
  seriesOnFill: ['#1d4ed8', '#c2410c', '#15803d', '#7e22ce', '#b45309', '#db2777'],
  /** 全对安全色板（图谱/散点），上限四槽 */
  graph4: {
    settled: '#1d4ed8',   // 已定
    facts: '#0891b2',     // 事实
    principles: '#65a30d',// 规律
    open: '#db2777',      // 待解
  },
  /** 顺序色板：单色相由浅到深（focus 蓝）。不许彩虹。 */
  sequential: ['#e8f0ff', '#bfdbfe', '#7ba7f0', '#3b73e0', '#0052d9', '#00379e'],
  /** 发散色板：暖冷两端 + 灰中点。中点必须是灰，不能是第三个色相。 */
  diverging: { warm: '#dc2626', mid: '#d3d7e0', cool: '#2563eb' },
  /**
   * 联想关系色板（六种关系 + 待确认）。域色板，不是分类色板 ——
   * 槽位绑死语义，不按顺序取，所以不跑 series 的相邻校验，改跑「每个值都过 AA」。
   *
   * 这套值原先住在 apps/web 的 relation-colors.ts 里，六个值有五个是系统值的字面副本
   * （precedent=info.solid、contradicts=risk.solid、causes=warn.solid、generalizes=ok.solid、
   * same_pattern=graph.edgeAssoc）。搬进来之后色值一个没变，变的是它们不再会各走各的。
   *
   * 保留原来的判断：同构紫是主角（微梦最常见的连接），相斥红必须刺眼（该报警的边不能温柔）；
   * causes 用 700 档而不是 600 —— #d97706 在白底上只有 3.1:1，小字不达标。
   * association 的灰压到 ink-400 而不是更浅档：「弱化」不等于「看不清」，
   * 看不清的证据等于没有证据。
   */
  relation: {
    same_pattern: '#7c3aed',
    precedent:    '#2563eb',
    contradicts:  '#dc2626',
    causes:       '#b45309',
    generalizes:  '#047857',
    association:  ink[400],
  },
  /** 关系胶囊的底色：同色系最浅一档。chip 用浅底深字，不用纯色块压白字。 */
  relationTint: {
    same_pattern: '#f3efff',
    precedent:    '#eff6ff',
    contradicts:  '#fef2f2',
    causes:       '#fffbeb',
    generalizes:  '#ecfdf5',
    association:  ink[50],
  },
} as const

/**
 * 层叠顺序。见 docs/DESIGN-SYSTEM.md §2.5
 *
 * 之前没有这一层，代价是可见的：下拉菜单同时出现在 z-50 和 z-20，遮罩在 40 和 50，
 * 而 Toast 因为「要盖住所有东西」但没有约定的顶，写了 `z-[100]`。
 * 任意值本身不是问题，问题是它证明当时没有可依据的刻度。
 *
 * 只有七档，每一档对应一种**行为**而不是一个数字：
 * 判据是「它要盖住谁」，不是「我想让它高一点」。
 */
export const layer: Record<string, string> = {
  base: '0',      // 常规内容
  raised: '10',   // 卡内浮起：叠放头像、角标、绝对定位的小标
  sticky: '20',   // 吸顶 / 吸底栏
  popover: '30',  // 下拉、日历、就地浮层（不带遮罩）
  overlay: '40',  // 遮罩层，以及贴在遮罩之上的常驻浮动件
  modal: '50',    // 弹窗 / 抽屉的面板
  toast: '60',    // 全局提示。**必须盖过弹窗** —— 弹窗里的操作也要能报结果
}

/**
 * 知识图谱的连线色阶。只有图谱在用，但仍放这里 —— 组件里不出现 hex 是红线，
 * 「只有一个消费者」不构成例外，否则下一个图表又会自己抄一份。
 */
export const graph = {
  edge: '#c7d2e8',        // 默认连线
  edgeWeak: ink[100],     // 弱连线
  edgeSimilar: '#c4b5fd', // 语义相似（淡紫虚线）
  edgeAssoc: '#7e22ce',   // 联想边（大脑自己连出来的关系）
  canvas: '#ffffff',      // 画布底；也用作节点之间的 2px 分隔环
} as const

/**
 * 字阶。具名档位，替代散落各处的 text-[11px] 一类任意值。
 * 不加 as const —— Tailwind 的 fontSize 只接受可变元组，readonly 会被类型系统拒。
 */
export const fontSize: Record<string, [string, { lineHeight: string }]> = {
  micro:   ['11px', { lineHeight: '1.4' }],
  meta:    ['13px', { lineHeight: '1.5' }],
  body:    ['15px', { lineHeight: '1.85' }],
  display: ['48px', { lineHeight: '1.1' }],
}

/**
 * 动效。产品界面只用 standard；spring 与 hero(700ms) 仅营销页。
 * 700ms 这一档对齐造物云 v8 的入场时长——营销页看三分钟可以慢，产品页一天八小时不行。
 */
export const motion = {
  duration: { fast: '150ms', base: '250ms', slow: '400ms', hero: '700ms' },
  easing: {
    standard: 'cubic-bezier(.25,1,.5,1)',
    spring: 'cubic-bezier(.34,1.56,.64,1)',
  },
} as const
