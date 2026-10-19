package com.ddzhilian.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天气泡里的 Markdown 富文本。
 *
 * 只画 [parseMarkdown] 认得的块，未支持的语法以原始文字留在段落里。颜色一律由调用方给出：
 * 自己的气泡是深色背景 + onPrimary 文字，对方是浅色背景 + onSurface，所以这里不读主题色，
 * 只用正文色的不同透明度区分代码块、引用和分隔线。
 */
@Composable
internal fun MarkdownMessage(
    raw: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 24.sp,
) {
    val blocks = remember(raw) { parseMarkdown(stripChatMarkup(raw)) }
    val base = TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight)
    if (blocks.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MarkdownBlockView(block, base, color) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, base: TextStyle, color: Color) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(block.inlines.annotated(color), style = base)

        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> 21.sp
                2 -> 19.sp
                else -> 17.sp
            }
            Text(
                block.inlines.annotated(color),
                style = base.copy(fontSize = size, lineHeight = (size.value * 1.4f).sp, fontWeight = FontWeight.SemiBold),
            )
        }

        is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item ->
                Row {
                    Text("•", style = base, color = color.copy(alpha = .75f), modifier = Modifier.padding(end = 8.dp))
                    // fill = false：列表项短时气泡跟随文字宽度，长句仍按气泡宽度换行。
                    Text(item.annotated(color), style = base, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        is MarkdownBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEachIndexed { offset, item ->
                Row {
                    Text(
                        (block.start + offset).toString() + ".",
                        style = base,
                        color = color.copy(alpha = .75f),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(item.annotated(color), style = base, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        is MarkdownBlock.Quote -> Column(
            Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .08f)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            block.blocks.forEach { nested -> MarkdownBlockView(nested, base, color) }
        }

        is MarkdownBlock.Code -> Column(
            // 代码不折行：整块横向滚动，保留缩进和行宽。
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .10f))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    style = base.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = color.copy(alpha = .65f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(block.code, style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp))
        }

        MarkdownBlock.Divider -> Box(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(color.copy(alpha = .16f)),
        )
    }
}

private fun List<MarkdownRun>.annotated(color: Color): AnnotatedString = buildAnnotatedString {
    forEach { run ->
        val start = length
        append(run.text)
        val decorations = buildList {
            if (run.strike) add(TextDecoration.LineThrough)
            if (run.link != null) add(TextDecoration.Underline)
        }
        if (run.bold || run.italic || run.code || decorations.isNotEmpty()) {
            addStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) color.copy(alpha = .16f) else Color.Unspecified,
                    textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
                ),
                start,
                length,
            )
        }
        // 链接只加下划线、不换颜色：自己的气泡（primary 底）与对方气泡（surface 底）都保持可读。
        run.link?.let { url -> addLink(LinkAnnotation.Url(url), start, length) }
    }
}
