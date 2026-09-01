package com.qiuyiwu.shennao

import org.json.JSONObject

/**
 * 问深脑（流式）。
 *
 * **为什么要流式而不是等一个完整答复。** agent 模式会翻好几篇文档，
 * 一个问题从提交到答完常在二十秒以上。中间什么都不显示的话，
 * 用户在第八秒就会认为它挂了——而它没有。
 *
 * SSE 的解析故意写得笨：一行一行读，只认 `data: `。不引入任何库——
 * 这条流的形状由我们自己的服务端决定，而它只有 6 种事件。
 */
object Ask {

    /** 界面要显示的东西。原样从服务端来，不在客户端二次判断。 */
    sealed class Event {
        /** 走的是快路还是智能体。智能体会慢，说出来用户就不会以为卡住了。 */
        data class Mode(val mode: String) : Event()
        /** 智能体正在翻哪一篇。这是二十秒里唯一能让人安心的东西。 */
        data class Step(val what: String) : Event()
        data class Token(val delta: String) : Event()
        /** 库里没有足够的依据。**这不是失败**，是深脑在说「我不知道」。 */
        object Insufficient : Event()
        object Done : Event()
        data class Failed(val message: String) : Event()
    }

    /**
     * 把一行 SSE 解析成事件。认不出的行返回 null——**不猜**。
     *
     * 服务端将来加了新事件类型，这里返回 null 只是少显示一条中间状态；
     * 而猜错会把一条 tool_call 显示成答案正文。
     */
    fun parseLine(line: String): Event? {
        if (!line.startsWith("data: ")) return null
        val o = runCatching { JSONObject(line.substring(6)) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            "mode" -> Event.Mode(o.optString("mode"))
            "token" -> Event.Token(o.optString("delta"))
            "tool_call" -> {
                val name = o.optString("name").ifBlank { "查资料" }
                Event.Step(humanTool(name))
            }
            "insufficient" -> Event.Insufficient
            "done" -> Event.Done
            "error" -> Event.Failed(o.optString("message").ifBlank { "答不出来" })
            else -> null
        }
    }

    /** 工具名翻成人话。`search_atoms` 对用户没有意义。 */
    private fun humanTool(name: String): String = when {
        name.contains("atom") -> "在翻判断"
        name.contains("transcript") || name.contains("material") -> "在翻原文"
        name.contains("person") || name.contains("people") -> "在翻人物档案"
        name.contains("commit") -> "在翻承诺"
        name.contains("topic") -> "在翻主题"
        else -> "在查资料"
    }
}
