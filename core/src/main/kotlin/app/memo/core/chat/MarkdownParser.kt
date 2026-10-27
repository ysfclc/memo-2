package app.memo.core.chat

/**
 * Markdown subset for chat bubbles: paragraphs, bullet and numbered lists, fenced code blocks,
 * and inline bold, italic, inline code and http(s) links.
 *
 * Parsing is a pure function of the text, and it is stable under streaming: every block except
 * the last one is final for any longer text that starts with the same characters. An unclosed
 * marker (`**`, a backtick, a link, or a fence) is shown as literal text, or as an open code
 * block, until its closing part arrives. A link target is kept only when it starts with
 * http:// or https://; anything else stays literal.
 */
object MarkdownParser {

    sealed interface Block {
        data class Paragraph(val spans: List<Span>) : Block
        data class ListBlock(val ordered: Boolean, val items: List<List<Span>>) : Block
        /** [closed] is false while the fence is still streaming. */
        data class Code(val language: String, val code: String, val closed: Boolean) : Block
    }

    sealed interface Span {
        data class Text(val text: String) : Span
        data class Bold(val text: String) : Span
        data class Italic(val text: String) : Span
        data class InlineCode(val text: String) : Span
        data class Link(val label: String, val url: String) : Span
    }

    fun parse(text: String): List<Block> {
        val all = text.split('\n')
        // A bare marker at the very end ("-", "*", "2", "2.") may still become a list item. It is
        // held back until its text arrives, so a list that is still streaming is never split.
        val lines = if (all.isNotEmpty() && PENDING_MARKER.matches(all.last())) all.dropLast(1) else all
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val body = mutableListOf<String>()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        if (lines[j].trim() == "```") {
                            closed = true
                            break
                        }
                        body += lines[j]
                        j++
                    }
                    blocks += Block.Code(language, body.joinToString("\n"), closed)
                    i = if (closed) j + 1 else lines.size
                }
                line.isBlank() -> i++
                listMarker(line) != null -> {
                    val firstOrdered = listMarker(line)!!.ordered
                    val items = mutableListOf<List<Span>>()
                    while (i < lines.size) {
                        val marker = listMarker(lines[i]) ?: break
                        if (marker.ordered != firstOrdered) break
                        items += inline(marker.content)
                        i++
                    }
                    blocks += Block.ListBlock(firstOrdered, items)
                }
                else -> {
                    val body = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i]
                        if (l.isBlank() || l.trimStart().startsWith("```") || listMarker(l) != null) break
                        body += l
                        i++
                    }
                    blocks += Block.Paragraph(inline(body.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    private class Marker(val ordered: Boolean, val content: String)

    private val PENDING_MARKER = Regex("^\\s*([-*+]|\\d+\\.?)$")
    private val UNORDERED = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*\\d+\\.\\s+(.*)$")

    private fun listMarker(line: String): Marker? {
        UNORDERED.matchEntire(line)?.let { return Marker(false, it.groupValues[1]) }
        ORDERED.matchEntire(line)?.let { return Marker(true, it.groupValues[1]) }
        return null
    }

    /** Inline spans for one block. Unclosed markers are literal text. */
    fun inline(src: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                spans += Span.Text(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < src.length) {
            val consumed: Int = when (src[i]) {
                '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush(); spans += Span.InlineCode(src.substring(i + 1, end)); end + 1 - i
                    } else 0
                }
                '*' -> when {
                    src.startsWith("**", i) -> {
                        val end = src.indexOf("**", i + 2)
                        if (end > i + 2) {
                            flush(); spans += Span.Bold(src.substring(i + 2, end)); end + 2 - i
                        } else {
                            // Unclosed: both stars stay literal, so the second one cannot open an italic.
                            plain.append("**"); 2
                        }
                    }
                    i + 1 < src.length && src[i + 1] != ' ' -> {
                        val end = src.indexOf('*', i + 1)
                        if (end > i + 1 && src[end - 1] != ' ' && src[end - 1] != '*') {
                            flush(); spans += Span.Italic(src.substring(i + 1, end)); end + 1 - i
                        } else 0
                    }
                    else -> 0
                }
                '[' -> link(src, i)?.let { link ->
                    flush(); spans += Span.Link(link.label, link.url); link.length
                } ?: 0
                else -> 0
            }
            if (consumed > 0) {
                i += consumed
            } else {
                plain.append(src[i])
                i++
            }
        }
        flush()
        return spans
    }

    private class LinkMatch(val label: String, val url: String, val length: Int)

    /** `[label](https://...)` starting at [start], or null. */
    private fun link(src: String, start: Int): LinkMatch? {
        val close = src.indexOf(']', start + 1)
        if (close <= start + 1 || close + 1 >= src.length || src[close + 1] != '(') return null
        val end = src.indexOf(')', close + 2)
        if (end < 0) return null
        val url = src.substring(close + 2, end)
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.any { it.isWhitespace() }) return null
        return LinkMatch(src.substring(start + 1, close), url, end + 1 - start)
    }
}
