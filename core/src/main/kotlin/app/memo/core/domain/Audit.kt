package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONObject

/** Channels that can speak outside the device. Channel toggles gate them. */
enum class OutboundChannel { FRONTIER_SITE, AI_APP, API_OPTIN, NOTIFICATION }

/**
 * What a step did outside the device, in one line. `contentHash` binds the
 * artifact (blank for non-artifact egress like a status notification).
 */
data class OutboundEvent(
    val runId: String = "",
    val stepId: String = "",
    val channel: OutboundChannel = OutboundChannel.API_OPTIN,
    val target: String = "",
    val action: String = "",
    val contentHash: String = "",
) {
    fun converges(): Boolean = target.isNotBlank() && action.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("runId", runId)
        .put("stepId", stepId)
        .put("channel", channel.name)
        .put("target", target)
        .put("action", action)
        .put("contentHash", contentHash)

    companion object {
        fun fromJson(raw: JSONObject): OutboundEvent? {
            val target = raw.optString("target")
            val action = raw.optString("action")
            if (target.isBlank() || action.isBlank()) return null
            val channel = runCatching {
                OutboundChannel.valueOf(raw.optString("channel", "API_OPTIN"))
            }.getOrDefault(OutboundChannel.API_OPTIN)
            return OutboundEvent(
                runId = raw.optString("runId"),
                stepId = raw.optString("stepId"),
                channel = channel,
                target = target.take(Limits.AUDIT_TARGET_MAX_CHARS),
                action = action.take(Limits.AUDIT_ACTION_MAX_CHARS),
                contentHash = raw.optString("contentHash"),
            )
        }
    }
}

/** One immutable link of the outbound audit chain. */
data class AuditEntry(
    val seq: Long = 0,
    val ts: Long = 0,
    val event: OutboundEvent = OutboundEvent(),
    val prevHash: String = "",
    val hash: String = "",
) {
    /**
     * What the chain hash absorbs: every field except `hash` itself.
     * Changing any byte after the fact breaks verification.
     */
    fun linkCanonical(): String = CanonicalAudit.encode(this)

    fun toJson(): JSONObject = JSONObject()
        .put("seq", seq)
        .put("ts", ts)
        .put("event", event.toJson())
        .put("prevHash", prevHash)
        .put("hash", hash)

    companion object {
        fun fromJson(raw: JSONObject): AuditEntry? {
            val eventRaw = raw.optJSONObject("event") ?: return null
            val event = OutboundEvent.fromJson(eventRaw) ?: return null
            return AuditEntry(
                seq = raw.optLong("seq", 0L),
                ts = raw.optLong("ts", 0L),
                event = event,
                prevHash = raw.optString("prevHash"),
                hash = raw.optString("hash"),
            )
        }
    }
}

// Small isolated canonical encoder for audit links (one place to test).
internal object CanonicalAudit {
    fun encode(entry: AuditEntry): String =
        "${entry.seq}|${entry.ts}|${entry.event.runId}|${entry.event.stepId}|" +
            entry.event.channel.name + "|" + entry.event.target + "|" +
            entry.event.action + "|" + entry.event.contentHash + "|" + entry.prevHash
}
