package app.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.memo.core.chat.MarkdownParser
import app.memo.ui.theme.MemoTheme

/** Renders the supported Markdown blocks from a complete or still-streaming reply. */
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Paragraph -> MarkdownParagraph(block.spans)
                is MarkdownParser.Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
                    block.items.forEachIndexed { index, spans ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                color = MemoTheme.colors.accent,
                                style = MemoTheme.typography.body,
                            )
                            Spacer(Modifier.width(MemoTheme.spacing.space3))
                            MarkdownParagraph(spans, Modifier.weight(1f))
                        }
                    }
                }
                is MarkdownParser.Block.Code -> CodeBlock(block.language, block.code)
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(spans: List<MarkdownParser.Span>, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(spans) {
        buildAnnotatedString {
            spans.forEach { span ->
                val start = length
                val value = when (span) {
                    is MarkdownParser.Span.Text -> span.text
                    is MarkdownParser.Span.Bold -> span.text
                    is MarkdownParser.Span.Italic -> span.text
                    is MarkdownParser.Span.InlineCode -> span.text
                    is MarkdownParser.Span.Link -> span.label
                }
                append(value)
                when (span) {
                    is MarkdownParser.Span.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    is MarkdownParser.Span.Italic -> addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, length)
                    is MarkdownParser.Span.InlineCode -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length)
                    is MarkdownParser.Span.Link -> {
                        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, length)
                        addStringAnnotation("url", span.url, start, length)
                    }
                    is MarkdownParser.Span.Text -> Unit
                }
            }
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = MemoTheme.typography.body.copy(color = MemoTheme.colors.ink),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.item
                ?.takeIf { it.startsWith("https://") }
                ?.let { runCatching { uriHandler.openUri(it) } }
        },
    )
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MemoTheme.spacing.space3))
            .background(MemoTheme.colors.surfaceSunken),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "Code" }, modifier = Modifier.weight(1f), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", tint = MemoTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
            }
        }
        Text(code, style = MemoTheme.typography.mono, color = MemoTheme.colors.ink)
    }
}
