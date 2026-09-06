package com.qiuyiwu.shennao.ble

import com.qiuyiwu.shennao.record.FileVault
import com.qiuyiwu.shennao.record.Segment
import com.qiuyiwu.shennao.record.SessionMeta
/**
 * 把从录音笔导进来的一个文件，交给**和手机录音同一条上传链路**。
 *
 * ## 为什么不另开一条
 *
 * 上传那条路已经踩平了很多坑：断点续传、分片幂等、时间轴严丝合缝、
 * 半成品不能被传走、409 各种含义。另写一条就是把这些坑再踩一遍——
 * 而且两条路会慢慢长歪，某天只有一条修好了另一条没有。
 *
 * ## 一个不做的决定
 *
 * 导进来的是设备已经压好的 opus，**不重新编码**。
 * 转成 AAC 只会让音质白掉一层，而服务端本来就接受 audio/ogg。
 */
object Ingest {

    /**
     * 落成一场待上传的录音。
     *
     * 整个文件当作**一片**：录音笔的文件是完整的一段，切开只会给自己制造
     * 「时间轴要严丝合缝」的麻烦——而那正是 8-31 让所有录音传不上去的原因。
     *
     * @param startedAtEpochMs 录音真实开始的时刻。设备文件名里带着它，
     *        比「导入的时刻」有意义得多——深脑那边的时间轴按它排。
     */
    fun stage(
        vault: FileVault,
        bytes: ByteArray,
        title: String,
        durationMs: Long,
        startedAtEpochMs: Long,
    ): String? {
        if (bytes.isEmpty()) return null

        /*
         * **设备吐的是裸 OPUS 定长包，不是 Ogg 文件。**
         *
         * 直接当 audio/ogg 传上去，ffmpeg 会解出垃圾——而且多半不报错，
         * 就像 8-31 那次 m4a 拼接：服务端收下了，内容是坏的，没有一层说话。
         * 所以先封成 Ogg，并按包数重算时长（设备报的秒数只精确到秒）。
         */
        val ogg = if (OggWrap.looksRaw(bytes)) {
            runCatching { OggWrap.wrap(bytes) }.getOrNull() ?: return null
        } else bytes
        val realMs = if (OggWrap.looksRaw(bytes)) OggWrap.durationMs(bytes.size) else durationMs
        if (realMs <= 0) return null
        /*
         * **幂等键由文件本身决定，不能用随机 UUID。**
         *
         * 深脑用 clientRequestId 做建会话的幂等键：同一个键重推只会拿回同一个会话。
         * 我原来写的是 UUID.randomUUID()——每次导入都是新的，于是：
         *   · 同一个文件在手机上导两次 → 深脑里两条
         *   · Mac 导过一次、手机再导一次 → 又是两条
         * 而这两条内容完全一样，用户还得自己去删。
         *
         * Mac 端一直是对的：`mac-ble-<文件名>`。这里用 `ble-<文件名>` ——
         * **两端故意不共用前缀**：录音笔的文件名只在这一支设备里唯一，
         * 跨设备同名（两支笔都有 note20260901-1400.opus）时，
         * 共用前缀会把两段不同的录音判成同一条，那比重复更糟。
         */
        val meta = SessionMeta(
            // 幂等键仍然是文件名（和 Mac 端对齐要靠它）；显示名换成人话（012 P1-5）
            clientRequestId = "ble-" + title.take(80),
            title = displayTitle(startedAtEpochMs),
            startedAtEpochMs = startedAtEpochMs,
            finished = true,        // 导入的文件天生就是完整的，不用等停止
        )
        val session = vault.newSession(meta)
        val seg = Segment(0, 0, realMs, Segment.State.SEALED, ext = "opus")
        return runCatching {
            vault.segmentFile(session, seg).writeBytes(ogg)
            session
        }.getOrNull()
    }

    /**
     * 从设备的文件名里解出录音时刻。
     *
     * CB08 的命名是 `note20260828-205856.opus`（本地时间）。解不出来时
     * **返回 null 而不是「现在」**——用现在的话，一场三天前的会会排在今天，
     * 而深脑的整条时间轴都建立在这个时刻上。
     */
    /** 「灵魂卡 · 9月5日 14:00」。文件名 note20260905-140000 是给机器看的。 */
    fun displayTitle(startedAtEpochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA); f.timeZone = zone
        return "灵魂卡 · " + f.format(java.util.Date(startedAtEpochMs))
    }
    fun startedAtFrom(name: String, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        val m = Regex("""(\d{8})-(\d{6})""").find(name) ?: return null
        return runCatching {
            val f = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            f.timeZone = zone
            f.isLenient = false          // 20261340-999999 这种要解失败，不要被"宽容"成别的日期
            f.parse("${m.groupValues[1]}-${m.groupValues[2]}")?.time
        }.getOrNull()
    }
}
