package app.memo.core.chat

import java.text.Normalizer
import java.util.Locale

/**
 * Case- and diacritic-insensitive search over titles and message text.
 *
 * Folding maps the Turkish dotted and dotless i (U+0130, U+0131) to `i` and the Polish
 * stroked l (U+0141, U+0142) to `l`, lowercases with the root locale, then removes combining
 * marks after NFD decomposition. The same fold is applied to query and haystack.
 */
object ChatSearch {
    private val MARKS = Regex("\\p{Mn}+")

    fun fold(input: String): String {
        val mapped = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    'İ', 'ı' -> append('i')
                    'Ł', 'ł' -> append('l')
                    else -> append(ch)
                }
            }
        }
        val lower = mapped.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return Normalizer.normalize(decomposed.replace(MARKS, ""), Normalizer.Form.NFC)
    }

    /** True when the folded [query] appears in the folded title or in any message text. Blank query matches all. */
    fun matches(query: String, conversation: Conversation, messages: List<ChatMessage>): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        if (fold(conversation.title).contains(q)) return true
        return messages.any { fold(it.text).contains(q) }
    }
}
