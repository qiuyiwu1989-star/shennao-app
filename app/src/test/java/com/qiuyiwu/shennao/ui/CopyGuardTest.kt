package com.qiuyiwu.shennao.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/*
 * 文案门禁（docs/design/014-文案规范.md）。
 *
 * 规范里的词表不靠人记：给用户看的字符串里出现内部词就红。
 * 判据只扫**字面字符串**，注释不算——注释里怎么说「服务端」都行，用户看不见。
 */
class CopyGuardTest {

    /** 用户看得见文字的文件。诊断包（发给开发者的）和蓝牙底层的步骤名不在内。 */
    private val userFacing = listOf(
        "TodayScreen.kt", "HistoryScreen.kt", "RecordScreen.kt", "MeetingScreen.kt", "MeScreen.kt",
        "AskScreen.kt", "SearchScreen.kt", "PersonScreen.kt", "SpeakerClaimScreen.kt", "BleScreen.kt",
        "MainActivity.kt", "Chrome.kt", "Installer.kt", "Client.kt", "Update.kt",
        "record/RecordingService.kt", "record/Uploader.kt", "record/OrphanNotice.kt",
        "ble/BleImportService.kt", "ble/LinkTuning.kt",
    )

    /** 内部词 → 该说什么。 */
    private val banned = mapOf(
        "服务端" to "深脑", "服务器" to "深脑", "应答" to "回复", "回包" to "回复",
        "落盘" to "存", "分片" to "段", "建会话" to "深脑没接下这场", "冻结清单" to "收尾",
        "推送" to "传 / 送到", "上传中" to "在传", "落账" to "记", "——" to "句号或逗号",
    )

    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java/com/qiuyiwu/shennao").isDirectory) d = d.parentFile
        return File(d!!, "app/src/main/java/com/qiuyiwu/shennao")
    }

    /** 只取字面字符串（含中文的），跳过注释行。 */
    private fun strings(f: File): List<Pair<Int, String>> =
        f.readLines().withIndex().filter { (_, l) ->
            val t = l.trim(); !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
        }.flatMap { (i, l) ->
            Regex(""""([^"\n]*[一-鿿][^"\n]*)"""").findAll(l).map { (i + 1) to it.groupValues[1] }.toList()
        }

    @Test fun `给用户看的字里没有内部词`() {
        val hits = mutableListOf<String>()
        for (name in userFacing) {
            val f = File(root(), name); if (!f.isFile) continue
            for ((line, s) in strings(f)) for ((bad, good) in banned) {
                if (bad in s) hits += "$name:$line 「$s」含「$bad」→ 说「$good」"
            }
        }
        assertTrue("文案里有内部词：\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /** 弹窗里的「不做」一律叫「取消」；「好」不是动词，按钮上不许单独出现。 */
    @Test fun `对话框按钮：不做叫取消，确认按钮是动词`() {
        val hits = mutableListOf<String>()
        for (name in userFacing) {
            val f = File(root(), name); if (!f.isFile) continue
            for ((line, s) in strings(f)) if (s == "算了" || s == "好" || s == "确定") hits += "$name:$line 「$s」"
        }
        assertTrue("对话框按钮措辞：\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
