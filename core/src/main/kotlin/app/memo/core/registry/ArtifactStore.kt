package app.memo.core.registry

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.JobApplication
import app.memo.core.domain.JobPosting
import app.memo.core.domain.Note
import app.memo.core.domain.Person
import app.memo.core.domain.Project
import app.memo.core.domain.SocialPost
import app.memo.core.domain.Task
import app.memo.core.prompts.Prompts
import org.json.JSONObject

/**
 * Persists an approved artifact (the JSON the user signed off) into the
 * matching [Workspace] registry. Every kind in [Prompts.ARTIFACT_KINDS] has
 * exactly one registry; reuse, bump and create semantics come from
 * [Registry.upsert]. Pure and JVM-testable; never throws.
 */
object ArtifactStore {

    /** Outcome of one save: [Saved] carries "created", "reused" or "bumped"; [Rejected] carries a plain reason. */
    sealed interface Result {
        data class Saved(val outcome: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /** The kinds this store can save; equal to [Prompts.ARTIFACT_KINDS]. */
    val kinds: Set<String> = Prompts.ARTIFACT_KINDS

    /** Saves an approved artifact. Returns "" on success (created, reused or bumped), else the reason. */
    fun persist(workspace: Workspace, kind: String, payloadJson: String, now: Long): String =
        when (val result = persistResult(workspace, kind, payloadJson, now)) {
            is Result.Saved -> ""
            is Result.Rejected -> result.reason
        }

    /** Same as [persist], returning the registry outcome. */
    fun persistResult(workspace: Workspace, kind: String, payloadJson: String, now: Long): Result {
        return try {
            val raw = runCatching { JSONObject(payloadJson) }.getOrNull()
                ?: return Result.Rejected("payload is not JSON")
            val put: Registry.Put<*> = when (kind) {
                "person" -> upsert(workspace.people, { Person.fromJson(it) }, raw, now, kind)
                "company" -> upsert(workspace.companies, { Company.fromJson(it) }, raw, now, kind)
                "job_posting" -> upsert(workspace.jobPostings, { JobPosting.fromJson(it) }, raw, now, kind)
                "application" -> upsert(workspace.applications, { JobApplication.fromJson(it) }, raw, now, kind)
                "task" -> upsert(workspace.tasks, { Task.fromJson(it) }, raw, now, kind)
                "draft" -> upsert(workspace.drafts, { ContentDraft.fromJson(it) }, raw, now, kind)
                "post" -> upsert(workspace.posts, { SocialPost.fromJson(it) }, raw, now, kind)
                "project" -> upsert(workspace.projects, { Project.fromJson(it) }, raw, now, kind)
                "note" -> upsert(workspace.notes, { Note.fromJson(it) }, raw, now, kind)
                else -> return Result.Rejected("kind '$kind' cannot be saved")
            }
            when (put) {
                is Registry.Put.Created<*> -> Result.Saved("created")
                is Registry.Put.Reused<*> -> Result.Saved("reused")
                is Registry.Put.Bumped<*> -> Result.Saved("bumped")
                is Registry.Put.Failed<*> -> Result.Rejected(put.reason)
            }
        } catch (e: RuntimeException) {
            Result.Rejected("could not save '$kind': ${e.javaClass.simpleName}")
        }
    }

    private fun <T> upsert(
        registry: Registry<T>,
        decode: (JSONObject) -> T?,
        raw: JSONObject,
        now: Long,
        kind: String,
    ): Registry.Put<*> {
        val item = decode(raw) ?: return Registry.Put.Failed<T>("invalid $kind")
        return registry.upsert(item, now)
    }
}
