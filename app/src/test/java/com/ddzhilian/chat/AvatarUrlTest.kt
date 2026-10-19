package com.ddzhilian.chat

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarUrlTest {
    private val base = "https://ws.ddzhilian.com".toHttpUrl()

    @Test
    fun resolvesServerRelativeAvatarPath() {
        // 线上 branding 实际返回的就是这种相对路径：/api/ai/avatar?v=b3537b39。
        // 之前直接把它交给 OkHttp，会在建 Request 时抛异常，头像永远退回「AI」圆标。
        assertEquals(
            "https://ws.ddzhilian.com/api/ai/avatar?v=b3537b39",
            resolveAvatarUrl("/api/ai/avatar?v=b3537b39", base),
        )
    }

    @Test
    fun keepsAbsoluteUrlsUntouched() {
        assertEquals("https://cdn.example.com/ai.gif", resolveAvatarUrl("https://cdn.example.com/ai.gif", base))
        assertEquals(
            "http://10.0.2.2:8789/api/ai/avatar",
            resolveAvatarUrl("http://10.0.2.2:8789/api/ai/avatar", base),
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("https://ws.ddzhilian.com/api/ai/avatar", resolveAvatarUrl("  /api/ai/avatar  ", base))
    }

    @Test
    fun givesUpWithoutBaseOrPath() {
        assertNull(resolveAvatarUrl("/api/ai/avatar", null))
        assertNull(resolveAvatarUrl("", base))
        assertNull(resolveAvatarUrl("   ", base))
        assertNull(resolveAvatarUrl(null, base))
    }
}
