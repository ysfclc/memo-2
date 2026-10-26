package app.memo.core.plan

import app.memo.core.Limits

/**
 * Shared low-level scan helpers for model output (proposals, frontier
 * returns). Deliberately boring: no regex tricks that fail on awkward
 * frontier formatting; every scan is bounded by Limits.
 */
object Parsing {

    /** Collapse whitespace runs to single spaces; used for verbatim comparisons. */
    fun normalizeWhitespace(text: String): String = text.replace(WHITESPACE, " ")

    /**
     * First balanced `{ ... }` block, string- and escape-aware, searched
     * inside the first [limit] characters. Returns the substring or null.
     */
    fun locateFirstJsonObject(text: String, limit: Int = Limits.MAX_OUTPUT_CHARS): String? {
        if (text.isEmpty()) return null
        val searchEnd = minOf(text.length, limit)
        var start = -1
        for (i in 0 until searchEnd) {
            if (text[i] == '{') {
                start = i
                break
            }
        }
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until searchEnd) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")
}
