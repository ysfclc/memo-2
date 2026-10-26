package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONObject

/**
 * A job opening seen at a company. Keyed by (company, role) so re-encounters
 * reuse the existing posting record.
 */
data class JobPosting(
    val companyKey: String = "",
    val role: String = "",
    val url: String = "",
    val summary: String = "",           // short-form notes: scope, comp, team
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("companyKey", companyKey)
        .put("role", role)
        .put("url", url)
        .put("summary", summary)
        .put("notes", notes)
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
        companyKey.isBlank() ||
            role.isBlank() ||
            role.length > Limits.MAX_NAME_CHARS ||
            url.length > Limits.MAX_METRIC_TARGET_CHARS ||
            summary.length > Limits.MAX_NOTES_CHARS ||
            notes.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): JobPosting? {
            val companyKey = raw.optString("companyKey")
            val role = raw.optString("role")
            if (companyKey.isBlank() || role.isBlank()) return null
            return JobPosting(
                companyKey = companyKey,
                role = role,
                url = raw.optString("url"),
                summary = raw.optString("summary"),
                notes = raw.optString("notes"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/** Where the user stands in a hiring pipeline for one [JobPosting]. */
enum class JobApplicationState { NEW, APPLIED, INTERVIEWING, OFFER, REJECTED, CLOSED }

/**
 * The user's application for one [JobPosting]. Natural key = the posting,
 * so a posting has at most one application row.
 */
data class JobApplication(
    val jobKey: String = "",
    val state: JobApplicationState = JobApplicationState.NEW,
    val appliedAt: Long = 0,
    val contactKey: String = "",      // Person who referred/replied
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("jobKey", jobKey)
        .put("state", state.name)
        .put("appliedAt", appliedAt)
        .put("contactKey", contactKey)
        .put("notes", notes)
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
        jobKey.isBlank() ||
            notes.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): JobApplication? {
            val jobKey = raw.optString("jobKey")
            if (jobKey.isBlank()) return null
            val state = runCatching {
                JobApplicationState.valueOf(raw.optString("state", "NEW"))
            }.getOrDefault(JobApplicationState.NEW)
            return JobApplication(
                jobKey = jobKey,
                state = state,
                appliedAt = raw.optLong("appliedAt", 0L),
                contactKey = raw.optString("contactKey"),
                notes = raw.optString("notes"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * A personal task. Optional dueAt drives ReminderWorker; notifications are
 * content-free (Threadline law): "Memo reminder" without leaking prose.
 */
enum class TaskState { OPEN, DONE, CANCELLED }

data class Task(
    val title: String = "",
    val state: TaskState = TaskState.OPEN,
    val dueAt: Long = 0,
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("state", state.name)
        .put("dueAt", dueAt)
        .put("notes", notes)
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
            title.length > Limits.MAX_TASK_TITLE_CHARS ||
            notes.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): Task? {
            val title = raw.optString("title")
            if (title.isBlank()) return null
            val state = runCatching {
                TaskState.valueOf(raw.optString("state", "OPEN"))
            }.getOrDefault(TaskState.OPEN)
            return Task(
                title = title,
                state = state,
                dueAt = raw.optLong("dueAt", 0L),
                notes = raw.optString("notes"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}
