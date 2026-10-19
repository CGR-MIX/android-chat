package com.ddzhilian.chat

/**
 * 聊天消息里的 Markdown 子集解析。
 *
 * 服务端保存 AI 回复时只做 HTML 转义（`server/src/index.ts` 的 `buildAiBotReplyText` / `plainTextToRichText`），
 * 所以原生端在 [stripChatMarkup] 之后拿到的仍是 `**粗体**`、`### 标题`、围栏代码块这样的原始标记。
 * 这里解析聊天里真的会出现的语法：标题、段落、有序 / 无序列表、引用、围栏代码块、分隔线，
 * 以及行内粗体、斜体、删除线、行内代码、链接和裸链接。
 *
 * 原则是「看不懂就按原文显示」：表格、图片、HTML 等未支持的语法原样留在段落里，任何解析分支都不吞字。
 * 解析层不依赖 Compose，可以直接用 JVM 单元测试覆盖。
 */
data class MarkdownRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

sealed interface MarkdownBlock {
    data class Paragraph(val inlines: List<MarkdownRun>) : MarkdownBlock
    data class Heading(val level: Int, val inlines: List<MarkdownRun>) : MarkdownBlock
    data class BulletList(val items: List<List<MarkdownRun>>) : MarkdownBlock
    data class OrderedList(val start: Int, val items: List<List<MarkdownRun>>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private val markdownFence = Regex("""^\s{0,3}(`{3,}|~{3,})\s*([^`~\s]*)\s*$""")
private val markdownHeading = Regex("""^\s{0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
private val markdownDivider = Regex("""^\s{0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
private val markdownBullet = Regex("""^\s{0,6}[-*+][ \t]+(.*)$""")
private val markdownOrdered = Regex("""^\s{0,6}(\d{1,9})[.)][ \t]+(.*)$""")
private val markdownQuote = Regex("""^\s{0,3}>[ \t]?(.*)$""")

/** 行内强调的递归上限，挡住畸形输入（如上万个 `*`）把栈打爆。 */
private const val MAX_INLINE_DEPTH = 8
private const val ESCAPABLE = "\\`*_{}[]()#+-.!>~|"

fun parseMarkdown(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = paragraph.joinToString("\n").trim()
        paragraph.clear()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(parseMarkdownInline(text))
    }

    while (index < lines.size) {
        val line = lines[index]

        val fence = markdownFence.matchEntire(line)
        if (fence != null) {
            flushParagraph()
            val marker = fence.groupValues[1]
            val code = mutableListOf<String>()
            index++
            // 围栏内容原样保留：不 trim、不解析行内标记。
            while (index < lines.size && !isFenceEnd(lines[index], marker)) {
                code += lines[index]
                index++
            }
            // 收尾围栏缺失时（流式回复截断）也照样成块，避免整段退化成普通文本。
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(fence.groupValues[2], code.joinToString("\n"))
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        val heading = markdownHeading.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            val level = heading.groupValues[1].length
            blocks += MarkdownBlock.Heading(level, parseMarkdownInline(heading.groupValues[2].trim()))
            index++
            continue
        }

        if (markdownDivider.matches(line)) {
            flushParagraph()
            blocks += MarkdownBlock.Divider
            index++
            continue
        }

        val quote = markdownQuote.matchEntire(line)
        if (quote != null) {
            flushParagraph()
            val inner = mutableListOf<String>()
            while (index < lines.size) {
                val nested = markdownQuote.matchEntire(lines[index]) ?: break
                inner += nested.groupValues[1]
                index++
            }
            blocks += MarkdownBlock.Quote(parseMarkdown(inner.joinToString("\n")))
            continue
        }

        val bullet = markdownBullet.matchEntire(line)
        val ordered = if (bullet == null) markdownOrdered.matchEntire(line) else null
        if (bullet != null || ordered != null) {
            flushParagraph()
            val numbered = ordered != null
            val start = ordered?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val items = mutableListOf(ordered?.groupValues?.get(2) ?: bullet?.groupValues?.get(1).orEmpty())
            index++
            while (index < lines.size) {
                val next = lines[index]
                val nextBullet = markdownBullet.matchEntire(next)
                val nextOrdered = markdownOrdered.matchEntire(next)
                if (!numbered && nextBullet != null) {
                    items += nextBullet.groupValues[1]
                    index++
                    continue
                }
                if (numbered && nextOrdered != null) {
                    items += nextOrdered.groupValues[2]
                    index++
                    continue
                }
                // 列表项的懒续行：普通文本归到上一项；遇到任何新块结构就收尾，交给外层重新判断。
                val continuesItem = next.isNotBlank() && nextBullet == null && nextOrdered == null &&
                    !markdownQuote.matches(next) && !markdownDivider.matches(next) && !markdownHeading.matches(next) &&
                    markdownFence.matchEntire(next) == null
                if (continuesItem) {
                    items[items.lastIndex] = items.last().trimEnd() + "\n" + next.trim()
                    index++
                    continue
                }
                break
            }
            val parsedItems = items.map { parseMarkdownInline(it) }.filter { runs -> runs.any { it.text.isNotBlank() } }
            if (parsedItems.isNotEmpty()) {
                blocks += if (numbered) MarkdownBlock.OrderedList(start, parsedItems) else MarkdownBlock.BulletList(parsedItems)
            }
            continue
        }

        paragraph += line
        index++
    }

    flushParagraph()
    return blocks
}

/** 行内语法：`**粗体**`、`*斜体*`、`***粗斜体***`、`~~删除线~~`、`` `代码` ``、`[文字](链接)`、裸链接与反斜杠转义。 */
fun parseMarkdownInline(raw: String): List<MarkdownRun> = parseInline(raw, 0)

private fun parseInline(text: String, depth: Int): List<MarkdownRun> {
    if (text.isEmpty()) return emptyList()
    if (depth > MAX_INLINE_DEPTH) return listOf(MarkdownRun(text))

    val runs = mutableListOf<MarkdownRun>()
    val buffer = StringBuilder()
    var index = 0

    fun flushText() {
        if (buffer.isNotEmpty()) {
            runs += MarkdownRun(buffer.toString())
            buffer.clear()
        }
    }

    while (index < text.length) {
        val char = text[index]

        if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
            buffer.append(text[index + 1])
            index += 2
            continue
        }

        if (char == '`') {
            val close = text.indexOf('`', index + 1)
            if (close > index + 1) {
                flushText()
                runs += MarkdownRun(text.substring(index + 1, close), code = true)
                index = close + 1
                continue
            }
            buffer.append(char)
            index++
            continue
        }

        if (char == '[') {
            val labelEnd = text.indexOf(']', index + 1)
            if (labelEnd > index + 1 && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                val urlEnd = text.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd + 2) {
                    val url = text.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ')
                    if (url.isNotEmpty()) {
                        flushText()
                        runs += parseInline(text.substring(index + 1, labelEnd), depth + 1).map { it.copy(link = url) }
                        index = urlEnd + 1
                        continue
                    }
                }
            }
            buffer.append(char)
            index++
            continue
        }

        if (text.startsWith("http://", index, ignoreCase = true) || text.startsWith("https://", index, ignoreCase = true)) {
            var end = index + 4
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"`") end++
            // 句末标点不属于链接，留给后面的文本。
            while (end > index && text[end - 1] in ".,;:!?'\"、。，；：！？") end--
            if (end > index) {
                flushText()
                val url = text.substring(index, end)
                runs += MarkdownRun(url, link = url)
                index = end
                continue
            }
        }

        val triple = when {
            text.startsWith("***", index) -> "***"
            text.startsWith("___", index) && isWordStart(text, index) -> "___"
            else -> null
        }
        if (triple != null) {
            val close = findClosing(text, index, triple, 3)
            if (close != null) {
                flushText()
                runs += parseInline(text.substring(index + 3, close), depth + 1).map { it.copy(bold = true, italic = true) }
                index = close + 3
                continue
            }
        }

        val strong = when {
            text.startsWith("**", index) -> "**"
            text.startsWith("__", index) && isWordStart(text, index) -> "__"
            else -> null
        }
        if (strong != null) {
            val close = findClosing(text, index, strong, 2)
            if (close != null) {
                flushText()
                runs += parseInline(text.substring(index + 2, close), depth + 1).map { it.copy(bold = true) }
                index = close + 2
                continue
            }
        }

        if (text.startsWith("~~", index)) {
            val close = findClosing(text, index, "~~", 2)
            if (close != null) {
                flushText()
                runs += parseInline(text.substring(index + 2, close), depth + 1).map { it.copy(strike = true) }
                index = close + 2
                continue
            }
        }

        // 单个 `*` / `_` 才是斜体；`_` 还要求出现在词首，避免 snake_case 被吃掉。
        if (char == '*' || (char == '_' && isWordStart(text, index))) {
            val close = findClosing(text, index, char.toString(), 1)
            if (close != null) {
                val inner = text.substring(index + 1, close)
                if (inner.isNotBlank() && !inner.first().isWhitespace() && !inner.last().isWhitespace()) {
                    flushText()
                    runs += parseInline(inner, depth + 1).map { it.copy(italic = true) }
                    index = close + 1
                    continue
                }
            }
        }

        buffer.append(char)
        index++
    }

