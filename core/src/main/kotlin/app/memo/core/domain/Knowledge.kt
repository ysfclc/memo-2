package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle of a [Project]. */
enum class ProjectStatus { ACTIVE, PAUSED, DONE, ARCHIVED }

/** What a [Note] records. */
enum class NoteKind { IDEA, INSIGHT, DECISION, SOURCE, OUTPUT }

/**
 * A named body of work. Natural key = slug(name), so every note, template
 * and plan that mentions the project reuses the same record (AGENTS.md rule 2).
 */
data class Project(
    val name: String = "",
    val summary: String = "",
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val tags: List<String> = emptyList(),
    val principleKeys: List<String> = emptyList(),    // ArchitecturalPrinciple natural ids
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("summary", summary)
        .put("status", status.name)
        .put("tags", JSONArray(tags))
        .put("principleKeys", JSONArray(principleKeys))
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
        name.isBlank() ||
            name.length > Limits.MAX_NAME_CHARS ||
            summary.length > Limits.MAX_NOTES_CHARS ||
            listInvalid(tags, Limits.MAX_NAME_CHARS) ||
            listInvalid(principleKeys, Limits.MAX_CONTACT_FIELD_CHARS)

    companion object {
        fun fromJson(raw: JSONObject): Project? {
            val name = raw.optString("name")
            if (name.isBlank()) return null
            val status = runCatching {
                ProjectStatus.valueOf(raw.optString("status", "ACTIVE"))
            }.getOrDefault(ProjectStatus.ACTIVE)
            return Project(
                name = name,
                summary = raw.optString("summary"),
                status = status,
                tags = jsonStringList(raw.optJSONArray("tags")),
                principleKeys = jsonStringList(raw.optJSONArray("principleKeys")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * A captured idea, insight, decision, source or output. Natural key =
 * (project | title-slug | creation day); a blank projectKey is an inbox note.
 */
data class Note(
    val projectKey: String = "",      // Project natural id; blank = unfiled
    val title: String = "",
    val body: String = "",
    val kind: NoteKind = NoteKind.IDEA,
    val sourceRef: String = "",       // URL or file reference the note came from
    val evidence: List<Evidence> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("projectKey", projectKey)
        .put("title", title)
        .put("body", body)
        .put("kind", kind.name)
        .put("sourceRef", sourceRef)
        .put("evidence", evidenceJson(evidence))
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
        title.isBlank() ||
            title.length > Limits.MAX_TITLE_CHARS ||
            body.length > Limits.MAX_DRAFT_BODY_CHARS ||
            projectKey.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            sourceRef.length > Limits.MAX_METRIC_TARGET_CHARS ||
            evidence.size > Limits.MAX_EVIDENCE_PER_STEP ||
            evidence.any { it.isInvalid() }

    companion object {
        fun fromJson(raw: JSONObject): Note? {
            val title = raw.optString("title")
            if (title.isBlank()) return null
            val kind = runCatching {
                NoteKind.valueOf(raw.optString("kind", "IDEA"))
            }.getOrDefault(NoteKind.IDEA)
            return Note(
                projectKey = raw.optString("projectKey"),
                title = title,
                body = raw.optString("body"),
                kind = kind,
                sourceRef = raw.optString("sourceRef"),
                evidence = jsonEvidence(raw.optJSONArray("evidence")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/** True when a string list breaks the count cap, or holds a blank or over-long item. */
internal fun listInvalid(items: List<String>, maxItemChars: Int): Boolean =
    items.size > Limits.MAX_LIST_ITEMS ||
        items.any { it.isBlank() || it.length > maxItemChars }

internal fun jsonStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) add(arr.optString(i))
    }
}

internal fun evidenceJson(items: List<Evidence>): JSONArray {
    val arr = JSONArray()
    items.forEach { arr.put(it.toJson()) }
    return arr
}

internal fun jsonEvidence(arr: JSONArray?): List<Evidence> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(Evidence.fromJson(item))
        }
    }
}
