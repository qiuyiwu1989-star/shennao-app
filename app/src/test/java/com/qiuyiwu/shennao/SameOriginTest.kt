package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/**
 * WebView 里装着一份完整的登录 cookie，所以「什么算自己家」这条判断
 * 是这一屏唯一会被人利用的地方。
 */
class SameOriginTest {
    private val base = "https://shennao.zaowuyun.com"

    @Test fun `自己家的页面留在里面`() {
        assertTrue(sameOrigin("$base/zh/transcript/abc", base))
        assertTrue(sameOrigin("$base/api/mobile/web-open?t=x", base))
    }

    @Test fun `前缀匹配挡不住的那一类`() {
        // 转写里会有别人贴的链接。用 startsWith 判同源的话，
        // 这个域名会被判成自己家，然后带着 cookie 打开。
        assertFalse(sameOrigin("https://shennao.zaowuyun.com.evil.com/x", base))
        assertFalse(sameOrigin("https://shennao.zaowuyun.com@evil.com/x", base))
    }

    @Test fun `换协议也不算同源`() {
        // http 上的 cookie 会被中间人读走
        assertFalse(sameOrigin("http://shennao.zaowuyun.com/zh", base))
    }

    @Test fun `外站一律出去`() {
        assertFalse(sameOrigin("https://example.com/", base))
        assertFalse(sameOrigin("javascript:alert(1)", base))
        assertFalse(sameOrigin("file:///etc/passwd", base))
    }

    @Test fun `看不懂的地址按外站处理，不按自己家`() {
        // 拿不准时的默认值决定了一次解析失败是「多点一次浏览器」
        // 还是「把会话交出去」
        assertFalse(sameOrigin("", base))
        assertFalse(sameOrigin("::::", base))
    }
}
