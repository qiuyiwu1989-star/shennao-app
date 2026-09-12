package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/** 应用内装新版：校验的判据。核不上就不装，这是唯一一道防线。 */
class InstallerTest {
    private val good = "2b1e0c02a0e733338d96344295c312c06a8721b3c35b8267c6e301582c45b3b9"

    @Test fun `清单里的占位符不算校验值`() {
        assertFalse(Installer.Verify.usable("x"))
        assertFalse(Installer.Verify.usable(""))
        assertTrue(Installer.Verify.usable(good))
    }

    @Test fun `大小写不敏感，差一位就不装`() {
        assertTrue(Installer.Verify.matches(good, good.uppercase()))
        assertFalse(Installer.Verify.matches(good, good.dropLast(1) + "a"))
        assertFalse("没有校验值时绝不能算通过", Installer.Verify.matches("x", "x"))
    }

    @Test fun `进度那一行`() {
        assertEquals("下载中 50% · 5.0 MB", Installer.progressLine(5 * 1048576L, 10 * 1048576L))
        assertEquals("下载中 · 1.0 MB", Installer.progressLine(1048576L, 0))
    }

    @Test fun `清旧包：不高于当前版本的删，更新的留，认不出的删`() {
        val names = listOf("shennao-4.6.2.apk", "shennao-4.6.3.apk", "shennao-4.7.0.apk", "shennao-4.10.0.apk", "junk.apk")
        val stale = Installer.staleVersions(names, "4.6.3")
        assertEquals(listOf("shennao-4.6.2.apk", "shennao-4.6.3.apk", "junk.apk"), stale)
    }
}
