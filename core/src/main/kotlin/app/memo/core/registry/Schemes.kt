package app.memo.core.registry

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.DraftKind
import app.memo.core.domain.JobApplication
import app.memo.core.domain.JobPosting
import app.memo.core.domain.JobApplicationState
import app.memo.core.domain.MetricSample
import app.memo.core.domain.Person
import app.memo.core.domain.SocialPost
import app.memo.core.domain.Task
import app.memo.core.domain.TaskState
import org.json.JSONObject

/**
 * The eight v1 registry schemes. Each one states its natural key rule and
 * its bookkeeping stamps; everything else is delegated to the domain codecs.
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
}
