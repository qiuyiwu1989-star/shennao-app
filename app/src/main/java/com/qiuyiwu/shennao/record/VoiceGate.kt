package com.qiuyiwu.shennao.record

import kotlin.math.max
import kotlin.math.min

/**
 * 全时聆听的门：判断这一帧有没有人在说话，并累计「真正说了多久」。
 *
 * 它有两个用途，**必须是同一套判据**，否则账对不上：
 *   1. 门控上传——静音不编码、不上传、不转写。这是成本的大头。
 *   2. 计量聆听时长——用户买的是这个数，界面上显示的也是这个数。
 *
 * 四个设计要点，每一个都对应一种典型的失败：
 *
 * **① 自适应噪声地板，不用固定阈值。**
 * 安静办公室的说话音量，可能低于地铁车厢的背景噪声。任何常数阈值都会在一种
 * 环境里全程当成在说话、在另一种环境里全程当成没人。所以维护一条缓慢跟随的
 * 噪声地板，只在「明显高出地板」时才判定为语音。地板只在静音时上抬、
 * 任何时候都缓慢下沉——否则一段长时间的持续说话会把地板抬到说话音量之上，
 * 然后它就再也听不见人说话了。
 *
 * **② 前置缓冲（preroll）。**
 * 等确认「有人在说话」时，这句话的头两个字已经过去了。不留前置缓冲的 VAD，
 * 典型症状是转写出来每个人都从第二个字开始说话。所以判定开口时，
 * 要把已经缓存的前 `prerollMs` 一并计入并交给下游。
 *
 * **③ 挂起时延（hangover）。**
 * 人说话中间本来就有 200 到 600 毫秒的停顿。低于 `hangoverMs` 的静音不算结束，
 * 否则一句话会被切成七八段，每段都要单独走一次转写，贵且难读。
 *
 * **④ 最短语音段。**
 * 关门声、咳嗽、键盘声都能顶过阈值。短于 `minSpeechMs` 的整段直接丢弃，
 * 且**不计入聆听时长**——用户不该为一声咳嗽付钱。
 *
 * 「够不够长」和「计多少时长」是**两个数**，第一版写错过：
 *   · 判够不够长只看**真正发声**的那段（不含前置缓冲、不含尾部静音）。
 *     把前置缓冲算进去，200 毫秒的咳嗽会被凑成 600 毫秒当成一句话。
 *   · 计时长要算上前置缓冲——那段音频确实留下并转写了，成本真花了。
 *
 * **计费按帧级累计，不按段落跨度。** 两小时会议里真正出声的可能只有一小时，
 * 按跨度算就是按两小时收钱。只累计「这一帧高于阈值」的时间。
 * **段落边界与计费是两个尺度**：边界由 hangover 定（会议 900 毫秒，全时聆听要放到几十秒，
 * 否则一场会议被切成几百段、每段各起一次会话）；计费只看帧。
 *
 * ⚠️ 这套判据有两份实现：本文件与 `packages/core/src/voice-gate.ts`（网页端与账单对账）。
 * **两边共用同一组测试用例**，判据分叉的后果是端上按 A 计时、账单按 B 出，用户看到两个数。
 *
 * 纯函数式状态机，不碰 IO 也不碰安卓 API，因此可以在 JVM 上直接测。
 */
