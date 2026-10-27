package app.memo.core.voice

import java.util.Locale

/**
 * Cuts a streamed answer into sentences for speech. Pure: no Android types.
 *
 * - [push] takes the next piece of text (a token or several) and returns the sentences that are
 *   now complete. A sentence ends at `.`, `!`, `?`, `…` or a line break, followed by whitespace
 *   (or by closing quotes and brackets that are followed by whitespace). The end of the buffer
 *   is never treated as a boundary, because the next piece may continue the sentence.
 * - Not boundaries: decimals (`3.14`), dots inside words (`e.g.`, `example.com`), common
 *   abbreviations in English, Polish and Turkish (`Dr.`, `np.`, `vs.`, `örn.`), single capital
 *   initials (`J. Kowalski`) and list numbers (`1.`, `12.`).
 * - A sentence longer than [maxChars] with no boundary is cut at the last space before the limit,
 *   so speech never waits for a very long sentence.
 * - [flush] returns the trailing text at the end of the answer.
 * - Output is ready for speech: emoji and pictographs are removed, markdown emphasis and heading
 *   marks are removed, whitespace is collapsed, and empty results are never returned.
 *
 * Not thread-safe. One answer is chunked by one caller at a time.
 */
class SentenceChunker(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    init {
        require(maxChars >= MIN_MAX_CHARS) { "maxChars must be at least $MIN_MAX_CHARS" }
    }

    private val pending = StringBuilder()

    /** Adds [text] and returns the sentences that are now complete, in order. */
    fun push(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        pending.append(text)
        val out = ArrayList<String>()
        while (true) {
            val end = findBoundary(pending)
            if (end < 0) {
                if (pending.length < maxChars) break
                val cut = forcedCut(pending)
                emit(pending.substring(0, cut), out)
                pending.delete(0, cut)
                continue
            }
            emit(pending.substring(0, end), out)
            pending.delete(0, end)
        }
        return out
    }

    /** Returns the text still pending at the end of the answer (at most one sentence), then clears the chunker. */
    fun flush(): List<String> {
        val out = ArrayList<String>(1)
        emit(pending.toString(), out)
        pending.clear()
        return out
    }

    /** Drops anything pending, for example after a cancel or barge-in. */
    fun reset() {
        pending.clear()
    }

    private fun emit(raw: String, out: MutableList<String>) {
        val cleaned = speechText(raw)
        if (cleaned.isNotEmpty()) out += cleaned
    }

    /** Index just after the first sentence end in [s], or -1 when no boundary is decidable yet. */
    private fun findBoundary(s: CharSequence): Int {
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '\n' -> return i + 1
                '…', '.', '!', '?' -> {
                    val after = skipClosers(s, i + 1)
                    if (after >= s.length) return -1 // the next piece may still extend this sentence
                    if (!s[after].isWhitespace()) {
                        i = after
                        continue
                    }
                    if (c == '.' && isNonBoundaryDot(s, i)) {
                        i = after
                        continue
                    }
                    return after
                }
            }
            i++
        }
        return -1
    }

    /** True when the dot at [dot] does not end a sentence: an abbreviation, an initial, a list number. */
    private fun isNonBoundaryDot(s: CharSequence, dot: Int): Boolean {
        var start = dot
        while (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '.')) start--
        val word = s.subSequence(start, dot).toString().trimStart('.')
        if (word.isEmpty()) return false
        if (word.all { it.isDigit() }) return word.length <= 2 // "1." list marker, not "2019."
        if (word.length == 1 && word[0].isUpperCase()) return true // initial: "J. Kowalski"
        return word.lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    private fun skipClosers(s: CharSequence, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in CLOSERS) i++
        return i
    }

    /**
     * Cut point for an over-long sentence: the last whitespace within [maxChars], or the limit
     * itself when there is none. Never splits a surrogate pair.
     */
    private fun forcedCut(s: CharSequence): Int {
        val limit = minOf(maxChars, s.length)
        for (i in limit - 1 downTo 1) {
            if (s[i].isWhitespace()) return i + 1
        }
        var cut = limit
        if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut--
        return if (cut <= 0) limit else cut
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 280
        private const val MIN_MAX_CHARS = 16

        private val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', ']', '}')

        /** Lower-case abbreviations whose dot does not end a sentence (en, pl, tr). */
        val ABBREVIATIONS: Set<String> = setOf(
            // English
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "approx", "inc", "ltd",
            "no", "fig", "vol", "dept", "co",
            // Polish
            "np", "tzn", "tj", "itd", "itp", "ok", "godz", "ul", "nr", "mgr", "inż", "wg", "tys", "zob", "al",
            "ds", "tzw", "pl", "r", "poz", "odp", "art", "hab",
            // Turkish
            "doç", "öğr", "sn", "bkz", "örn", "vb", "vs", "yy", "dk", "sy", "mh", "apt", "vd", "bl", "prof",
        )

        /** Emphasis and code marks: removed without a space, so "`code`." stays "code.". */
        private val EMPHASIS_MARKS = setOf('*', '`', '~')

        /** Heading and table marks: replaced by a space, so words stay apart. */
        private val BLOCK_MARKS = setOf('#', '|')

        /**
         * Speech-ready text: emoji and pictographs removed, markdown marks removed, whitespace
         * collapsed and trimmed. Pure and deterministic.
         */
        fun speechText(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                i += Character.charCount(cp)
                if (isEmojiLike(cp)) continue
                if (cp < 0x10000) {
                    val ch = cp.toChar()
                    if (ch in EMPHASIS_MARKS) continue
                    if (ch in BLOCK_MARKS) {
                        sb.append(' ')
                        continue
                    }
                }
                sb.appendCodePoint(cp)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        /** Approximate emoji test: pictographs, dingbats, symbols and joiners. */
        private fun isEmojiLike(cp: Int): Boolean = when {
            cp >= 0x1F000 -> true
            cp in 0x2300..0x23FF -> true
            cp in 0x2600..0x27BF -> true
            cp in 0x2B00..0x2BFF -> true
            cp == 0x200D || cp == 0x20E3 -> true
            cp in 0xFE00..0xFE0F -> true
            else -> false
        }
    }
}
