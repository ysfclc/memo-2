package app.memo.core.platform

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Bounds shared by the external channels. */
object ChannelLimits {
    /** Shortest allowed task timeout. */
    const val MIN_TIMEOUT_MS: Long = 1_000L

    /** Longest allowed task timeout (thinking-model consults are capped at 300 s). */
    const val MAX_TIMEOUT_MS: Long = 300_000L
}

/**
 * One step executed in a Memo-owned web session.
 *
 * [profileId] selects the WebView profile (cookies and login state) for one
 * service. [envelopeJson] is the approved action envelope as a JSON object; the
 * channel must refuse any action not in it. [timeoutMs] is the wall-clock cap
 * for the whole task, within [ChannelLimits].
 */
data class WebTask(
    val runId: String,
    val stepId: String,
    val profileId: String,
    val envelopeJson: String,
    val timeoutMs: Long,
) {
    init {
        requireId(runId, "runId")
        requireId(stepId, "stepId")
        requireId(profileId, "profileId")
        requireJsonObject(envelopeJson, "envelopeJson")
        requireTimeout(timeoutMs)
    }
}

/**
 * One step executed by native app automation on this device.
 *
 * [packageName] is the Android package the envelope targets. The channel must
 * refuse to act on any other foreground package. [envelopeJson] and
 * [timeoutMs] behave as in [WebTask].
 */
data class AppTask(
    val runId: String,
    val stepId: String,
    val packageName: String,
    val envelopeJson: String,
    val timeoutMs: Long,
) {
    init {
        requireId(runId, "runId")
        requireId(stepId, "stepId")
        require(PACKAGE_PATTERN.matches(packageName)) { "invalid package name: $packageName" }
        requireJsonObject(envelopeJson, "envelopeJson")
        requireTimeout(timeoutMs)
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}

/** Outcome of an external task, delivered through [ExternalCallback.deliver]. */
sealed class ExternalResult {
    /**
     * The task finished. [output] is the extracted text or JSON result.
     * [evidenceJson] is a JSON array of evidence items, `[]` when there are none.
     */
    data class Done(val output: String, val evidenceJson: String = "[]") : ExternalResult() {
        init {
            try {
                JSONArray(evidenceJson)
            } catch (e: JSONException) {
                throw IllegalArgumentException("evidenceJson must be a JSON array", e)
            }
        }
    }

    /** The task failed. [reason] must not contain page or app content. */
    data class Failed(val reason: String) : ExternalResult() {
        init {
            require(reason.isNotBlank()) { "failure reason must not be blank" }
        }
    }

    /**
     * The task needs the user: login, captcha, 2FA, an unexpected page or a
     * deviation from the envelope. The step pauses for takeover.
     */
    data class NeedsUser(val reason: String) : ExternalResult() {
        init {
            require(reason.isNotBlank()) { "takeover reason must not be blank" }
        }
    }
}

/**
 * Receives the final result of a started task. Implemented by core's
 * orchestration layer, which calls `WorkflowEngine.deliverExternal` with it.
 *
 * [deliver] is called exactly once per ticket, on a channel-owned thread,
 * never synchronously from inside [WebChannel.start] or [AppChannel.start].
 * It must not block.
 */
interface ExternalCallback {
    /** Delivers [result] for the task started under [ticket]. */
    fun deliver(ticket: String, result: ExternalResult)
}

/**
 * Executes [WebTask]s in Memo's own WebView profiles, headless where possible.
 *
 * - [start] queues the task and returns a non-blank ticket. The result
 *   arrives later through [callback]. If the task cannot be queued, [start]
 *   throws.
 * - [cancel] stops a task. Unknown or finished tickets are ignored. The task
 *   then delivers [ExternalResult.Failed] unless it already delivered a result.
 * - [reveal] moves the task's live view to the foreground so the user can
 *   take over. The task keeps its ticket and does not restart. Unknown tickets
 *   are ignored.
 */
interface WebChannel {
    /** Queues [task] and returns its ticket. Results are delivered to [callback]. */
    fun start(task: WebTask, callback: ExternalCallback): String

    /** Cancels the task with [ticket]. Unknown or finished tickets are ignored. */
    fun cancel(ticket: String)

    /** Brings the task's view forward for visible takeover. Unknown tickets are ignored. */
    fun reveal(ticket: String)
}

/**
 * Executes [AppTask]s with native app automation on this device. Same
 * contract as [WebChannel]. Because stock Android accessibility only acts on
 * the foreground app, a task may need the user's screen and resolve to
 * [ExternalResult.NeedsUser] until the target app is on screen.
 */
interface AppChannel {
    /** Queues [task] and returns its ticket. Results are delivered to [callback]. */
    fun start(task: AppTask, callback: ExternalCallback): String

    /** Cancels the task with [ticket]. Unknown or finished tickets are ignored. */
    fun cancel(ticket: String)

    /** Brings the task's target app forward for visible takeover. Unknown tickets are ignored. */
    fun reveal(ticket: String)
}

private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")

private fun requireId(value: String, field: String) {
    require(ID_PATTERN.matches(value)) { "$field must be 1..128 safe id characters" }
}

private fun requireTimeout(timeoutMs: Long) {
    require(timeoutMs in ChannelLimits.MIN_TIMEOUT_MS..ChannelLimits.MAX_TIMEOUT_MS) {
        "timeoutMs must be in ${ChannelLimits.MIN_TIMEOUT_MS}..${ChannelLimits.MAX_TIMEOUT_MS}"
    }
}

private fun requireJsonObject(json: String, field: String) {
    try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw IllegalArgumentException("$field must be a JSON object", e)
    }
}
