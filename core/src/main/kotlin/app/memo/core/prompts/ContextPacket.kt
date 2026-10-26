package app.memo.core.prompts

import app.memo.core.Limits

/**
 * A bounded, scope-limited prompt packet (Pocket law): only fields the
 * channel needs, every field capped, whole packet capped, truncation
 * visible. Built from the registry at run time by the app layer — never
 * by freeform model output.
 */
class ContextPacket private constructor(
    private val fields: LinkedHashMap<String, String>,
) {

    val fieldCount: Int get() = fields.size
    val approximateChars: Int get() = fields.values.sumOf { it.length + it.toString().length / 8 }

    fun isEmpty(): Boolean = fields.isEmpty()

    /** Visible-truncation render; never exceeds [totalCap] by more than the marker. */
    fun render(totalCap: Int = Limits.PACKET_TOTAL_MAX_CHARS): String {
        if (fields.isEmpty()) return "(no packet fields)"
        val sb = StringBuilder()
        var used = 0
        for ((key, value) in fields) {
            val entryLength = key.length + value.length + 2
            if (used + entryLength > totalCap) {
                sb.append("[packet truncated at ").append(totalCap).append(" chars]")
                break
            }
            sb.append(key).append(": ").append(value).append('\n')
            used += entryLength
        }
        return sb.toString().removeSuffix("\n")
    }

    fun fieldsSnapshot(): Map<String, String> = fields.toMap()

    class Builder {
        private val fields = LinkedHashMap<String, String>()

        /**
         * Adds/overwrites one field. Values are whitespace-normalized and
         * capped [Limits.PACKET_FIELD_MAX_CHARS]; field count is capped at
         * [Limits.PACKET_MAX_FIELDS] (later puts ignored once full).
         */
        fun put(key: String, value: String?): Builder {
            if (fields.size >= Limits.PACKET_MAX_FIELDS) return this
            val cleaned = value?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.take(Limits.PACKET_FIELD_MAX_CHARS)
                .orEmpty()
            if (cleaned.isBlank()) return this
            fields[key.trim()] = cleaned
            return this
        }

        fun putAll(entries: Map<String, String?>): Builder {
            entries.forEach { (key, value) -> put(key, value) }
            return this
        }

        fun build(): ContextPacket = ContextPacket(LinkedHashMap(fields))
    }
}
