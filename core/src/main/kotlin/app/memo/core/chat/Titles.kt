package app.memo.core.chat

/** Pure title rules. A title is derived once, from the first user message, and never silently rewritten. */
object ChatTitles {
    const val EMPTY: String = "New chat"

    private val WHITESPACE = Regex("\\s+")

    /**
     * Title from the first user message: whitespace collapsed, at most
     * [ChatLimits.GENERATED_TITLE_CHARS] characters, cut at a word boundary when one exists.
     * Never splits a surrogate pair.
     */
    fun fromFirstMessage(text: String): String {
        val max = ChatLimits.GENERATED_TITLE_CHARS
        val collapsed = text.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return EMPTY
        if (collapsed.length <= max) return collapsed
        if (collapsed[max] == ' ') return collapsed.substring(0, max).trimEnd()
        val lastSpace = collapsed.substring(0, max).lastIndexOf(' ')
        val cut = if (lastSpace > 0) lastSpace else max
        val end = if (cut > 0 && Character.isHighSurrogate(collapsed[cut - 1])) cut - 1 else cut
        return collapsed.substring(0, end).trimEnd()
    }
}
