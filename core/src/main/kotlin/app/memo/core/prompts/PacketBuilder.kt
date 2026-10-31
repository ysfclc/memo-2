package app.memo.core.prompts

import app.memo.core.Limits
import app.memo.core.redact.Redactor
import app.memo.core.registry.Registry
import app.memo.core.registry.Workspace
import org.json.JSONObject

/**
 * Context retrieval for prompt packets (pure core, no Android).
 *
 * Ranks every row of every registry against a user query and the recent
 * chat, then builds a budget-capped, redacted [ContextPacket]. Ranking order:
 * MENTION (`@name`, `"name"` or `@key`), then NATURAL_KEY (a name or the key as
 * a whole phrase), then KEYWORD (distinct query words found in the row, by
 * overlap score). Names are the human identity fields of each row; keys are
 * hashes and only match when typed literally.
 */
object PacketBuilder {

    enum class Match { MENTION, NATURAL_KEY, KEYWORD }

    /**
     * One ranked row. [label] is the row's first display name (or its key when it
     * has none). [text] is the row's raw JSON; redaction happens in [build].
     */
    data class Hit(
        val kind: String,
        val key: String,
        val label: String,
        val match: Match,
        val score: Int,
        val text: String,
    )

    const val RECENT_CHAT_FIELD = "recent_chat"
    const val RECENT_CHAT_LINES = 6
    private const val MIN_KEYWORD_CHARS = 4
    private const val MIN_NATURAL_KEY_CHARS = 3
    private const val MIN_NAME_CHARS = 3

    /** Human identity fields; whichever exist on a row supply its display names. */
    private val NAME_FIELDS = listOf("fullName", "name", "title", "displayName")

    private val STOP_WORDS = setOf(
        "that", "this", "with", "from", "about", "have", "what", "when", "where", "which", "your",
    )
    private val WORD = Regex("[\\p{L}\\p{Nd}]+")
    private val WHITESPACE = Regex("\\s+")

    /** Pure ranking over every registry in [workspace]; best match per row. */
    fun rank(query: String, workspace: Workspace): List<Hit> {
        val q = query.lowercase()
        val words = queryWords(q)
        val hits = mutableListOf<Hit>()
        for (registry in workspace.all()) collect(registry, q, words, hits)
        return hits.sortedWith(
            compareBy<Hit> { it.match.ordinal }
                .thenByDescending { it.score }
                .thenBy { it.kind }
                .thenBy { it.key },
        )
    }

    /**
     * Builds the packet: ranked row hits as `<kind>:<key>` fields, then the last
     * [RECENT_CHAT_LINES] chat lines as `recent_chat`. Every value is redacted and
     * the whole packet stays within [budgetChars] (approximateChars).
     */
    fun build(
        query: String,
        workspace: Workspace,
        recentChat: List<String> = emptyList(),
        budgetChars: Int = Limits.PACKET_TOTAL_MAX_CHARS,
    ): ContextPacket {
        val chatField = recentChatField(recentChat, budgetChars)
        val chatCost = chatField?.let { RECENT_CHAT_FIELD.length + it.length + 2 } ?: 0
        val hitBudget = budgetChars - chatCost
        val maxHits = Limits.PACKET_MAX_FIELDS - (if (chatField != null) 1 else 0)

        val builder = ContextPacket.Builder()
        var used = 0
        var count = 0
        for (hit in rank(query, workspace)) {
            if (count >= maxHits) break
            val value = clean(Redactor.redact(hit.text).text, Limits.PACKET_FIELD_MAX_CHARS)
            if (value.isEmpty()) continue
            val field = "${hit.kind}:${hit.key}"
            val cost = field.length + value.length + 2
            if (used + cost > hitBudget) break
            builder.put(field, value)
            used += cost
            count++
        }
        if (chatField != null) builder.put(RECENT_CHAT_FIELD, chatField)
        return builder.build()
    }

    private fun <T> collect(registry: Registry<T>, q: String, words: Set<String>, out: MutableList<Hit>) {
        val scheme = registry.scheme
        for (item in registry.items()) {
            val key = scheme.id(item).trim().lowercase()
            if (key.isEmpty()) continue
            val json = scheme.toJson(item)
            val text = json.toString()
            val names = displayNames(json)
            val (match, score) = bestMatch(key, names, q, words, text) ?: continue
            out += Hit(registry.kind, key, names.firstOrNull() ?: key, match, score, text)
        }
    }

    /** Lowercased, whitespace-collapsed, non-blank name fields of [json] of at least [MIN_NAME_CHARS]. */
    private fun displayNames(json: JSONObject): List<String> =
        NAME_FIELDS.map { field -> normalizeName(json.optString(field, "")) }
            .filter { it.length >= MIN_NAME_CHARS }
            .distinct()

    private fun normalizeName(raw: String): String = raw.trim().lowercase().replace(WHITESPACE, " ")

    private fun bestMatch(
        key: String,
        names: List<String>,
        q: String,
        words: Set<String>,
        text: String,
    ): Pair<Match, Int>? {
        if (q.isEmpty()) return null
        val mentioned = q.contains("@$key") || q.contains("\"$key\"") ||
            names.any { containsWhole(q, "@$it") || containsWhole(q, "\"$it\"") }
        if (mentioned) return Match.MENTION to 0
        val named = (key.length >= MIN_NATURAL_KEY_CHARS && containsWhole(q, key)) ||
            names.any { containsWhole(q, it) }
        if (named) return Match.NATURAL_KEY to 0
        val lowerText = text.lowercase()
        val score = words.count { lowerText.contains(it) }
        return if (score >= 1) Match.KEYWORD to score else null
    }

    /** True when [needle] occurs in [haystack] bounded by non-alphanumeric characters. */
    private fun containsWhole(haystack: String, needle: String): Boolean {
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            val before = haystack.getOrNull(from - 1)
            val after = haystack.getOrNull(from + needle.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) {
                return true
            }
            from = haystack.indexOf(needle, from + 1)
        }
        return false
    }

    private fun queryWords(q: String): Set<String> =
        WORD.findAll(q).map { it.value }
            .filter { it.length >= MIN_KEYWORD_CHARS && it !in STOP_WORDS }
            .toSet()

    /**
     * Redacted, cleaned recent-chat value that fits the budget, or null when
     * there is no chat. Its cost is reserved before any row hit is added.
     */
    private fun recentChatField(recentChat: List<String>, budgetChars: Int): String? {
        val lines = recentChat.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(RECENT_CHAT_LINES)
        if (lines.isEmpty()) return null
        val redacted = clean(Redactor.redact(lines.joinToString(" | ")).text, Limits.PACKET_FIELD_MAX_CHARS)
        val room = (budgetChars - RECENT_CHAT_FIELD.length - 2).coerceAtLeast(0)
        val fitted = clean(redacted.take(room), Limits.PACKET_FIELD_MAX_CHARS)
        return fitted.ifEmpty { null }
    }

    /**
     * Mirrors [ContextPacket.Builder.put] normalization (trim, collapse whitespace,
     * cap length), trailing-space free, so the counted length is the stored length.
     */
    private fun clean(raw: String, maxChars: Int): String =
        raw.trim().replace(WHITESPACE, " ").take(maxChars).trimEnd()
}
