package app.memo.core.registry

import app.memo.core.codec.SynCodec

/**
 * The persistent work graph: one object, eight registries, serialized as a
 * SYN envelope. Used by `MemoVault` (per-collection files), backups and
 * tests. Playback parse -> quarantine -> serialize round-trips every row.
 */
class Workspace {

    val people = Registry("people", Schemes.People)
    val companies = Registry("companies", Schemes.Companies)
    val jobPostings = Registry("job_postings", Schemes.JobPostings)
    val applications = Registry("applications", Schemes.Applications)
    val tasks = Registry("tasks", Schemes.Tasks)
    val drafts = Registry("drafts", Schemes.Drafts)
    val posts = Registry("posts", Schemes.Posts)
    val samples = Registry("metric_samples", Schemes.Samples)

    /** registries in canonical order (serialization + UI pickers agree with this) */
    fun all(): List<Registry<*>> = listOf(
        people, companies, jobPostings, applications, tasks, drafts, posts, samples,
    )

    fun quarantineCount(): Int = all().sumOf { it.quarantinedRows().size }

    fun serialize(exportedAt: Long): String = SynCodec.serialize(this, exportedAt)

    companion object {
        fun parse(text: String): SynCodec.ParseOutcome = SynCodec.parse(text)
    }
}
