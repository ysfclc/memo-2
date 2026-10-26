package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONObject

/** Lifecycle of an outbound content artifact. */
enum class DraftState { NEW, IN_REVIEW, APPROVED, SENT, DISCARDED }

/** What shape of content the draft is. */
enum class DraftKind { POST, COMMENT, EMAIL, NOTE }

/**
 * A piece of authored content. Lives in the registry with the user's
 * explicit confirmation (reuse-first); a gate step must bind its
 * `contentHash` before anything leaves the device.
 */
data class ContentDraft(
    val kind: DraftKind = DraftKind.POST,
    val title: String = "",
    val body: String = "",
    val state: DraftState = DraftState.NEW,
    val audienceKey: String = "",     // optional Person/Company target
    val evidence: List<Evidence> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject {
        val evd = org.json.JSONArray()
        evidence.forEach { evd.put(it.toJson()) }
        return JSONObject()
            .put("kind", kind.name)
            .put("title", title)
            .put("body", body)
            .put("state", state.name)
            .put("audienceKey", audienceKey)
            .put("evidence", evd)
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
        body.isBlank() ||
            title.length > Limits.MAX_TASK_TITLE_CHARS ||
            body.length > Limits.MAX_DRAFT_BODY_CHARS ||
            evidence.size > Limits.MAX_EVIDENCE_PER_STEP ||
            evidence.any { it.isInvalid() }

    companion object {
        fun fromJson(raw: JSONObject): ContentDraft? {
            val body = raw.optString("body")
            if (body.isBlank()) return null
            val kind = runCatching {
                DraftKind.valueOf(raw.optString("kind", "POST"))
            }.getOrDefault(DraftKind.POST)
            val state = runCatching {
                DraftState.valueOf(raw.optString("state", "NEW"))
            }.getOrDefault(DraftState.NEW)
            val evdJson = raw.optJSONArray("evidence") ?: org.json.JSONArray()
            return ContentDraft(
                kind = kind,
                title = raw.optString("title"),
                body = body,
                state = state,
                audienceKey = raw.optString("audienceKey"),
                evidence = buildList {
                    for (i in 0 until evdJson.length()) {
                        val item = evdJson.optJSONObject(i) ?: continue
                        add(Evidence.fromJson(item))
                    }
                },
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * Something the user published elsewhere (own post, not a quote).
 * Natural key = normalized URL; metric samples hang off this key.
 */
data class SocialPost(
    val url: String = "",
    val platform: String = "",        // "linkedin", "x", "newsletter"; free label, pinned later
    val excerpt: String = "",
    val postedAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("platform", platform)
        .put("excerpt", excerpt)
        .put("postedAt", postedAt)
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
        url.isBlank() ||
            url.length > Limits.MAX_METRIC_TARGET_CHARS ||
            platform.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            excerpt.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): SocialPost? {
            val url = raw.optString("url")
            if (url.isBlank()) return null
            return SocialPost(
                url = url,
                platform = raw.optString("platform"),
                excerpt = raw.optString("excerpt"),
                postedAt = raw.optLong("postedAt", 0L),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * One measurement of one metric on one URL. Approved metric-channel law:
 * values are captured only through explicitly allowed collectors; the
 * sample is always attributable and capped.
 */
data class MetricSample(
    val postKey: String = "",         // SocialPost natural id
    val metric: String = "",          // "likes" | "comments" | "reposts" | "views" | "followers"
    val value: Long = 0,
    val capturedAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("postKey", postKey)
        .put("metric", metric)
        .put("value", value)
        .put("capturedAt", capturedAt)
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
        postKey.isBlank() ||
            metric.isBlank() ||
            metric.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            value < 0

    companion object {
        fun fromJson(raw: JSONObject): MetricSample? {
            val postKey = raw.optString("postKey")
            val metric = raw.optString("metric")
            if (postKey.isBlank() || metric.isBlank()) return null
            return MetricSample(
                postKey = postKey,
                metric = metric,
                value = raw.optLong("value", 0L).coerceAtLeast(0L),
                capturedAt = raw.optLong("capturedAt", 0L),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}
