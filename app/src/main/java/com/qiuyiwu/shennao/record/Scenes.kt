package com.qiuyiwu.shennao.record

/**
 * 录前选场合 = 选方法（spec 010）。键要和服务端 SESSION_SCENES / 库里的 check 一致——
 * 加一个场合三处一起改。六个，不再多：这是开录前一眼扫过的一行，不是表单。
 * 不选也行：老会话、分享进来的文件都没有场合。
 */
object Scenes {
    val all: List<Pair<String, String>> = listOf(
        "meeting" to "会议", "one_on_one" to "一对一", "interview" to "访谈",
        "negotiation" to "谈判", "lecture" to "课程", "memo" to "随手记",
    )
    fun label(key: String?): String? = all.firstOrNull { it.first == key }?.second
    fun isKnown(key: String?): Boolean = key != null && all.any { it.first == key }
}
