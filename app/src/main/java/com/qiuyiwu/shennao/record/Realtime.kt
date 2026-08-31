package com.qiuyiwu.shennao.record

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * 实时字幕。
 *
 * ## 唯一的红线：**它绝不能伤到录音**
 *
 * 录音是产品，字幕是附赠。所以这里的每一个决定都朝同一个方向倒：
 *   · 采集线程**永不因为网络阻塞**。喂数据是往一个有界队列里塞，塞不下就丢，
 *     丢的是字幕不是录音——反过来则会让整场会少掉几秒音频。
 *   · 连不上、断线、网关报错，一律只影响字幕。录音线程完全不知道这件事发生过。
 *   · 停止录音时先关字幕，再收尾录音——顺序反了会让最后一段等在网络上。
 *
 * ## 实时稿的身份
 *
 * 仓规：实时稿不进入长期记忆，也不能被当成亲证事实。所以客户端这边
 * **只显示、不落盘、不上传**——网关自己会把 final 段投影到服务端，
 * 那条路有它自己的闸门，与这里无关。
 */
class Realtime(
    private val onSegment: (String, Boolean) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)      // 会议室的 NAT 常在 60 秒后掐掉空闲连接
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 是长连接，读超时必须关掉
        .build()

    private var ws: WebSocket? = null
    private val running = AtomicBoolean(false)

    /**
     * 待发的音频。有界，满了就丢最旧的。
     *
     * 容量按 ~4 秒音频定：再多就说明网络已经跟不上了，而攒着几十秒的旧音频
     * 迟早会一次性涌出去，把字幕推到比现场晚半分钟——那时它已经没用了。
     */
    private val queue = ArrayBlockingQueue<ByteArray>(20)
    private var pump: Thread? = null

    fun start(gatewayUrl: String, ticket: String, startedAtMs: Long) {
        if (running.getAndSet(true)) return
        val req = Request.Builder()
            .url(gatewayUrl)
            .addHeader("Authorization", "Bearer $ticket")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 先报锚点：网关按它把段落时间对回录音的时间轴
                webSocket.send(JSONObject()
                    .put("type", "anchor").put("startedAtMs", startedAtMs).toString())
                onState("已连上")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.optString("type")) {
                    "ready" -> onState("正在听")
                    "segment" -> {
                        val seg = o.optJSONObject("segment") ?: return
                        val t = seg.optString("text")
                        if (t.isNotBlank()) onSegment(t, o.optBoolean("isFinal"))
                    }
                    // gap 要说出来。字幕断了一截而界面若无其事，
                    // 用户会以为那段时间没人说话。
                    "gap" -> onState("字幕断了一段（${o.optString("reason")}），录音没受影响")
                    "ended" -> onState("字幕已结束")
                    "error" -> onState("字幕出错：${o.optString("code")}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 只报字幕失败。**绝不碰录音**。
                onState("字幕连不上，录音继续")
                running.set(false)
            }
        })

        pump = Thread({
            while (running.get()) {
                val buf = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { ws?.send(buf.toByteString()) }
            }
        }, "shennao-rt").apply { isDaemon = true; start() }
    }

    /**
     * 喂音频。**永远不阻塞**——采集线程调它。
     *
     * 队列满了就丢掉最旧的一帧再放新的：字幕宁可缺一句，也不能让采集线程等，
     * 那一等丢的是录音本身。
     */
    fun feed(buf: ByteArray, n: Int) {
        if (!running.get() || n <= 0) return
        val copy = buf.copyOf(n)
        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { ws?.send(JSONObject().put("type", "end").toString()) }
        runCatching { ws?.close(1000, null) }
        ws = null
        queue.clear()
        pump = null
    }
}