    flushText()
    return runs
}

/** 找到独占的闭合标记位置；内容为空、首尾是空格或闭合处仍属于更长标记时继续往后找。 */
private fun findClosing(text: String, start: Int, delimiter: String, length: Int): Int? {
    var search = start + length
    while (search <= text.length - length) {
        val found = text.indexOf(delimiter, search)
        if (found < 0) return null
        val inner = text.substring(start + length, found)
        val after = found + length
        val afterOk = when {
            after >= text.length -> true
            delimiter[0] == '*' -> text[after] != '*'
            delimiter[0] == '_' -> !text[after].isLetterOrDigit() && text[after] != '_'
            else -> true
        }
        // 内容首尾不能是同一个标记字符：否则 `****`、`~~` 之类的字面字符会被吃成强调。
        val innerOk = inner.isNotEmpty() && !inner.first().isWhitespace() && !inner.last().isWhitespace() &&
            inner.first() != delimiter[0] && inner.last() != delimiter[0]
        if (innerOk && afterOk) return found
        search = found + length
    }
    return null
}

private fun isWordStart(text: String, index: Int): Boolean = index == 0 || !text[index - 1].isLetterOrDigit()

private fun isFenceEnd(line: String, marker: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= marker.length && trimmed.all { it == marker[0] }
}

/** 单行 / 多行预览用的纯文本：先去 HTML 外壳，再把 Markdown 语法收成可读文字。 */
fun plainChatText(raw: String): String = markdownToPlainText(parseMarkdown(stripChatMarkup(raw)))

private fun markdownToPlainText(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n") { block ->
    when (block) {
        is MarkdownBlock.Paragraph -> inlineText(block.inlines)
        is MarkdownBlock.Heading -> inlineText(block.inlines)
        is MarkdownBlock.BulletList -> block.items.joinToString("\n") { item -> "• " + inlineText(item) }
        is MarkdownBlock.OrderedList -> block.items.mapIndexed { offset, item -> "${block.start + offset}. " + inlineText(item) }
            .joinToString("\n")
        is MarkdownBlock.Quote -> markdownToPlainText(block.blocks).lines()
            .joinToString("\n") { line -> if (line.isBlank()) line else "> $line" }
        is MarkdownBlock.Code -> block.code
        MarkdownBlock.Divider -> ""
    }
}.trim()

private fun inlineText(runs: List<MarkdownRun>): String = runs.joinToString("") { it.text }
