package com.ddzhilian.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun plainTextPassesThroughUnchanged() {
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownRun("今天天气不错")))),
            parseMarkdown("今天天气不错"))
        assertTrue(parseMarkdown("   ").isEmpty())
    }

    @Test
    fun parsesInlineEmphasisAndCode() {
        val runs = parseMarkdownInline("这是 **粗体** 和 *斜体* 还有 `code`")
        assertEquals(listOf("这是 ", "粗体", " 和 ", "斜体", " 还有 ", "code"), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertTrue(runs[3].italic)
        assertTrue(runs[5].code)
    }

    @Test
    fun parsesBoldItalicAndStrike() {
        val runs = parseMarkdownInline("***都要*** ~~删掉~~")
        assertEquals(listOf("都要", " ", "删掉"), runs.map { it.text })
        assertTrue(runs[0].bold && runs[0].italic)
        assertTrue(runs[2].strike)
    }

    @Test
    fun keepsOrdinaryPunctuationLiteral() {
        // 乘号、snake_case、孤立星号都不能被当成强调吃字。
        assertEquals(listOf(MarkdownRun("2*3 等于 6")), parseMarkdownInline("2*3 等于 6"))
        assertEquals(listOf(MarkdownRun("user_name 变量")), parseMarkdownInline("user_name 变量"))
        assertEquals(listOf(MarkdownRun("a * b")), parseMarkdownInline("a * b"))
        assertEquals(listOf(MarkdownRun("****")), parseMarkdownInline("****"))
    }

    @Test
    fun honoursBackslashEscapes() {
        assertEquals(listOf(MarkdownRun("*不是斜体*")), parseMarkdownInline("\\*不是斜体\\*"))
    }

    @Test
    fun keepsCodeSpanContentVerbatim() {
        val runs = parseMarkdownInline("运行 `**不该解析**` 即可")
        assertEquals(listOf("运行 ", "**不该解析**", " 即可"), runs.map { it.text })
        assertTrue(runs[1].code)
    }

    @Test
    fun parsesLinksAndBareUrls() {
        val runs = parseMarkdownInline("见 [文档](https://example.com/a) 或 https://example.com/b。")
        assertEquals("https://example.com/a", runs.first { it.text == "文档" }.link)
        val bare = runs.first { it.text.startsWith("https://example.com/b") }
        assertEquals("https://example.com/b", bare.link)
        // 句末标点不属于链接，被留在正文里。
        assertEquals("。", runs.last().text)
        assertNull(runs.last().link)
    }

    @Test
    fun parsesBlockStructure() {
        val blocks = parseMarkdown("## 标题\n\n正文一行\n第二行\n\n- 项目一\n- 项目二\n\n1. 第一步\n2. 第二步\n\n> 引用一行\n\n---")
        assertEquals(6, blocks.size)
        assertEquals(MarkdownBlock.Heading(2, listOf(MarkdownRun("标题"))), blocks[0])
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownRun("正文一行\n第二行"))), blocks[1])
        assertEquals(MarkdownBlock.BulletList(listOf(listOf(MarkdownRun("项目一")), listOf(MarkdownRun("项目二")))), blocks[2])
        assertEquals(MarkdownBlock.OrderedList(1, listOf(listOf(MarkdownRun("第一步")), listOf(MarkdownRun("第二步")))), blocks[3])
        assertEquals(MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(listOf(MarkdownRun("引用一行"))))), blocks[4])
        assertEquals(MarkdownBlock.Divider, blocks[5])
    }

    @Test
    fun keepsOrderedListStartNumber() {
        val block = parseMarkdown("3. 第三步\n4. 第四步").single() as MarkdownBlock.OrderedList
        assertEquals(3, block.start)
        assertEquals(2, block.items.size)
    }

    @Test
    fun separatesDividerFromList() {
        val blocks = parseMarkdown("- 一\n\n---\n\n* 二")
        assertEquals(MarkdownBlock.BulletList(listOf(listOf(MarkdownRun("一")))), blocks[0])
        assertEquals(MarkdownBlock.Divider, blocks[1])
        assertEquals(MarkdownBlock.BulletList(listOf(listOf(MarkdownRun("二")))), blocks[2])
    }

    @Test
    fun parsesFencedCodeBlockVerbatim() {
        val blocks = parseMarkdown("```kotlin\nval a = **1**\n  indented\n```")
        assertEquals(MarkdownBlock.Code("kotlin", "val a = **1**\n  indented"), blocks.single())
    }

    @Test
    fun keepsUnclosedFenceAsCodeBlock() {
        // 流式回复被截断时收尾围栏会缺失，此时仍按代码块显示，不能把内容当正文吞掉。
        val blocks = parseMarkdown("说明\n```\n未闭合的代码")
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownRun("说明"))), blocks[0])
        assertEquals(MarkdownBlock.Code("", "未闭合的代码"), blocks[1])
    }

    @Test
    fun joinsLazyContinuationIntoListItem() {
        val block = parseMarkdown("- 第一行\n  第二行\n- 下一项").single() as MarkdownBlock.BulletList
        assertEquals(listOf("第一行\n第二行", "下一项"), block.items.map { runs -> runs.joinToString("") { it.text } })
    }

    @Test
    fun rendersServerRichTextReply() {
        // 服务端把 AI 回复存成「HTML 外壳 + Markdown 源文本」，原生端先 stripChatMarkup 再解析。
        val raw = "<p><strong>@张三</strong> 你好</p><p>## 小标题</p><p>- 一<br />- 二</p>"
        val blocks = parseMarkdown(stripChatMarkup(raw))
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownRun("@张三 你好"))), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, listOf(MarkdownRun("小标题"))), blocks[1])
        assertEquals(MarkdownBlock.BulletList(listOf(listOf(MarkdownRun("一")), listOf(MarkdownRun("二")))), blocks[2])
    }

    @Test
    fun plainChatTextFlattensMarkup() {
        val raw = "<p><strong>@张三</strong> 你好</p><p>## 小标题</p><p>- 一<br />- 二</p>"
        assertEquals("@张三 你好\n小标题\n• 一\n• 二", plainChatText(raw))
        assertEquals("普通文本", plainChatText("普通文本"))
        assertEquals("", plainChatText(""))
        assertEquals("行内 粗体 与 代码", plainChatText("行内 **粗体** 与 `代码`"))
    }

    @Test
    fun plainChatTextKeepsUnsupportedSyntaxReadable() {
        // 未支持的语法（表格）按原文保留，绝不丢字。
        assertEquals("| a | b |", plainChatText("| a | b |"))
    }
}
