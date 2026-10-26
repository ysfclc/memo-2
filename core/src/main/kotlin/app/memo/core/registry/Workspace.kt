package app.memo.core.registry

import app.memo.core.codec.SynCodec

/**
 * The persistent work graph: one object, sixteen registries (eight v1 work
 * and content registries plus eight v2 knowledge and library registries),
 * serialized as a SYN envelope. Used by `MemoVault` (per-collection files),
 * backups and tests. Playback parse -> quarantine -> serialize round-trips
 * every row.
 */
class Workspace {

    // ---- v1 registries -------------------------------------------------
    val people = Registry("people", Schemes.People)
    val companies = Registry("companies", Schemes.Companies)
    val jobPostings = Registry("job_postings", Schemes.JobPostings)
    val applications = Registry("applications", Schemes.Applications)
    val tasks = Registry("tasks", Schemes.Tasks)
    val drafts = Registry("drafts", Schemes.Drafts)
    val posts = Registry("posts", Schemes.Posts)
    val samples = Registry("metric_samples", Schemes.Samples)

    // ---- v2 registries (domain v2: knowledge and library) --------------
    val projects = Registry("projects", Schemes.Projects)
    val notes = Registry("notes", Schemes.Notes)
    val workflowTemplates = Registry("workflow_templates", Schemes.WorkflowTemplates)
    val promptTemplates = Registry("prompt_templates", Schemes.PromptTemplates)
    val principles = Registry("principles", Schemes.Principles)
    val contentPreferences = Registry("content_preferences", Schemes.ContentPreferences)
    val channelAccounts = Registry("channel_accounts", Schemes.ChannelAccounts)
    val providerAdapters = Registry("provider_adapters", Schemes.ProviderAdapters)

    /**
     * Registries in canonical order: the eight v1 registries first, then the
     * eight v2 registries. Serialization and UI pickers agree with this order.
     * Each registry's [Registry.kind] is its key in the envelope `registries` object.
     */
    fun all(): List<Registry<*>> = listOf(
        people, companies, jobPostings, applications, tasks, drafts, posts, samples,
        projects, notes, workflowTemplates, promptTemplates, principles,
        contentPreferences, channelAccounts, providerAdapters,
    )

    fun quarantineCount(): Int = all().sumOf { it.quarantinedRows().size }

    /**
     * Data from a same-version envelope that this build does not understand:
     * unknown registry arrays, and malformed quarantine entries. Filled by the
     * codec on parse and written back unchanged on serialize, so a newer or
     * damaged file is never silently shrunk by an older save.
     */
    internal var carriedRegistries: org.json.JSONObject = org.json.JSONObject()
    internal var carriedQuarantine: org.json.JSONObject = org.json.JSONObject()

    fun serialize(exportedAt: Long): String = SynCodec.serialize(this, exportedAt)

    companion object {
        fun parse(text: String): SynCodec.ParseOutcome = SynCodec.parse(text)
    }
}
