package com.qiuyiwu.shennao.record

import org.junit.Assert.*
import org.junit.Test

/*
 * 分享进来的三条纯判据。它们决定了「服务端按什么容器解」和「会不会重复」，
 * 错了都是静默的：解出垃圾不报错、重复了用户自己去删。
 */
class ShareInTest {

    @Test fun `文件名后缀优先于分享方给的 mime`() {
        assertEquals("mp3", ShareIn.extOf("客户回访.mp3", "application/octet-stream"))
        assertEquals("wav", ShareIn.extOf("a.WAV", null))
    }

    @Test fun `没有可信后缀时看 mime`() {
        assertEquals("mp3", ShareIn.extOf("录音", "audio/mpeg"))
        assertEquals("m4a", ShareIn.extOf(null, "audio/x-m4a"))
        assertEquals("ogg", ShareIn.extOf("x", "audio/opus"))
        assertEquals("webm", ShareIn.extOf("x", "video/webm"))
    }

    @Test fun `都不知道就当 m4a——手机上最常见的容器`() {
        assertEquals("m4a", ShareIn.extOf(null, null))
        assertEquals("m4a", ShareIn.extOf("怪名字.xyz123", "application/octet-stream"))
    }

    @Test fun `每种后缀都有对应的 mimeType，不会落到默认的 aac 上`() {
        // Vault.kt 注释：报错了服务端会按别的格式去解，解出垃圾而且不报错
        for (ext in listOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "webm", "amr", "mp4")) {
            val m = Segment(0, 0, 1000, Segment.State.SEALED, ext = ext).mimeType
            assertNotEquals("$ext 不该落到默认的 audio/aac", "audio/aac", m)
        }
    }

    @Test fun `标题去掉后缀，太长截断，没名字给一个能认出来源的`() {
        assertEquals("客户回访 0904", ShareIn.titleOf("客户回访 0904.m4a"))
        assertEquals("分享进来的录音", ShareIn.titleOf(null))
        assertEquals("分享进来的录音", ShareIn.titleOf("   "))
        assertEquals(80, ShareIn.titleOf("x".repeat(200) + ".mp3").length)
    }

    /** 同一个文件分享两次，深脑那边只该有一条。幂等键由内容决定，不用 UUID。 */
    @Test fun `幂等键由内容决定，且和别的来源不共用前缀`() {
        val a = ShareIn.clientRequestId("abcdef0123456789abcdef0123456789")
        assertTrue(a.startsWith("share-"))
        assertEquals(a, ShareIn.clientRequestId("abcdef0123456789abcdef0123456789"))
        assertFalse(a.startsWith("ble-"))
        assertTrue("长度要稳定，服务端幂等键上限 200", a.length < 40)
    }
}
