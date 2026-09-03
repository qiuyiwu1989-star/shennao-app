package com.qiuyiwu.shennao.ble

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动同步靠这份账本判断"要不要跳过"——它算错了，轻则白等几分钟重新下载
 * 一份已经导过的文件，重则（如果反过来算错）把真的没导过的文件当成
 * 已经导过而漏掉。两种错都要钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedRegistryTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `没导过的文件默认不算已导入`() {
        val r = ImportedRegistry(ctx)
        assertFalse(r.isImported("AA:BB", "note1"))
    }

    @Test fun `标记过的文件下次查询要能查到`() {
        val r = ImportedRegistry(ctx)
        r.markImported("AA:BB", "note1")
        assertTrue(r.isImported("AA:BB", "note1"))
    }

    @Test fun `两支不同录音笔的同名文件不能互相顶替——协议允许重名`() {
        // Ingest.kt 的幂等键注释里写过：两支笔完全可能各自生成一个
        // note20260901-1400.opus，这在协议层是允许的。本地账本如果
        // 只按文件名记，会把导过 A 笔的这份，误判成 B 笔同名文件也导过，
        // 白白漏掉一份真实录音。
        val r = ImportedRegistry(ctx)
        r.markImported("AA:BB", "note20260901-1400")
        assertFalse("B 笔的同名文件不该被 A 笔的记录顶替",
                    r.isImported("CC:DD", "note20260901-1400"))
    }

    @Test fun `账本要跨进程重启还在——存的是文件不是内存`() {
        ImportedRegistry(ctx).markImported("AA:BB", "note1")
        // 用一个全新实例读，模拟进程重启后重新构造 ImportedRegistry
        assertTrue(ImportedRegistry(ctx).isImported("AA:BB", "note1"))
    }
}
