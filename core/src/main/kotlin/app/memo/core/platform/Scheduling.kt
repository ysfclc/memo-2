package app.memo.core.platform

import org.json.JSONException
import org.json.JSONObject

/**
 * A deferred or recurring unit of work handed to the platform scheduler.
 *
 * - [id] is stable and matches `^[a-z0-9_.-]{1,64}$`. Scheduling an existing id
 *   replaces that job.
 * - [kind] names the handler and matches `^[a-z][a-z0-9_]{0,31}$`.
 * - [runAtMillis] is the first run time, in epoch milliseconds.
 * - [repeatEveryMillis] is null for a one-shot job. Otherwise it is at least
 *   [MIN_REPEAT_MILLIS], the platform's periodic minimum.
 * - [requiresNetwork] defers the run until the device is online.
 * - [payloadJson] is a JSON object passed to the handler. It must not contain
 *   secrets: the scheduler persists it in plain storage.
 */
data class ScheduledJob(
    val id: String,
    val kind: String,
    val runAtMillis: Long,
    val repeatEveryMillis: Long?,
    val requiresNetwork: Boolean,
    val payloadJson: String,
) {
    init {
        require(ID_PATTERN.matches(id)) { "job id must match ^[a-z0-9_.-]{1,64}$" }
        require(KIND_PATTERN.matches(kind)) { "job kind must match ^[a-z][a-z0-9_]{0,31}$" }
        require(runAtMillis >= 0) { "runAtMillis must be >= 0" }
        require(repeatEveryMillis == null || repeatEveryMillis >= MIN_REPEAT_MILLIS) {
            "repeatEveryMillis must be null or at least $MIN_REPEAT_MILLIS"
        }
        try {
            JSONObject(payloadJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("payloadJson must be a JSON object", e)
        }
    }

    companion object {
        /** Shortest allowed repeat interval: 15 minutes, the platform's periodic minimum. */
        const val MIN_REPEAT_MILLIS: Long = 15L * 60_000L

        private val ID_PATTERN = Regex("^[a-z0-9_.-]{1,64}$")
        private val KIND_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

/**
 * Hands [ScheduledJob]s to the platform. The implementation must survive
 * process death and device reboot: a scheduled job runs even when Memo is not
 * in the foreground. Jobs are not run synchronously from [schedule].
 */
interface Scheduler {
    /** Schedules [job], replacing any job with the same id. */
    fun schedule(job: ScheduledJob)

    /** Cancels the job with [id]. Unknown ids are ignored. */
    fun cancel(id: String)
}