class VoiceGate(
    /** 每帧多少毫秒。调用方按采集缓冲大小固定传同一个值 */
    private val frameMs: Int = 20,
    /** 高出噪声地板多少才算语音（0..1 的感知音量刻度上） */
    private val marginOverFloor: Float = 0.055f,
    /** 连续多久高于阈值才判定开口。太短会被瞬时噪声骗，太长会吃掉开头 */
    private val openMs: Int = 120,
    /** 判定开口时往前多带这么久，补上开头被吃掉的字 */
    val prerollMs: Int = 400,
    /** 静音持续多久才判定这段话结束（说话中的停顿不算） */
    private val hangoverMs: Int = 900,
    /** 短于此的整段判为噪声，丢弃且不计费 */
    private val minSpeechMs: Int = 600,
) {
    enum class State { SILENT, SPEAKING }

    /** 这一帧之后发生了什么。调用方据此开段、写数据、封段 */
    enum class Event {
        /** 没人说话，什么都不用做 */
        IDLE,
        /** 刚判定有人开口：把 preroll 里缓存的音频先写进去，再写本帧 */
        OPEN,
        /** 说话中：正常写 */
        CONTINUE,
        /** 这段话结束：封段。可以上传 */
        CLOSE,
        /** 这段话太短，判为噪声：丢掉，不计时 */
        DISCARD,
    }

    var state: State = State.SILENT
        private set

    /** 噪声地板，缓慢跟随环境 */
    private var floor: Float = 0.02f
    /** 当前已连续高于阈值多久 */
    private var aboveMs: Int = 0
    /** 当前已连续低于阈值多久（说话状态下用于判结束） */
    private var belowMs: Int = 0
    /** 本段里真正高于阈值的帧累计——判去留和算账都只看它 */
    private var voicedMs: Int = 0

    /** 累计判定为说话的毫秒数。**这就是计费与界面显示的那个数** */
    var speechMs: Long = 0L
        private set

    /**
     * 本段到此刻为止**真正发声**的毫秒数。
     *
     * 全时聆听要用它做一个 [feed] 的返回值答不了的决定：OPEN 只说明「像是有人开口」，
     * 而是不是真的要为这一段起一次录音，得等它熬过 minSpeechMs 才知道。
     * 等 CLOSE 再决定就太晚了——CLOSE 要到几十秒的静音之后才来。
     */
    val voicedMsSoFar: Int get() = voicedMs

    /** 上一段的时长，供调用方写日志 */
    var lastSegmentMs: Int = 0
        private set

    /**
     * 喂一帧。[level] 是 [Level.of] 算出的 0..1 感知音量。
     */
    fun feed(level: Float): Event {
        val threshold = floor + marginOverFloor
        val loud = level > threshold

        // 噪声地板：静音时向上靠拢得慢，任何时候都允许缓慢下沉。
        // 两个方向都慢，但下沉必须一直进行——一段长演讲期间地板若只升不降，
        // 讲到后来它会高过人声，门就永远关着了。
        floor = if (!loud) floor + (level - floor) * 0.02f else floor * 0.9995f
        floor = min(0.5f, max(0.002f, floor))

        return when (state) {
            State.SILENT -> {
                if (loud) {
                    aboveMs += frameMs
                    if (aboveMs >= openMs) {
                        state = State.SPEAKING
                        belowMs = 0
                        // 触发开口的那几帧本身就是发声，计入
                        voicedMs = aboveMs
                        Event.OPEN
                    } else Event.IDLE
                } else {
                    aboveMs = 0
                    Event.IDLE
                }
            }
            State.SPEAKING -> {
                if (loud) {
                    voicedMs += frameMs
                    belowMs = 0
                    Event.CONTINUE
                } else {
                    belowMs += frameMs
                    if (belowMs < hangoverMs) return Event.CONTINUE
                    settle()
                }
            }
        }
    }

    /** 收尾：算两个数，一个判去留，一个算账 */
    private fun settle(): Event {
        val voiced = voicedMs                       // 真正发声：判够不够长
        val billable = prerollMs + voiced           // 留下并转写的：算账
        state = State.SILENT
        aboveMs = 0; belowMs = 0; voicedMs = 0
        lastSegmentMs = voiced
        return if (voiced < minSpeechMs) Event.DISCARD else {
            speechMs += billable.toLong(); Event.CLOSE
        }
    }

    /**
     * 停止聆听时调用：把没封的这段收掉。
     * 返回值与 [feed] 同义（CLOSE 要上传、DISCARD 丢掉、IDLE 本来就没在录）。
     */
    fun flush(): Event = if (state != State.SPEAKING) Event.IDLE else settle()

    /** 换一天 / 换一个计费周期时归零。噪声地板不归零，它是环境属性 */
    fun resetMeter() { speechMs = 0L }
}
