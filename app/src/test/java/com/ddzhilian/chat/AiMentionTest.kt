package com.ddzhilian.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMentionTest {
    private val branding = AiBranding(name = "点点助手", wakeWords = listOf("ai", "bot"))

    @Test
    fun parsesMentionWithAssistantName() {
        assertEquals("你好", parseAiMention("@点点助手 你好", branding))
        assertEquals("介绍下自己", parseAiMention("@点点助手：介绍下自己", branding))
        assertEquals("", parseAiMention("@点点助手", branding))
        assertEquals("多行问题", parseAiMention("  @点点助手  多行问题", branding))
    }

    @Test
    fun parsesWakeWordsCaseInsensitively() {
        assertEquals("今天天气", parseAiMention("@AI 今天天气", branding))
        assertEquals("来点灵感", parseAiMention("@bot，来点灵感", branding))
    }

    @Test
    fun ignoresNonMentionsAndLookAlikes() {
        assertNull(parseAiMention("你好 @点点助手", branding))
        assertNull(parseAiMention("@aix 你好", branding))
        assertNull(parseAiMention("@其他助手 你好", branding))
        assertNull(parseAiMention("普通消息", branding))
        assertNull(parseAiMention("", branding))
    }

    @Test
    fun stripsRichTextMarkupForNativeDisplay() {
        val html = "<p><strong>@张三</strong> 你好<br/>第二行 &amp; 更多</p>"
        assertEquals("@张三 你好\n第二行 & 更多", stripChatMarkup(html))
        assertEquals("普通文本", stripChatMarkup("普通文本"))
        assertTrue(stripChatMarkup("").isEmpty())
        assertEquals("1 < 2 & 3 > 0", stripChatMarkup("1 &lt; 2 &amp; 3 &gt; 0"))
    }

    @Test
    fun parsesBrandingPayload() {
        val payload = JSONObject()
            .put("aiName", "点点助手")
            .put("wakeWords", JSONArray().put("ai").put("bot"))
            .put("avatarUrl", "/api/ai/avatar?v=abcd1234")
            .put("memoryTurns", 3)

        val parsed = parseAiBranding(payload)
        assertEquals("点点助手", parsed.name)
        assertEquals(listOf("ai", "bot"), parsed.wakeWords)
        assertEquals("/api/ai/avatar?v=abcd1234", parsed.avatarUrl)
        assertEquals(3, parsed.memoryTurns)
    }

    @Test
    fun fallsBackToDefaultsForEmptyBranding() {
        val parsed = parseAiBranding(JSONObject())
        assertEquals(DEFAULT_AI_NAME, parsed.name)
        assertEquals(DEFAULT_AI_WAKE_WORDS, parsed.wakeWords)
        assertNull(parsed.avatarUrl)
    }
}
