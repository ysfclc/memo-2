package app.memo.vault

import app.memo.core.Limits
import app.memo.core.codec.SynCodec
import app.memo.core.registry.Workspace

/** Durable workspace snapshot codec boundary. */
object MemoVault {
    fun save(workspace: Workspace, out: java.io.OutputStream) {
        val json = SynCodec.serialize(workspace, System.currentTimeMillis())
        out.write(json.toByteArray(Charsets.UTF_8))
    }

    fun load(input: java.io.InputStream): Workspace? {
        return when (val result = SynCodec.parse(String(input.readBytes(), Charsets.UTF_8))) {
            is SynCodec.ParseOutcome.Ok -> result.workspace
            is SynCodec.ParseOutcome.Rejected -> null
        }
    }

    fun ioFile(context: android.content.Context, name: String): java.io.File =
        java.io.File(context.filesDir, name)

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
