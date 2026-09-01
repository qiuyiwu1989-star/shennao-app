package com.qiuyiwu.shennao.ble

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 导入的生命周期归服务，不归界面。
 *
 * 2026-09-01 用户实测：「点击导入之后移动页面后就会停止掉」。根因有两处，
 * 都在 BleScreen 里：DisposableEffect 的 onDispose 直接 disconnect()，
 * 以及传完之后的落盘写在界面的 LaunchedEffect 中。
 *
 * 这两条都不是能靠单测跑出来的运行时行为（要真机、要设备），所以这里
 * 钉的是**源码结构**——一条能在 CI 上跑的、防止它悄悄长回来的门禁。
 * 结构门禁比没有门禁强，但它证明不了运行时正确：真机验证仍然要做。
 */
class ImportLifecycleTest {

    private fun src(name: String) =
        File("src/main/java/com/qiuyiwu/shennao/$name").readText()

    @Test fun `界面不许在离开时断开连接`() {
        val s = src("BleScreen.kt")
        val code = s.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "onDispose 里断连 = 用户切走一眼，传输就断在半路（27 KB/s，一小时录音要传四分半）",
            Regex("""onDispose\s*\{[^}]*disconnect""").containsMatchIn(code),
        )
    }

    @Test fun `落盘必须在服务里，不能在界面里`() {
        val screen = src("BleScreen.kt")
        val code = screen.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        // 文件已经完整传过来、就在内存里，这时候界面被销毁，它就没了——
        // 设备那边显示传过，深脑里什么都没有，而两边都不报错。
        assertFalse("Ingest.stage 不该由界面调用", code.contains("Ingest.stage"))
        assertTrue("落盘要在服务里", src("ble/BleImportService.kt").contains("Ingest.stage"))
    }

    @Test fun `超时看门狗也要在服务里——切走之后设备不吭声得有人发现`() {
        val service = src("ble/BleImportService.kt")
        assertTrue("服务要自己 tick", service.contains(".tick()"))
    }
}
