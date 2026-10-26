package app.memo.platform.android.llm

/**
 * Splits streamed model text into answer and `<think>…</think>` reasoning.
 *
 * Tags may be split across chunks: a chunk that ends in a proper prefix of
 * the tag it is looking for is held back until the next chunk decides it.
 * Only the exact tags `<think>` and `</think>` are recognised. Text between
 * them is reasoning, including any other markup; a second `<think>` inside a
 * reasoning block is ordinary reasoning text, not a nested block. A stray
 * `</think>` outside a block is answer text.
 *
 * When [thinkingEnabled] is false, reasoning text is dropped and never emitted.
 * [finish] flushes held-back text in the channel it belongs to. An unclosed
 * `<think>` at the end of the stream therefore keeps its text as reasoning.
 *
 * Not thread-safe: use one instance per generation.
 */
class ThinkSplitter(private val thinkingEnabled: Boolean) {

    /** One routed piece of text. [thinking] is true for reasoning text. */
    data class Piece(val thinking: Boolean, val text: String)

    private var inThink = false
    private var held = ""

    /** Feeds one streamed chunk and returns the pieces it completes. */
    fun feed(chunk: String): List<Piece> {
        if (chunk.isEmpty()) return emptyList()
        val out = ArrayList<Piece>()
        var buf = held + chunk
        held = ""
        while (buf.isNotEmpty()) {
            val tag = if (inThink) CLOSE else OPEN
            val idx = buf.indexOf(tag)
            if (idx >= 0) {
                emit(out, buf.substring(0, idx))
                buf = buf.substring(idx + tag.length)
                inThink = !inThink
                continue
            }
            val keep = partialTagSuffix(buf, tag)
            emit(out, buf.substring(0, buf.length - keep))
            held = buf.substring(buf.length - keep)
            buf = ""
        }
        return out
    }

    /** Flushes any text held back at the end of the stream. The splitter is then spent. */
    fun finish(): List<Piece> {
        val out = ArrayList<Piece>()
        emit(out, held)
        held = ""
        return out
    }

    private fun emit(out: MutableList<Piece>, text: String) {
        if (text.isEmpty()) return
        if (inThink) {
            if (thinkingEnabled) out.add(Piece(thinking = true, text = text))
        } else {
            out.add(Piece(thinking = false, text = text))
        }
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"

        /** Length of the longest suffix of [buf] that is a proper prefix of [tag]. */
        fun partialTagSuffix(buf: String, tag: String): Int {
            val max = minOf(buf.length, tag.length - 1)
            for (k in max downTo 1) {
                if (buf.endsWith(tag.substring(0, k))) return k
            }
            return 0
        }
    }
}
