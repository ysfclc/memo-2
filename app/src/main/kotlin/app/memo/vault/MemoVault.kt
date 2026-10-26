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

    /** Write the current Workspace to a Syn envelope file. */
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
            is SynCodec.ParseOutcome.Rejected -> null
        }
    }

    /** Registry kind labels used by the app layer. */
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