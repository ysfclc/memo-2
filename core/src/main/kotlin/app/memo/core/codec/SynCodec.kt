package app.memo.core.codec

import app.memo.core.Limits
import app.memo.core.registry.Workspace
import org.json.JSONObject

/**
 * The SYN envelope (Pocket law): strict validate -> migrate -> load.
 * A bad envelope is REJECTED as a whole (caller keeps its old copy) — the
 * codec never merges partial garbage against good data.Individual rows
 * inside a valid envelope may quarantine themselves (registry law).
 */
object SynCodec {

    const val FORMAT_ID: String = "SYN"

    sealed interface ParseOutcome {
        data class Ok(
            val workspace: Workspace,
            val fromVersion: Int,
            val migrated: Boolean,
        ) : ParseOutcome

        data class Rejected(val reason: String) : ParseOutcome
    }

    /** Migration chain: `fromVersion -> transformer`. v1 is the current head. */
    private val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
        1 to { it }, // identity placeholder so the chain has a head; v2 will prepend real steps
    )

    fun parse(text: String): ParseOutcome {
        if (text.length > Limits.MAX_WORKSPACE_BYTES) {
            return ParseOutcome.Rejected("workspace larger than ${Limits.MAX_WORKSPACE_BYTES} bytes")
        }
        val doc = runCatching { JSONObject(text) }.fold(
            onSuccess = { it },
            onFailure = { return ParseOutcome.Rejected("invalid JSON: ${it.message}") },
        )
        if (doc.optString("format") != FORMAT_ID) {
            return ParseOutcome.Rejected("envelope format must be $FORMAT_ID")
        }
        val version = doc.optInt("schemaVersion", 0)
        if (version <= 0) {
            return ParseOutcome.Rejected("missing or invalid schemaVersion")
        }
        if (version > Limits.WORKSPACE_SCHEMA_VERSION) {
            return ParseOutcome.Rejected("workspace from newer version $version (this build: ${Limits.WORKSPACE_SCHEMA_VERSION})")
        }
        var migrated = false
        var current = doc
        var v = version
        while (v < Limits.WORKSPACE_SCHEMA_VERSION) {
            val step = migrations[v]
                ?: return ParseOutcome.Rejected("no migration registered from v$v")
            current = step(current)
            v += 1
            migrated = true
        }
        val registries = current.optJSONObject("registries")
            ?: return ParseOutcome.Rejected("missing registries object")
        if (registries.length() > 64) {
            return ParseOutcome.Rejected("registries object suspiciously large")
        }
        val workspace = Workspace()
        workspace.people.load(registries.optJSONArray("people"))
        workspace.companies.load(registries.optJSONArray("companies"))
        workspace.jobPostings.load(registries.optJSONArray("job_postings"))
        workspace.applications.load(registries.optJSONArray("applications"))
        workspace.tasks.load(registries.optJSONArray("tasks"))
        workspace.drafts.load(registries.optJSONArray("drafts"))
        workspace.posts.load(registries.optJSONArray("posts"))
        workspace.samples.load(registries.optJSONArray("metric_samples"))
        return ParseOutcome.Ok(workspace, version, migrated)
    }

    fun serialize(workspace: Workspace, exportedAt: Long): String {
        val registries = JSONObject()
            .put("people", workspace.people.toRows())
            .put("companies", workspace.companies.toRows())
            .put("job_postings", workspace.jobPostings.toRows())
            .put("applications", workspace.applications.toRows())
            .put("tasks", workspace.tasks.toRows())
            .put("drafts", workspace.drafts.toRows())
            .put("posts", workspace.posts.toRows())
            .put("metric_samples", workspace.samples.toRows())
        val out = JSONObject()
            .put("format", FORMAT_ID)
            .put("schemaVersion", Limits.WORKSPACE_SCHEMA_VERSION)
            .put("exportedAt", exportedAt)
            .put("registries", registries)
        val text = out.toString(2)
        if (text.length > Limits.MAX_WORKSPACE_BYTES) {
            throw IllegalStateException("serialized workspace exceeds the size cap")
        }
        return text
    }
}
