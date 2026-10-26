package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONArray
import org.json.JSONObject

/** Channels a [ProviderAdapter] can drive. */
private val ADAPTER_CHANNELS = setOf("web", "app")

/** Semver text, major.minor.patch. */
private val SEMVER = Regex("""\d+\.\d+\.\d+""")

private fun isJsonObject(text: String): Boolean =
    runCatching { JSONObject(text) }.isSuccess

/** A `{title, steps}` plan: a title and between one and [Limits.MAX_STEPS_PER_RUN] steps. */
private fun isPlanJson(text: String): Boolean {
    val plan = runCatching { JSONObject(text) }.getOrNull() ?: return false
    val steps = plan.optJSONArray("steps") ?: return false
    return plan.optString("title").isNotBlank() && steps.length() in 1..Limits.MAX_STEPS_PER_RUN
}

/**
 * A reusable plan the user has run or saved. Natural key = slug(name).
 * Usage stats for templates live with run history, not on the template row.
 */
data class WorkflowTemplate(
    val name: String = "",
    val description: String = "",
    val intentExamples: List<String> = emptyList(),
    val planJson: String = "",        // {"title": "...", "steps": [...]}
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("intentExamples", JSONArray(intentExamples))
        .put("planJson", planJson)
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
            description.length > Limits.MAX_NOTES_CHARS ||
            listInvalid(intentExamples, Limits.MAX_TITLE_CHARS) ||
            planJson.isBlank() ||
            planJson.length > Limits.MAX_JSON_PAYLOAD_CHARS ||
            !isPlanJson(planJson)

    companion object {
        fun fromJson(raw: JSONObject): WorkflowTemplate? {
            val name = raw.optString("name")
            val planJson = raw.optString("planJson")
            if (name.isBlank() || planJson.isBlank()) return null
            return WorkflowTemplate(
                name = name,
                description = raw.optString("description"),
                intentExamples = jsonStringList(raw.optJSONArray("intentExamples")),
                planJson = planJson,
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * A reusable prompt body with named variables. Natural key = slug(name).
 */
data class PromptTemplate(
    val name: String = "",
    val role: String = "",            // free label, e.g. RESEARCH, DRAFT, CRITIQUE
    val body: String = "",
    val variables: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("role", role)
        .put("body", body)
        .put("variables", JSONArray(variables))
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
            role.length > Limits.MAX_NAME_CHARS ||
            body.isBlank() ||
            body.length > Limits.MAX_STEP_PROMPT_CHARS ||
            listInvalid(variables, Limits.MAX_NAME_CHARS)

    companion object {
        fun fromJson(raw: JSONObject): PromptTemplate? {
            val name = raw.optString("name")
            val body = raw.optString("body")
            if (name.isBlank() || body.isBlank()) return null
            return PromptTemplate(
                name = name,
                role = raw.optString("role"),
                body = body,
                variables = jsonStringList(raw.optJSONArray("variables")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * A rule the user's own work is built against. Natural key = slug(title).
 */
data class ArchitecturalPrinciple(
    val title: String = "",
    val statement: String = "",
    val rationale: String = "",
    val scope: String = "",           // e.g. "global" or a project name
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("statement", statement)
        .put("rationale", rationale)
        .put("scope", scope)
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
            statement.isBlank() ||
            statement.length > Limits.MAX_NOTES_CHARS ||
            rationale.length > Limits.MAX_NOTES_CHARS ||
            scope.length > Limits.MAX_CONTACT_FIELD_CHARS

    companion object {
        fun fromJson(raw: JSONObject): ArchitecturalPrinciple? {
            val title = raw.optString("title")
            val statement = raw.optString("statement")
            if (title.isBlank() || statement.isBlank()) return null
            return ArchitecturalPrinciple(
                title = title,
                statement = statement,
                rationale = raw.optString("rationale"),
                scope = raw.optString("scope"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * How the user wants content to sound for one scope. Natural key = scope,
 * lowercased and trimmed, so "Global" and " global " are the same preference.
 */
data class ContentPreference(
    val scope: String = "",           // "global" or a channel name
    val tone: String = "",
    val audience: String = "",
    val channels: List<String> = emptyList(),
    val cadence: String = "",
    val doNots: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("scope", scope)
        .put("tone", tone)
        .put("audience", audience)
        .put("channels", JSONArray(channels))
        .put("cadence", cadence)
        .put("doNots", JSONArray(doNots))
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
        scope.isBlank() ||
            scope.length > Limits.MAX_NAME_CHARS ||
            tone.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            audience.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            cadence.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            listInvalid(channels, Limits.MAX_CONTACT_FIELD_CHARS) ||
            listInvalid(doNots, Limits.MAX_NOTES_CHARS)

    companion object {
        fun fromJson(raw: JSONObject): ContentPreference? {
            val scope = raw.optString("scope")
            if (scope.isBlank()) return null
            return ContentPreference(
                scope = scope,
                tone = raw.optString("tone"),
                audience = raw.optString("audience"),
                channels = jsonStringList(raw.optJSONArray("channels")),
                cadence = raw.optString("cadence"),
                doNots = jsonStringList(raw.optJSONArray("doNots")),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * The user's account on one platform. Natural key = platform + handle.
 * Accounts start disabled: nothing acts through an account until the user
 * switches it on.
 */
data class ChannelAccount(
    val platform: String = "",        // "linkedin", "x", "email", ...
    val handle: String = "",
    val displayName: String = "",
    val profileUrl: String = "",
    val enabled: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("handle", handle)
        .put("displayName", displayName)
        .put("profileUrl", profileUrl)
        .put("enabled", enabled)
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
        platform.isBlank() ||
            platform.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            handle.isBlank() ||
            handle.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            displayName.length > Limits.MAX_NAME_CHARS ||
            profileUrl.length > Limits.MAX_METRIC_TARGET_CHARS

    companion object {
        fun fromJson(raw: JSONObject): ChannelAccount? {
            val platform = raw.optString("platform")
            val handle = raw.optString("handle")
            if (platform.isBlank() || handle.isBlank()) return null
            return ChannelAccount(
                platform = platform,
                handle = handle,
                displayName = raw.optString("displayName"),
                profileUrl = raw.optString("profileUrl"),
                enabled = raw.optBoolean("enabled", false),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * The selectors and scripts that drive one provider (a web provider id or an
 * app package). Natural key = channel + targetId. adapterVersion is semver
 * text; verifiedAt is the last time the adapter was checked against the target.
 */
data class ProviderAdapter(
    val targetId: String = "",        // web provider id or app package
    val channel: String = "",         // "web" | "app"
    val adapterVersion: String = "",  // semver, e.g. "1.2.0"
    val payloadJson: String = "",     // selectors/scripts as a JSON object
    val verifiedAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("targetId", targetId)
        .put("channel", channel)
        .put("adapterVersion", adapterVersion)
        .put("payloadJson", payloadJson)
        .put("verifiedAt", verifiedAt)
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
        targetId.isBlank() ||
            targetId.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            channel !in ADAPTER_CHANNELS ||
            !SEMVER.matches(adapterVersion) ||
            payloadJson.isBlank() ||
            payloadJson.length > Limits.MAX_JSON_PAYLOAD_CHARS ||
            !isJsonObject(payloadJson)

    companion object {
        fun fromJson(raw: JSONObject): ProviderAdapter? {
            val targetId = raw.optString("targetId")
            val payloadJson = raw.optString("payloadJson")
            if (targetId.isBlank() || payloadJson.isBlank()) return null
            return ProviderAdapter(
                targetId = targetId,
                channel = raw.optString("channel"),
                adapterVersion = raw.optString("adapterVersion"),
                payloadJson = payloadJson,
                verifiedAt = raw.optLong("verifiedAt", 0L),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}
