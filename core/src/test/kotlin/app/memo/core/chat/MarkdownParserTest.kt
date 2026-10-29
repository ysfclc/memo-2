package app.memo.core.chat

import app.memo.core.chat.MarkdownParser.Block
import app.memo.core.chat.MarkdownParser.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownParserTest {

    private val sample = """
        Intro with **bold**, *italic*, `code` and [docs](https://example.com/a).

        - first item
        - second **item**

        1. step one
        2. step two

        ```kotlin
        val x = 1
        println(x)
        ```

        Closing paragraph with a [bad](javascript:alert) link.
    """.trimIndent()

    @Test
    fun parsesBlocksInOrder() {
        val blocks = MarkdownParser.parse(sample)
        assertEquals(5, blocks.size)
        assertIs<Block.Paragraph>(blocks[0])
        assertIs<Block.ListBlock>(blocks[1])
        assertIs<Block.ListBlock>(blocks[2])
        assertIs<Block.Code>(blocks[3])
        assertIs<Block.Paragraph>(blocks[4])
    }

    @Test
    fun inlineSpansAreParsed() {
        val spans = (MarkdownParser.parse(sample)[0] as Block.Paragraph).spans
        assertTrue(spans.contains(Span.Bold("bold")))
        assertTrue(spans.contains(Span.Italic("italic")))
        assertTrue(spans.contains(Span.InlineCode("code")))
        assertTrue(spans.contains(Span.Link("docs", "https://example.com/a")))
    }

    @Test
    fun codeBlockKeepsLanguageAndBody() {
        val code = MarkdownParser.parse(sample)[3] as Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun unclosedFenceStaysOpenUntilEnd() {
        val blocks = MarkdownParser.parse("```py\nprint(1)\nprint(2)")
        assertEquals(listOf(Block.Code("py", "print(1)\nprint(2)", closed = false)), blocks)
    }

    @Test
    fun linksWithNonHttpTargetsStayLiteral() {
        val spans = MarkdownParser.inline("see [x](javascript:alert)")
        assertEquals(listOf<Span>(Span.Text("see [x](javascript:alert)")), spans)
    }

    @Test
    fun unclosedMarkersAreLiteral() {
        assertEquals(listOf<Span>(Span.Text("a **open")), MarkdownParser.inline("a **open"))
        assertEquals(listOf<Span>(Span.Text("tick `open")), MarkdownParser.inline("tick `open"))
        assertEquals(listOf<Span>(Span.Text("half [link](https://x")), MarkdownParser.inline("half [link](https://x"))
    }

    @Test
    fun unclosedLinkTargetWhileStreamingIsLiteralUntilClosed() {
        assertEquals(listOf<Span>(Span.Text("[go](https://ex")), MarkdownParser.inline("[go](https://ex"))
        assertEquals(listOf<Span>(Span.Link("go", "https://ex.com")), MarkdownParser.inline("[go](https://ex.com)"))
    }

    /**
     * Streaming invariant: for every prefix of the text, every block except the last one is
     * already final, so it equals the same block in the full parse.
     */
    @Test
    fun earlierBlocksNeverChangeAsChunksArrive() {
        val full = MarkdownParser.parse(sample)
        for (k in 0..sample.length) {
            val partial = MarkdownParser.parse(sample.substring(0, k))
            val settled = partial.dropLast(1)
            assertTrue(settled.size <= full.size, "prefix $k has more settled blocks than the full text")
            assertEquals(full.take(settled.size), settled, "settled blocks changed at prefix $k")
        }
    }

    @Test
    fun earlierSpansNeverBreakWhileChunkAppends() {
        val text = "Hello **world** and *more* text"
        var previous: List<Span>? = null
        for (k in 1..text.length) {
            val spans = MarkdownParser.inline(text.substring(0, k))
            // Every span already closed in an earlier prefix is still present, unchanged.
            previous?.filter { it !is Span.Text }?.forEach { assertTrue(it in spans, "span lost at $k: $it") }
            previous = spans
        }
    }

    @Test
    fun emptyTextHasNoBlocks() {
        assertEquals(emptyList(), MarkdownParser.parse(""))
    }
}
