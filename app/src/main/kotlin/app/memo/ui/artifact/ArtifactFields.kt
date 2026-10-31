package app.memo.ui.artifact

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One human-labelled row of a rendered artifact. */
data class ArtifactField(val label: String, val value: String)

/**
 * A filled artifact ready for display. [isFallback] is true when no dedicated
 * renderer applied (unknown kind, invalid JSON, or no recognised fields) and the
 * fields are the raw top-level key/value pairs.
 */
data class RenderedArtifact(
    val kind: String,
    val title: String,
    val fields: List<ArtifactField>,
    val isFallback: Boolean,
)

/**
 * Pure rendering of filled artifact JSON (the domain entity `toJson()` shapes)
 * into labelled fields. Never throws.
 */
object ArtifactFields {
    private const val FALLBACK_VALUE_CHARS = 280
    private const val NOTES_EXCERPT_CHARS = 280
    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    private class Mapping(
        val title: (JSONObject) -> String,
        val fields: (JSONObject) -> List<ArtifactField>,
    )

    private val mappings: Map<String, Mapping> = mapOf(
        "person" to Mapping(
            title = { it.text("fullName") },
            fields = { o ->
                o.collect(
                    "Name" to "fullName",
                    "Role" to "role",
                    "Company" to "companyKey",
                    "Email" to "email",
                    "Phone" to "phone",
                    "Notes" to "notes",
                )
            },
        ),
        "company" to Mapping(
            title = { it.text("displayName").ifBlank { it.text("domain") } },
            fields = { o ->
                o.collect(
                    "Name" to "displayName",
                    "Domain" to "domain",
                    "Notes" to "notes",
                )
            },
        ),
        "job_posting" to Mapping(
            title = { it.text("role") },
            fields = { o ->
                o.collect(
                    "Role" to "role",
                    "Company" to "companyKey",
                    "URL" to "url",
                    "Summary" to "summary",
                    "Notes" to "notes",
                )
            },
        ),
        "application" to Mapping(
            title = { it.text("jobKey") },
            fields = { o ->
                listOfNotNull(
                    o.textField("Posting", "jobKey"),
                    o.humanField("Status", "state"),
                    o.dateField("Applied", "appliedAt"),
                    o.textField("Contact", "contactKey"),
                    o.textField("Notes", "notes")?.let {
                        ArtifactField("Notes", it.value.take(NOTES_EXCERPT_CHARS))
                    },
                )
            },
        ),
        "task" to Mapping(
            title = { it.text("title") },
            fields = { o ->
                listOfNotNull(
                    o.humanField("Status", "state"),
                    o.dateField("Due", "dueAt"),
                    o.textField("Notes", "notes"),
                )
            },
        ),
        "draft" to Mapping(
            title = { it.text("title") },
            fields = { o ->
                val body = o.text("body")
                listOfNotNull(
                    o.humanField("Kind", "kind"),
                    o.humanField("Status", "state"),
                    o.textField("Audience", "audienceKey"),
                    o.textField("Body", "body"),
                ) + characters(body)
            },
        ),
        "post" to Mapping(
            title = { it.text("url") },
            fields = { o ->
                val excerpt = o.text("excerpt")
                listOfNotNull(
                    o.textField("URL", "url"),
                    o.textField("Platform", "platform"),
                    o.dateField("Posted", "postedAt"),
                    o.textField("Excerpt", "excerpt"),
                ) + characters(excerpt)
            },
        ),
    )

    /** Renderer kinds that have a dedicated renderer. Covers every `Prompts.ARTIFACT_KINDS` entry. */
    val dedicated: Set<String> = mappings.keys

    fun render(kind: String, json: String): RenderedArtifact {
        val mapping = mappings[kind]
        val obj = runCatching { JSONObject(json) }.getOrNull()
        if (mapping == null || obj == null) return fallback(kind, obj, json)
        val fields = runCatching { mapping.fields(obj) }.getOrDefault(emptyList())
        if (fields.isEmpty()) return fallback(kind, obj, json)
        val title = runCatching { mapping.title(obj) }.getOrDefault("").ifBlank { kind }
        return RenderedArtifact(kind = kind, title = title, fields = fields, isFallback = false)
    }

    private fun fallback(kind: String, obj: JSONObject?, json: String): RenderedArtifact {
        val fields = when {
            obj != null -> obj.keys().asSequence().sorted().map { key ->
                ArtifactField(key, clip(stringify(obj.get(key))))
            }.toList()
            json.isBlank() -> emptyList()
            else -> listOf(ArtifactField("Raw", clip(json.trim())))
        }
        return RenderedArtifact(kind = kind, title = kind, fields = fields, isFallback = true)
    }

    private fun stringify(value: Any?): String =
        if (value == null || value == JSONObject.NULL) "null" else value.toString()

    private fun clip(value: String): String =
        if (value.length <= FALLBACK_VALUE_CHARS) value
        else value.take(FALLBACK_VALUE_CHARS - 1) + "…"

    private fun characters(body: String): List<ArtifactField> =
        if (body.isBlank()) emptyList() else listOf(ArtifactField("Characters", body.length.toString()))

    private fun JSONObject.text(key: String): String =
        if (has(key) && !isNull(key)) optString(key).trim() else ""

    private fun JSONObject.textField(label: String, key: String): ArtifactField? =
        text(key).takeIf { it.isNotBlank() }?.let { ArtifactField(label, it) }

    private fun JSONObject.humanField(label: String, key: String): ArtifactField? =
        text(key).takeIf { it.isNotBlank() }?.let { ArtifactField(label, humanize(it)) }

    private fun JSONObject.dateField(label: String, key: String): ArtifactField? =
        optLong(key, 0L).takeIf { it > 0L }?.let { ArtifactField(label, formatDate(it)) }

    private fun JSONObject.collect(vararg specs: Pair<String, String>): List<ArtifactField> =
        specs.mapNotNull { (label, key) -> textField(label, key) }

    private fun formatDate(epochMillis: Long): String =
        runCatching { DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis)) }
            .getOrDefault(epochMillis.toString())

    /** `IN_REVIEW` becomes `In review`. */
    private fun humanize(raw: String): String =
        raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
