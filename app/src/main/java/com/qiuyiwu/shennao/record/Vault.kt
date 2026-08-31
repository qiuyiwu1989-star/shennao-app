package com.qiuyiwu.shennao.record

/*
 * 录音的落盘布局。
 *
 * 这里最要紧的一条决定：**状态就是文件名，不另存账本。**
 *
 * 安卓会在任何一个指令之间杀掉进程——内存里的队列、写到一半的 json 账本、
 * 还没 flush 的 SharedPreferences，都可能对不上。而「目录里有哪些文件」
 * 这件事，操作系统保证是一致的。所以一段录音走到哪一步，全部编码在后缀里：
 *
 *   seg-000003-0180000-0240000.pcm   还在录（或者：录的时候被杀了）
 *   seg-000003-0180000-0240000.aac   已封段，等着上传
 *   seg-000003-0180000-0240000.up    已上传并被服务端确认，音频已删
 *
 * 还认一个 .m4a：那是 v0.5 之前封的段。m4a 拼接会被服务端静默截断
 * （见 Encoder 里的实测），但已经躺在手机上的不能不认——不认就是孤儿，
 * 永远传不上去也永远删不掉。
 *
 * 没有「上传中」这个状态。上传中途死掉就退回 .m4a 重来一遍，
 * 而重来是安全的：ticket 用的幂等键由 序号 决定，不由重试次数决定。
 *
 * 序号和起止时刻也在文件名里，不在内存里。进程重启之后不需要「恢复计数器」——
 * 那种恢复一旦算错，两段录音会拿到同一个序号，服务端只会看到后一段。
 */

/** 一段录音的落盘身份。名字即状态。 */
data class Segment(
    val sequence: Int,
    /** 相对整场录音开头的毫秒偏移 */
    val startMs: Long,
    val endMs: Long,
    val state: State,
    /** 落盘后缀。只在 SEALED 时有意义（pcm/up 各只有一种） */
    val ext: String = if (state == State.SEALED) "aac" else "",
) {
    enum class State { RECORDING, SEALED, UPLOADED }

    /** v0.5 之前封的段是 m4a。只影响上传时报的 mimeType。 */
    val legacyM4a: Boolean get() = state == State.SEALED && ext == "m4a"

    /** 这一段该报什么 mimeType。判据只有一条：它实际是什么容器。 */
    val mimeType: String get() = if (legacyM4a) "audio/mp4" else "audio/aac"

    val durationMs: Long get() = endMs - startMs

    fun fileName(): String = "seg-%06d-%09d-%09d.%s".format(
        sequence, startMs, endMs,
        when (state) { State.RECORDING -> "pcm"; State.SEALED -> ext.ifBlank { "aac" }; State.UPLOADED -> "up" },
    )

    fun withState(s: State) = copy(state = s, ext = if (s == State.SEALED) ext.ifBlank { "aac" } else "")

    companion object {
        private val RE = Regex("""^seg-(\d{6})-(\d{9})-(\d{9})\.(pcm|aac|m4a|up)$""")

        /** 解析文件名。认不出的返回 null——目录里出现别的文件不该让整场录音失败。 */
        fun parse(name: String): Segment? {
            val m = RE.matchEntire(name) ?: return null
            val (seq, s, e, ext) = m.destructured
            val start = s.toLong()
            val end = e.toLong()
            // 结束早于开始说明文件名被外力改过。宁可当它不存在，
            // 也不要拿一个负数时长去调服务端——那会让整场录音的时间轴错位。
            if (end < start) return null
            return Segment(
                seq.toInt(), start, end,
                when (ext) {
                    "pcm" -> State.RECORDING
                    "aac", "m4a" -> State.SEALED
                    else -> State.UPLOADED
                },
                ext = if (ext == "aac" || ext == "m4a") ext else "",
            )
        }
    }
}

/**
 * 一场录音的元信息。这个 **是** 需要落盘的——它没法从文件名推出来。
 *
 * clientRequestId 是建会话的幂等键：同一场录音重试多少次，服务端都只建一个会话。
 * 它必须在录第一个字节之前就定下来并落盘，不能等到上传时才生成——
 * 否则进程重启后会拿新 id 再建一个会话，同一场会在深脑里裂成两条。
 */
data class SessionMeta(
    val clientRequestId: String,
    val title: String,
    /** 墙上时刻。录音真正开始的那一刻，不是上传的时刻。 */
    val startedAtEpochMs: Long,
    /** 用户已经点了停止。没有这个标记就不该 stop/finalize——录音可能还在继续。 */
    val finished: Boolean = false,
    /** 服务端会话 id。建会话之后落盘，省掉每轮重建；也是「去那场会」要用的。 */
    val serverSessionId: String? = null,
) {
    fun toJson(): String = org.json.JSONObject()
        .put("clientRequestId", clientRequestId)
        .put("title", title)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("finished", finished)
        .put("serverSessionId", serverSessionId ?: org.json.JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(s: String): SessionMeta? = runCatching {
            val o = org.json.JSONObject(s)
            val id = o.optString("clientRequestId").takeIf { it.isNotBlank() } ?: return null
            SessionMeta(
                id,
                o.optString("title").ifBlank { "手机录音" },
                o.optLong("startedAtEpochMs"),
                o.optBoolean("finished"),
                o.optString("serverSessionId").takeIf { it.isNotBlank() && it != "null" },
            )
        }.getOrNull()
    }
}

/**
 * 存储出口。真实实现读写沙箱目录，测试里换成内存。
 *
 * 抽这一层不是为了「解耦」，是为了让「进程在第 3 步被杀会怎样」能在单元测试里
 * 直接摆出来——那是这一版唯一真正难的地方，而它不该只能靠拔电池来验证。
 */
interface Vault {
    /** 有哪些录音会话（目录名） */
    fun sessions(): List<String>
    fun readMeta(session: String): SessionMeta?
    fun writeMeta(session: String, meta: SessionMeta)

    /**
     * 原子地改 meta 的某一个字段。
     *
     * 必须有这个，不能各自「读出来 → copy → 写回去」：录音线程写 finished=true
     * 的同时，上传线程可能正拿着旧 meta 要写 serverSessionId——后写的那个会把
     * finished 抹回 false，这场录音就再也不会收尾了。表现是「偶尔有一场
     * 一直显示在传」，而且只在停止录音的那一两秒内触发，极难复现。
     *
     * 返回改完之后的值；这场录音不存在时返回 null。
     */
    fun updateMeta(session: String, f: (SessionMeta) -> SessionMeta): SessionMeta?
    /** 这场录音的所有分段，按序号升序 */
    fun segments(session: String): List<Segment>
    fun readSegment(session: String, seg: Segment): ByteArray?
    /** 改后缀=换状态。必须是原子的重命名，不能是「复制+删除」 */
    fun rename(session: String, from: Segment, to: Segment): Boolean
    fun deleteSession(session: String)
}
