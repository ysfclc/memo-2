package app.memo.platform.android.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkSplitterTest {

    private fun run(thinking: Boolean, vararg chunks: String): List<ThinkSplitter.Piece> {
        val s = ThinkSplitter(thinking)
        val out = ArrayList<ThinkSplitter.Piece>()
        for (c in chunks) out += s.feed(c)
        out += s.finish()
        return out
    }

    /** Joins adjacent pieces of the same channel so assertions do not depend on chunking. */
    private fun merged(pieces: List<ThinkSplitter.Piece>): List<ThinkSplitter.Piece> {
        val out = ArrayList<ThinkSplitter.Piece>()
        for (p in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.thinking == p.thinking) {
                out[out.size - 1] = ThinkSplitter.Piece(p.thinking, last.text + p.text)
            } else {
                out += p
            }
        }
        return out
    }

    @Test
    fun noTagsIsAllAnswer() {
        val out = merged(run(true, "hello ", "world"))
        assertEquals(listOf(ThinkSplitter.Piece(false, "hello world")), out)
    }

    @Test
    fun tagsSplitAcrossChunkBoundariesAreRecognised() {
        val out = merged(run(true, "<th", "ink>reason", "</thi", "nk>answer"))
        assertEquals(
            listOf(
                ThinkSplitter.Piece(true, "reason"),
                ThinkSplitter.Piece(false, "answer"),
            ),
            out,
        )
    }

    @Test
    fun tagSplitOneCharacterAtATime() {
        val text = "<think>ab</think>cd"
        val out = merged(run(true, *text.map { it.toString() }.toTypedArray()))
        assertEquals(
            listOf(ThinkSplitter.Piece(true, "ab"), ThinkSplitter.Piece(false, "cd")),
            out,
        )
    }

    @Test
    fun falsePrefixIsReleasedOnceItCannotBeATag() {
        val out = merged(run(false, "a <th", "is is text"))
        assertEquals(listOf(ThinkSplitter.Piece(false, "a <this is text")), out)
    }

    @Test
    fun unclosedTagAtEndKeepsTextAsReasoning() {
        val out = merged(run(true, "<think>partial thought"))
        assertEquals(listOf(ThinkSplitter.Piece(true, "partial thought")), out)
    }

    @Test
    fun partialTagAtEndIsFlushedAsAnswerText() {
        val out = merged(run(true, "text <thi"))
        assertEquals(listOf(ThinkSplitter.Piece(false, "text <thi")), out)
    }

    @Test
    fun nestedMarkupInsideReasoningIsKeptAsReasoning() {
        val out = merged(run(true, "<think>a <b>c</b> <think> d</think>e"))
        assertEquals(
            listOf(
                ThinkSplitter.Piece(true, "a <b>c</b> <think> d"),
                ThinkSplitter.Piece(false, "e"),
            ),
            out,
        )
    }

    @Test
    fun strayCloseTagOutsideBlockIsAnswerText() {
        val out = merged(run(true, "x </think> y"))
        assertEquals(listOf(ThinkSplitter.Piece(false, "x </think> y")), out)
    }

    @Test
    fun reasoningIsDroppedWhenThinkingIsDisabled() {
        val out = merged(run(false, "<think>secret</think>visible"))
        assertEquals(listOf(ThinkSplitter.Piece(false, "visible")), out)
    }

    @Test
    fun multipleBlocksAlternate() {
        val out = merged(run(true, "<think>1</think>A<think>2</think>B"))
        assertEquals(
            listOf(
                ThinkSplitter.Piece(true, "1"),
                ThinkSplitter.Piece(false, "A"),
                ThinkSplitter.Piece(true, "2"),
                ThinkSplitter.Piece(false, "B"),
            ),
            out,
        )
    }

    @Test
    fun emptyChunksProduceNothing() {
        assertEquals(emptyList(), run(true, "", ""))
    }
}
