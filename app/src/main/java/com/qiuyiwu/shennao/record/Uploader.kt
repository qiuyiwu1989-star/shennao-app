package com.qiuyiwu.shennao.record

import com.qiuyiwu.shennao.Http
import org.json.JSONObject

/*
 * 把落盘的分段推进深脑。
 *
 * 六步链路：建会话 → 要分片地址 → 直传 COS → 确认分片 → **stop 冻结清单** → finalize。
 * 漏掉 stop，finalize 必然 409「录音尚未冻结分片数量」——这是 Mac 端付过学费的。
 *
 * 手机端比 Mac 端多一个难处：**边录边传**。所以 stop 不是紧接着分片的下一步，
 * 而是要等到用户真的停止、且所有分段都确认之后。中间任何时刻进程都可能被杀，
 * 所以这个函数被设计成「可以从任何一步的中断处再跑一遍」：
 *
 *   - 建会话幂等（clientRequestId），重跑拿回同一个会话
 *   - 分片幂等（idempotencyKey = 请求id + 序号），已验证过的会返回 409 CHUNK_ALREADY_VERIFIED，
 *     那不是错误，是「这一片已经在了」
 *   - 每片确认成功后立刻改后缀落盘。改名是原子的，所以不存在「传了但不知道传没传」
 *
 * 不做的事：不并发传多片。手机网络下并发的收益很小，而一旦某片失败、
 * 另一片已经改了名，恢复逻辑就要处理空洞。顺序传慢一点，但永远对得上。
 */

/** 一场录音推送的结果。给界面用，也给测试断言用。 */
sealed class DrainResult {
    /** 全部推完并收尾了 */
    data class Done(val sessionId: String, val chunks: Int) : DrainResult()
    /** 传了一些，但录音还没结束（或还有片没传完），下一轮继续 */
    data class Progress(val uploaded: Int, val remaining: Int) : DrainResult()
    /** 这一轮什么也没做 */
    object Idle : DrainResult()
    /** 出错了。message 是给人看的，retryable 决定要不要留着下一轮再试 */
    data class Failed(
        val message: String,
        val retryable: Boolean,
        /** 服务端说 token 过期了。上层据此续一次再重来，只续一次 */
        val authExpired: Boolean = false,
    ) : DrainResult()
}

