package app.memo.core.registry

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.ArchitecturalPrinciple
import app.memo.core.domain.ChannelAccount
import app.memo.core.domain.ContentPreference
import app.memo.core.domain.DraftKind
import app.memo.core.domain.JobApplication
import app.memo.core.domain.JobPosting
import app.memo.core.domain.JobApplicationState
import app.memo.core.domain.MetricSample
import app.memo.core.domain.Note
import app.memo.core.domain.Person
import app.memo.core.domain.Project
import app.memo.core.domain.PromptTemplate
import app.memo.core.domain.ProviderAdapter
import app.memo.core.domain.SocialPost
import app.memo.core.domain.Task
import app.memo.core.domain.TaskState
import app.memo.core.domain.WorkflowTemplate
import org.json.JSONObject

/**
 * The eight v1 registry schemes and the eight v2 knowledge and library
 * schemes. Each one states its natural key rule and its bookkeeping stamps;
 * everything else is delegated to the domain codecs.
 */
object Schemes {

    object People : RegScheme<Person> {
        override fun id(item: Person): String = NaturalKeys.person(item.fullName, item.phone)
        override fun toJson(item: Person): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Person? = Person.fromJson(raw)
        override fun versionOf(item: Person): Int = item.version
        override fun stamped(item: Person, existing: Person?, now: Long, nextVersion: Int): Person =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Companies : RegScheme<Company> {
        override fun id(item: Company): String = NaturalKeys.company(item.domain)
        override fun toJson(item: Company): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Company? = Company.fromJson(raw)
        override fun versionOf(item: Company): Int = item.version
        override fun stamped(item: Company, existing: Company?, now: Long, nextVersion: Int): Company =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object JobPostings : RegScheme<JobPosting> {
        override fun id(item: JobPosting): String = NaturalKeys.job(item.companyKey, item.role)
        override fun toJson(item: JobPosting): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): JobPosting? = JobPosting.fromJson(raw)
        override fun versionOf(item: JobPosting): Int = item.version
        override fun stamped(item: JobPosting, existing: JobPosting?, now: Long, nextVersion: Int): JobPosting =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Applications : RegScheme<JobApplication> {
        override fun id(item: JobApplication): String = NaturalKeys.application(item.jobKey)
        override fun toJson(item: JobApplication): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): JobApplication? = JobApplication.fromJson(raw)
        override fun versionOf(item: JobApplication): Int = item.version
        override fun stamped(item: JobApplication, existing: JobApplication?, now: Long, nextVersion: Int): JobApplication =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Tasks : RegScheme<Task> {
        override fun id(item: Task): String = NaturalKeys.task(item.title, item.dueAt)
        override fun toJson(item: Task): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Task? = Task.fromJson(raw)
        override fun versionOf(item: Task): Int = item.version
        override fun stamped(item: Task, existing: Task?, now: Long, nextVersion: Int): Task =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Drafts : RegScheme<ContentDraft> {
        /** Natural key: kind + anchor (title-slug, else body head) + creation day. */
        override fun id(item: ContentDraft): String {
            val day = NaturalKeys.dayBucket(item.createdAt)
            return NaturalKeys.draft(item.kind.name, item.title, item.body.take(48), day)
        }
        override fun toJson(item: ContentDraft): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): ContentDraft? = ContentDraft.fromJson(raw)
        override fun versionOf(item: ContentDraft): Int = item.version
        override fun stamped(item: ContentDraft, existing: ContentDraft?, now: Long, nextVersion: Int): ContentDraft =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: item.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Posts : RegScheme<SocialPost> {
        override fun id(item: SocialPost): String = NaturalKeys.post(item.url)
        override fun toJson(item: SocialPost): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): SocialPost? = SocialPost.fromJson(raw)
        override fun versionOf(item: SocialPost): Int = item.version
        override fun stamped(item: SocialPost, existing: SocialPost?, now: Long, nextVersion: Int): SocialPost =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Samples : RegScheme<MetricSample> {
        override fun id(item: MetricSample): String = NaturalKeys.metric(item.postKey, item.metric, item.capturedAt)
        override fun toJson(item: MetricSample): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): MetricSample? = MetricSample.fromJson(raw)
        override fun versionOf(item: MetricSample): Int = item.version
        override fun stamped(item: MetricSample, existing: MetricSample?, now: Long, nextVersion: Int): MetricSample =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Projects : RegScheme<Project> {
        override fun id(item: Project): String = NaturalKeys.project(item.name)
        override fun toJson(item: Project): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Project? = Project.fromJson(raw)
        override fun versionOf(item: Project): Int = item.version
        override fun stamped(item: Project, existing: Project?, now: Long, nextVersion: Int): Project =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Notes : RegScheme<Note> {
        /** Natural key: project + title-slug + creation day (the item's own createdAt, as Drafts does). */
        override fun id(item: Note): String = NaturalKeys.note(item.projectKey, item.title, item.createdAt)
        override fun toJson(item: Note): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Note? = Note.fromJson(raw)
        override fun versionOf(item: Note): Int = item.version
        override fun stamped(item: Note, existing: Note?, now: Long, nextVersion: Int): Note =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: item.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object WorkflowTemplates : RegScheme<WorkflowTemplate> {
        override fun id(item: WorkflowTemplate): String = NaturalKeys.workflowTemplate(item.name)
        override fun toJson(item: WorkflowTemplate): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): WorkflowTemplate? = WorkflowTemplate.fromJson(raw)
        override fun versionOf(item: WorkflowTemplate): Int = item.version
        override fun stamped(item: WorkflowTemplate, existing: WorkflowTemplate?, now: Long, nextVersion: Int): WorkflowTemplate =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object PromptTemplates : RegScheme<PromptTemplate> {
        override fun id(item: PromptTemplate): String = NaturalKeys.promptTemplate(item.name)
        override fun toJson(item: PromptTemplate): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): PromptTemplate? = PromptTemplate.fromJson(raw)
        override fun versionOf(item: PromptTemplate): Int = item.version
        override fun stamped(item: PromptTemplate, existing: PromptTemplate?, now: Long, nextVersion: Int): PromptTemplate =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object Principles : RegScheme<ArchitecturalPrinciple> {
        override fun id(item: ArchitecturalPrinciple): String = NaturalKeys.principle(item.title)
        override fun toJson(item: ArchitecturalPrinciple): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): ArchitecturalPrinciple? = ArchitecturalPrinciple.fromJson(raw)
        override fun versionOf(item: ArchitecturalPrinciple): Int = item.version
        override fun stamped(item: ArchitecturalPrinciple, existing: ArchitecturalPrinciple?, now: Long, nextVersion: Int): ArchitecturalPrinciple =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object ContentPreferences : RegScheme<ContentPreference> {
        override fun id(item: ContentPreference): String = NaturalKeys.preference(item.scope)
        override fun toJson(item: ContentPreference): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): ContentPreference? = ContentPreference.fromJson(raw)
        override fun versionOf(item: ContentPreference): Int = item.version
        override fun stamped(item: ContentPreference, existing: ContentPreference?, now: Long, nextVersion: Int): ContentPreference =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object ChannelAccounts : RegScheme<ChannelAccount> {
        override fun id(item: ChannelAccount): String = NaturalKeys.channelAccount(item.platform, item.handle)
        override fun toJson(item: ChannelAccount): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): ChannelAccount? = ChannelAccount.fromJson(raw)
        override fun versionOf(item: ChannelAccount): Int = item.version
        override fun stamped(item: ChannelAccount, existing: ChannelAccount?, now: Long, nextVersion: Int): ChannelAccount =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }

    object ProviderAdapters : RegScheme<ProviderAdapter> {
        override fun id(item: ProviderAdapter): String = NaturalKeys.adapter(item.channel, item.targetId)
        override fun toJson(item: ProviderAdapter): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): ProviderAdapter? = ProviderAdapter.fromJson(raw)
        override fun versionOf(item: ProviderAdapter): Int = item.version
        override fun stamped(item: ProviderAdapter, existing: ProviderAdapter?, now: Long, nextVersion: Int): ProviderAdapter =
            item.copy(
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
                version = nextVersion,
            )
    }
}
