package app.memo.core.domain

import org.json.JSONArray
import org.json.JSONObject

/** Review state of an [Entity] or [Relation]; only APPROVED facts are used downstream. */
enum class ReviewStatus { PROPOSED, APPROVED, REJECTED }

/** Shape of an open entity type or relation type: lowercase snake, starting with a letter. */
private val TYPE_SHAPE = Regex("^[a-z][a-z0-9_]{0,31}$")

private const val MAX_ENTITY_TITLE_CHARS: Int = 200
private const val MAX_KEY_CHARS: Int = 128
private const val MAX_PROPERTIES: Int = 20
private const val MAX_PROPERTY_VALUE_CHARS: Int = 500
private const val MAX_PROVENANCE: Int = 10
private const val MAX_EXCERPT_CHARS: Int = 500

/**
 * Where a fact came from: the source record kind and id, plus the exact
 * supporting text. Stored verbatim; never paraphrased.
 */
data class Provenance(
    val sourceKind: String = "",      // e.g. "chat_message", "note", "capture"
    val sourceId: String = "",
    val excerpt: String = "",         // exact supporting text
    val capturedAt: Long = 0,
) {
    /** True when the excerpt is empty or over cap, or an identifier is over cap. */
    fun isInvalid(): Boolean =
        excerpt.isBlank() ||
            excerpt.length > MAX_EXCERPT_CHARS ||
            sourceKind.length > MAX_KEY_CHARS ||
            sourceId.length > MAX_KEY_CHARS

    fun toJson(): JSONObject = JSONObject()
        .put("sourceKind", sourceKind)
        .put("sourceId", sourceId)
        .put("excerpt", excerpt)
        .put("capturedAt", capturedAt)

    companion object {
        fun fromJson(raw: JSONObject): Provenance = Provenance(
            sourceKind = raw.optString("sourceKind"),
            sourceId = raw.optString("sourceId"),
            // Verbatim: smart quotes, dashes and line breaks in the excerpt are kept.
            excerpt = raw.optString("excerpt"),
            capturedAt = raw.optLong("capturedAt", 0L),
        )
    }
}

/**
 * An open-type thing the user mentioned (place, event, product, idea, topic…).
 * Natural key = type + title slug, so "Web Summit" recorded twice is one record.
 */
data class Entity(
    val type: String = "",
    val title: String = "",
    val properties: Map<String, String> = emptyMap(),
    val reviewStatus: ReviewStatus = ReviewStatus.PROPOSED,
    val provenance: List<Provenance> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject {
        val props = JSONObject()
        properties.forEach { (k, v) -> props.put(k, v) }
        return JSONObject()
            .put("type", type)
            .put("title", title)
            .put("properties", props)
            .put("reviewStatus", reviewStatus.name)
            .put("provenance", provenanceJson(provenance))
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("version", version)
    }

    fun contentJson(): JSONObject {
        val o = toJson()
        o.remove("createdAt").also { o.remove("updatedAt") }
        o.remove("version")
        return o
    }

    fun isInvalid(): Boolean =
        !TYPE_SHAPE.matches(type) ||
            title.isBlank() ||
            title.length > MAX_ENTITY_TITLE_CHARS ||
            properties.size > MAX_PROPERTIES ||
            properties.any { (k, v) ->
                !TYPE_SHAPE.matches(k) || v.length > MAX_PROPERTY_VALUE_CHARS
            } ||
            provenance.size > MAX_PROVENANCE ||
            provenance.any { it.isInvalid() }

    companion object {
        fun fromJson(raw: JSONObject): Entity? {
            val type = raw.optString("type")
            val title = raw.optString("title")
            if (type.isBlank() || title.isBlank()) return null
            val status = runCatching {
                ReviewStatus.valueOf(raw.optString("reviewStatus", "PROPOSED"))
            }.getOrDefault(ReviewStatus.PROPOSED)
            val propsObj = raw.optJSONObject("properties") ?: JSONObject()
            val props = LinkedHashMap<String, String>()
            propsObj.keys().forEach { key -> props[key] = propsObj.optString(key) }
            return Entity(
                type = type,
                title = title,
                properties = props,
                reviewStatus = status,
                provenance = jsonProvenance(raw.optJSONArray("provenance")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * A typed link between two records, e.g. "Ana works_at Acme". The endpoints
 * are registry natural keys. Natural key = (fromKey | toKey | relationType),
 * so the same link proposed twice is one record.
 */
data class Relation(
    val fromKey: String = "",
    val toKey: String = "",
    val relationType: String = "",
    val reviewStatus: ReviewStatus = ReviewStatus.PROPOSED,
    val provenance: List<Provenance> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fromKey", fromKey)
        .put("toKey", toKey)
        .put("relationType", relationType)
        .put("reviewStatus", reviewStatus.name)
        .put("provenance", provenanceJson(provenance))
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("version", version)

    fun contentJson(): JSONObject {
        val o = toJson()
        o.remove("createdAt").also { o.remove("updatedAt") }
        o.remove("version")
        return o
    }

    fun isInvalid(): Boolean =
        !TYPE_SHAPE.matches(relationType) ||
            fromKey.isBlank() || fromKey.length > MAX_KEY_CHARS ||
            toKey.isBlank() || toKey.length > MAX_KEY_CHARS ||
            fromKey == toKey ||
            provenance.size > MAX_PROVENANCE ||
            provenance.any { it.isInvalid() }

    companion object {
        fun fromJson(raw: JSONObject): Relation? {
            val relation = Relation(
                fromKey = raw.optString("fromKey"),
                toKey = raw.optString("toKey"),
                relationType = raw.optString("relationType"),
                reviewStatus = runCatching {
                    ReviewStatus.valueOf(raw.optString("reviewStatus", "PROPOSED"))
                }.getOrDefault(ReviewStatus.PROPOSED),
                provenance = jsonProvenance(raw.optJSONArray("provenance")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            )
            return relation.takeUnless { it.isInvalid() }
        }
    }
}

private fun provenanceJson(items: List<Provenance>): JSONArray {
    val arr = JSONArray()
    items.forEach { arr.put(it.toJson()) }
    return arr
}

private fun jsonProvenance(arr: JSONArray?): List<Provenance> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(Provenance.fromJson(item))
        }
    }
}