class Uploader(
    private val http: Http,
    private val vault: Vault,
    private val apiBase: String,
    /**
     * 取当前 access token 和 org。返回 null 表示没登录，这一轮别动。
     *
     * 参数 force=true 表示「刚才那个被服务端拒了，给我换一个」。
     * 一场会开两小时，access token 只活一小时——不续期的话，从第 60 分钟起
     * 每一段都会静默传不上去，而界面上什么都看不出来。
     */
    private val auth: (force: Boolean) -> Pair<String, String>?,
) {

    /** 把所有会话推一遍。返回每场的结果，界面按需要挑着显示。 */
    fun drainAll(): Map<String, DrainResult> =
        vault.sessions().associateWith { drain(it) }

    fun drain(session: String): DrainResult {
        val r = attempt(session, false)
        // 只续一次。续了还是 401 就是真的登录失效了，反复重试只会烧电，
        // 而正确的做法是让界面把人送去重新登录。
        return if (r is DrainResult.Failed && r.authExpired) attempt(session, true) else r
    }

    private fun attempt(session: String, forceAuth: Boolean): DrainResult {
        val meta = vault.readMeta(session) ?: return DrainResult.Failed("这场录音的元信息读不出来", false)
        val segs = vault.segments(session)
        val sealed = segs.filter { it.state == Segment.State.SEALED }
        val recording = segs.filter { it.state == Segment.State.RECORDING }

        // 一段都没录到就停了。空会话推到服务端只会变成一条永远分析不出东西的记录——
        // 在建会话之前就挡掉，不是建完再删。
        if (meta.finished && segs.isEmpty()) {
            vault.deleteSession(session)
            return DrainResult.Idle
        }
        // 还在录、又没有已封段的东西可传——不用惊动服务端
        if (sealed.isEmpty() && !meta.finished) return DrainResult.Idle
        // 已结束但还有没封的段：封段是录音线程的活，等它做完。
        // 这里绝不能往下走去 stop——冻结清单会把还没封的那段永久关在门外。
        if (meta.finished && recording.isNotEmpty()) {
            return DrainResult.Progress(segs.count { it.state == Segment.State.UPLOADED },
                                        sealed.size + recording.size)
        }

        val (token, org) = auth(forceAuth) ?: return DrainResult.Failed("还没登录", true)
        val h = mapOf(
            "Authorization" to "Bearer $token",
            "x-deepbrain-org-id" to org,
            "Content-Type" to "application/json",
        )

        val ref = ensureSession(meta, session, h)
        when (ref) {
            is SessionRef.Err -> return DrainResult.Failed(ref.message, ref.retryable, ref.authExpired)
            is SessionRef.Frozen -> {
                // 服务端这条会话已经冻结（stop 过了）。分片再也进不去。
                val pending = segs.count { it.state != Segment.State.UPLOADED }
                if (pending == 0) {
                    // 正常情况：上一轮 finalize 成功了，只是死在删本地目录之前。
                    vault.deleteSession(session)
                    return DrainResult.Done(ref.id, segs.size)
                }
                // 单段的导入（灵魂卡 / 分享）撞上服务端已经收尾的同一个幂等键：这是重复导入
                // （重装后再导、账本清过），不是丢音频——服务端那份就是这份。删本地，别留永久失败行（012 P3-3）。
                if (segs.size == 1 && (meta.clientRequestId.startsWith("ble-") || meta.clientRequestId.startsWith("share-"))) {
                    vault.deleteSession(session)
                    return DrainResult.Done(ref.id, 0)
                }
                // 不正常：清单冻结了，手上还有没进去的音频。删掉就是把录音扔了，
                // 留着每轮都会失败。报出来让人看见，本地文件原样保留。
                return DrainResult.Failed(
                    "深脑那边这场已经收尾了（${ref.status}），但本地还有 $pending 段没进去。" +
                        "这几段需要另建一场重推。", false)
            }
            is SessionRef.Ok -> Unit
        }
        val sid = (ref as SessionRef.Ok).id

        /*
         * 一段失败**不能停掉整场**。
         *
         * 2026-08-31 实测：用户录了 15 段，只有第 1 段进了深脑。原因就在这里——
         * 第 1 段的 ticket 返回了一种我没认出的 409，代码直接 return，
         * 于是第 2 到第 15 段永远轮不到。而分片之间本来是独立的：
         * 一段进不去，不该连累其它 14 段。
         *
         * 登录过期是唯一的例外：那不是「这一段」的问题，是整轮都动不了，
         * 继续试下去只是把同一个 401 重复 15 遍。
         */
        var done = 0
        val stuck = mutableListOf<String>()
        for (seg in sealed) {
            when (val r = push(sid, session, seg, meta, h)) {
                is StepResult.Ok, is StepResult.Skip -> done++
                is StepResult.Err -> {
                    if (r.authExpired) return DrainResult.Failed(r.message, true, true)
                    stuck += r.message
                }
            }
        }

        val uploadedNow = segs.count { it.state == Segment.State.UPLOADED } + done
        if (!meta.finished) {
            return if (stuck.isEmpty()) DrainResult.Progress(uploadedNow, 0)
                   else DrainResult.Failed(stuck.first(), true)
        }

        // 到这里：用户已停止、没有未封段、所有分段都已确认。可以冻结清单了。
        val all = vault.segments(session)
        val ready = all.filter { it.state == Segment.State.UPLOADED }
        if (ready.size != all.size) {
            // 还有段没进去就不能冻结清单——冻结之后它们永远进不来了。
            // 但要把卡住的原因报出去，否则界面只会一直显示「上传中」。
            return if (stuck.isEmpty()) DrainResult.Progress(ready.size, all.size - ready.size)
                   else DrainResult.Failed(
                       "${all.size - ready.size} 段进不去：${stuck.first()}", true)
        }

        val totalMs = ready.maxOf { it.endMs }
        val stop = http.request(
            "POST", "$apiBase/api/recordings/$sid/stop", h,
            JSONObject(mapOf("durationMs" to totalMs, "expectedChunkCount" to ready.size)).toString(),
        )

        /*
         * 服务端说清单不全时，**它会告诉我们缺哪几片**（missingSequences）。
         *
         * 2026-08-31：一场 56 片的录音卡在这里反复 409，而服务端每次都附着
         * 缺号清单，是客户端把它扔了——只报了一句「冻结清单失败（409）」。
         * 手上明明有一份精确的差异，却拿去当成一个笼统的失败重试，
         * 于是每 15 秒重复同一个错，永远不会好。
         *
         * 缺的那几片本地还留着音频（改名成 .up 时只改了名字，字节没删），
         * 所以能自愈：把它们改回待传，下一轮自然会重推。
         */
        if (stop.status == 409 && stop.body.contains("MANIFEST_INCOMPLETE")) {
            val missing = missingSequences(stop.body)
            if (missing.isNotEmpty()) {
                var revived = 0
                for (seq in missing) {
                    val seg = ready.firstOrNull { it.sequence == seq } ?: continue
                    if (vault.rename(session, seg, seg.copy(state = Segment.State.SEALED))) revived++
                }
                return DrainResult.Progress(ready.size - revived, revived)
            }
            // 服务端说不全、却没说缺哪片：这时重试没有意义，报出来让人看见。
            return DrainResult.Failed("服务端说分片不全，但没说缺哪几片", false)
        }
        // 已经冻结过了也算成功——重跑到这一步是正常的
        if (stop.status >= 400 && !stop.body.contains("INVALID_STATE")) {
            return DrainResult.Failed("冻结清单失败（${stop.status}）", stop.status >= 500)
        }
        val fin = http.request("POST", "$apiBase/api/recordings/$sid/finalize", h, "{}")
        if (fin.status >= 400 && !fin.body.contains("INVALID_STATE")) {
            return DrainResult.Failed("收尾失败（${fin.status}）", fin.status >= 500)
        }

        vault.deleteSession(session)
        return DrainResult.Done(sid, ready.size)
    }

    // ---- 单步 ----

    private sealed class StepResult {
        object Ok : StepResult()
        object Skip : StepResult()
        data class Err(val message: String, val retryable: Boolean, val authExpired: Boolean = false) : StepResult()
    }

    private sealed class SessionRef {
        data class Ok(val id: String) : SessionRef()
        /** 服务端已经冻结/收尾了这条会话，分片进不去 */
        data class Frozen(val id: String, val status: String) : SessionRef()
        data class Err(val message: String, val retryable: Boolean, val authExpired: Boolean = false) : SessionRef()
    }

    /**
     * 拿到服务端会话 id，顺便认一下它现在是什么状态。
     *
     * 状态必须看。三种要分开待：
     *   recording / uploading —— 正常，可以继续塞分片
     *   failed —— **不是**「已经做完了」。把 failed 当完成，会让一条一个字都没进
     *             深脑的录音永远挡着重传。Mac 端在这里栽过，这里不再栽第二次。
     *   其余（已 stop / 已收尾）—— 清单冻结了，交给上面判断是收尾还是出事了
     */
    private fun ensureSession(meta: SessionMeta, session: String, h: Map<String, String>): SessionRef {
        val r = http.request(
            "POST", "$apiBase/api/recordings", h,
            JSONObject(mapOf(
                "clientRequestId" to meta.clientRequestId,
                "title" to meta.title,
                "captureClient" to "android",
                "capabilities" to org.json.JSONArray(listOf("mic")),
                "startedAt" to iso(meta.startedAtEpochMs),
            )).apply { meta.scene?.let { put("scene", it) } }.toString(),
        )
        if (r.status == 401) return SessionRef.Err("登录过期", true, authExpired = true)
        if (r.status >= 400) return SessionRef.Err("建会话失败（${r.status}）", r.status >= 500)
        val o = runCatching { JSONObject(r.body).getJSONObject("session") }.getOrNull()
            ?: return SessionRef.Err("建会话的应答看不懂", true)
        val id = o.optString("id").takeIf { it.isNotBlank() }
            ?: return SessionRef.Err("建会话没返回 id", true)
        if (meta.serverSessionId != id) vault.updateMeta(session) { it.copy(serverSessionId = id) }

        val status = o.optString("status")
        return when (status) {
            "failed" -> SessionRef.Err(
                "深脑那边这场是 failed 状态。它挡着重传——需要另建一场重推。", false)
            "recording", "uploading" -> SessionRef.Ok(id)
            else -> SessionRef.Frozen(id, status)
        }
    }

    private fun push(
        sid: String, session: String, seg: Segment, meta: SessionMeta, h: Map<String, String>,
    ): StepResult {
        // 有文件本体就流式发，不整个读进内存（导入的 opus、分享的 wav 都是一整段，几 MB 到上百 MB）。
        // 内存 vault（测试）没有文件，退回按字节发。012 P0-4
        val file = vault.segmentPath(session, seg)
        val bytes = if (file == null) vault.readSegment(session, seg) else null
        if (file == null && bytes == null)
            // 文件不见了（被清理器扫掉、或者用户清了数据）。改成已上传会让服务端少收一片、
            // 清单对不上；留着又会每轮都失败。当成不可重试的错误报上去，让人看见。
            return StepResult.Err("第 ${seg.sequence} 段的音频文件不见了", false)
        val byteLength = file?.length() ?: bytes!!.size.toLong()

        val ticket = http.request(
            "POST", "$apiBase/api/recordings/$sid/chunks/ticket", h,
            JSONObject(mapOf(
                "sequence" to seg.sequence,
                "idempotencyKey" to "${meta.clientRequestId}-${seg.sequence}",
                "mimeType" to seg.mimeType,   // 每段报自己的真实容器：v0.5 之前封的是 m4a
                "byteLength" to byteLength,
                "startedAtMs" to seg.startMs,
                // 服务端要求 endedAtMs ≥ 1 且大于开始。零长度的段不该走到这里，
                // 但真走到了也不能让整场录音卡住。
                "endedAtMs" to maxOf(seg.endMs, seg.startMs + 1),
                "uploadMode" to "background",
            )).toString(),
        )
        if (ticket.status == 401) return StepResult.Err("登录过期", true, authExpired = true)
        if (ticket.status == 409) {
            // 409 有好几种，而它们对客户端的含义是同一个：**这一片服务端不再收了**。
            // 之前只认 CHUNK_ALREADY_VERIFIED，其余 409 走到下面变成致命错误，
            // 把整场都卡死了。
            //
            // 已验证 = 字节已经在服务端，改名收工；
            // 其余（元数据对不上、状态冲突）= 重试多少次都是同一个答案，
            // 报出来让人看见，但不要挡住别的分片。
            if (ticket.body.contains("CHUNK_ALREADY_VERIFIED")) {
                vault.rename(session, seg, seg.withState(Segment.State.UPLOADED))
                return StepResult.Skip
            }
            val code = codeOf(ticket.body)
            // 服务端已经收下这一片的字节、只是元数据对不上（CHUNK_CONFLICT）：
            // 再试一百次也是同一个答案，而字节确实在服务端。认下来往前走，
            // 否则这一场会永远卡住——用户手机每 10 秒空转一次，什么都传不上去。
            if (code == "CHUNK_CONFLICT") {
                vault.rename(session, seg, seg.withState(Segment.State.UPLOADED))
                return StepResult.Skip
            }
            return StepResult.Err("第 ${seg.sequence} 段服务端不收（$code）", false)
        }
        if (ticket.status >= 400) {
            return StepResult.Err("第 ${seg.sequence} 段要地址失败（${ticket.status}）", ticket.status >= 500)
        }
        val url = runCatching { JSONObject(ticket.body).optString("uploadUrl") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: return StepResult.Err("第 ${seg.sequence} 段没拿到上传地址", true)

        val put = if (file != null) http.requestFile("PUT", url, mapOf("Content-Type" to seg.mimeType), file)
                  else http.requestBytes("PUT", url, mapOf("Content-Type" to seg.mimeType), bytes!!)
        if (put.status >= 400) {
            return StepResult.Err("第 ${seg.sequence} 段直传失败（${put.status}）", true)
        }

        val ok = http.request(
            "POST", "$apiBase/api/recordings/$sid/chunks/${seg.sequence}/complete", h, "{}",
        )
        if (ok.status == 401) return StepResult.Err("登录过期", true, authExpired = true)
        if (ok.status >= 400) {
            return StepResult.Err("第 ${seg.sequence} 段确认失败（${ok.status}）", ok.status >= 500)
        }
        // 确认之后才改名。反过来的话，一旦确认失败，这一片会被当成传好了而永远丢掉。
        vault.rename(session, seg, seg.withState(Segment.State.UPLOADED))
        return StepResult.Ok
    }

    /**
     * 取服务端给的缺号清单。
     * 结构是 {"error":{"code":"MANIFEST_INCOMPLETE","details":{"missingSequences":[...]}}}
     */
    private fun missingSequences(body: String): List<Int> = runCatching {
        val e = JSONObject(body).optJSONObject("error") ?: JSONObject(body)
        val arr = (e.optJSONObject("details") ?: e).optJSONArray("missingSequences")
            ?: return emptyList()
        (0 until arr.length()).map { arr.getInt(it) }
    }.getOrElse { emptyList() }

    /** 从错误应答里取 code。取不到就还回状态码本身——总比一句「失败了」强。 */
    private fun codeOf(body: String): String = runCatching {
        // 服务端的错误体是 {"error":{"code":...}}，**code 是嵌在里面的**。
        // 按平铺去取会永远拿到空串，然后界面上显示一个「服务端不收（409）」，
        // 等于什么都没说。
        val o = JSONObject(body)
        (o.optJSONObject("error")?.optString("code") ?: o.optString("code"))
            .ifBlank { "409" }
    }.getOrElse { "409" }

    private fun iso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}
