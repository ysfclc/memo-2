package app.memo.vault

import app.memo.core.codec.SynCodec
import app.memo.core.registry.Workspace
import app.memo.core.Limits

/**
 * Persistence layer for the Workspace (8 registries).
 * Serializes via the SYN envelope (strict validate → migrate → load);
 * on startup verifies the hash chain and restores or quarantines.
 * All work goes through CanonicalJson so content signatures are
 * deterministic and hash-chain-stable.
 */
object MemoVault {

    /** Serializable snapshot of the whole work graph. */
    data class Snapshot(
        val people: SynCodec.Workspace.PersonSnapshot,
        val companies: SynCodec.Workspace.CompanySnapshot,
        val tasks: SynCodec.Workspace.TaskSnapshot,
        val drafts: SynCodec.Workspace.DraftSnapshot,
        val posts: SynCodec.Workspace.PostSnapshot,
        val samples: SynCodec.Workspace.SampleSnapshot,
        val applications: SynCodec.Workspace.ApplicationSnapshot,
        val jobPostings: SynCodec.Workspace.JobPostingSnapshot,
    )

    /** Write the current Workspace to a Syn envelope file.
     *  Every append hands the full chain to the audit sink — the app’s
     *  persistent sink must grow as [1,2,3,…,n].
     */
    fun save(workspace: Workspace, out: java.io.OutputStream) {
        val json = SynCodec.serialize(workspace, Limits.EXPORTED_AT)
        out.write(json.toByteArray(Charsets.UTF_8))
    }

    /** Read a Syn envelope and reconstruct a Workspace.
     *  If the envelope is rejected (bad format / version) the caller keeps
     *  the old copy — the codec never merges partial garbage against good data.
     *  Individual bad rows quarantine per registry law.
     */
    fun load(in: java.io.InputStream): Workspace? {
        val json = String(in.readBytes(), Charsets.UTF_8)
        val result = SynCodec.parse(json)
        return when (result) {
            is SynCodec.ParseOutcome.Ok<Workspace> -> result.workspace
            is SynCodec.ParseOutcome.Rejected -> {
                // keep old workspace; the app must not silently merge bad data
                null
            }
        }
    }

    /** Convenient DTOs for the SYN serialization (kept in sync with Workspace). */
    object SnapshotKinds {
        const val PEOPLE = "people"
        const val COMPANIES = "companies"
        const val TASKS = "tasks"
        const val DRAFTS = "drafts"
        const val POSTS = "posts"
        const val SAMPLES = "samples"
        const val APPLICATIONS = "applications"
        const val JOB_POSTINGS = "job_postings"
    }
}

/** Minimal SYN snapshot types that the app can read/write without pulling
 * the whole core dependency graph.  The app only needs these DTOs to
 * persist/restore the work graph between launches. */
private use:java.io.Serializable data class PersonSnapshot(
    val fullName: String,
    val email: String,
    val phone: String,
    val companyKey: String,
    val role: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class CompanySnapshot(
    val domain: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class TaskSnapshot(
    val title: String,
    val role: String,
    val url: String,
    val summary: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class DraftSnapshot(
    val kind: String,
    val title: String,
    val body: String,
    val state: String,
    val audienceKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class PostSnapshot(
    val url: String,
    val audienceKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class SampleSnapshot(
    val postKey: String,
    val metric: String,
    val capturedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class ApplicationSnapshot(
    val companyKey: String,
    val role: String,
    val url: String,
    val summary: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) private use:java.io.Serializable data class JobPostingSnapshot(
    val companyKey: String,
    val role: String,
    val url: String,
    val summary: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
)