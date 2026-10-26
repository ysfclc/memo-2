package app.memo.core.audit

import app.memo.core.Limits
import app.memo.core.domain.AuditEntry
import app.memo.core.domain.OutboundEvent
import app.memo.core.redact.Redactor
import app.memo.core.util.Sha256Hex

/**
 * Append-only, SHA-256-chained outbound audit (product law: nothing leaves
 * the device silently). Each link hashes a canonical, redaction-scrubbed
 * line of the event plus the previous hash; the first link chains to
 * `Limits.GENESIS_HASH` (64 zeros). `verify()` recomputes the whole chain.
 */
fun interface AuditSink {
    /** Receives the FULL current chain snapshot after every append. */
    fun persist(entries: List<AuditEntry>)
}

/** Pure in-memory sink (tests; app layer supplies a file-backed one). */
class InMemoryAuditSink : AuditSink {
    val received = mutableListOf<List<AuditEntry>>()
    override fun persist(entries: List<AuditEntry>) {
        received += entries.toList()
    }
}

class HashChainAudit(
    private val sink: AuditSink,
    restored: List<AuditEntry> = emptyList(),
) {

    private val links = mutableListOf<AuditEntry>().apply { addAll(restored) }

    val size: Int get() = links.size

    fun entries(): List<AuditEntry> = links.toList()

    fun lastHash(): String = links.lastOrNull()?.hash ?: Limits.GENESIS_HASH

    /**
     * Append one outbound event. Action text passes the Redactor first
     * (audit logs must not become a secret store).
     */
    fun append(event: OutboundEvent, now: Long): AuditEntry {
        require(event.target.isNotBlank() && event.action.isNotBlank()) {
            "outbound event needs target and action"
        }
        val scrubbedAction = Redactor.redact(event.action).text
        val scrubbedTarget = Redactor.redact(event.target).text
        val seq = if (links.isEmpty()) 0L else links.last().seq + 1
        val prev = lastHash()
        val candidate = AuditEntry(
            seq = seq,
            ts = now,
            event = event.copy(target = scrubbedTarget, action = scrubbedAction),
            prevHash = prev,
            hash = "",
        )
        val hash = Sha256Hex.hex(candidate.linkCanonical())
        val final = candidate.copy(hash = hash)
        links += final
        sink.persist(entries())
        return final
    }

    data class VerifyResult(val ok: Boolean, val checked: Int, val reason: String)

    /** Recompute every link; first failure stops with its index. */
    fun verify(): VerifyResult {
        var expectedPrev = Limits.GENESIS_HASH
        for (i in links.indices) {
            val link = links[i]
            if (link.seq != i.toLong()) {
                return VerifyResult(false, i, "sequence break at link $i (seq ${link.seq})")
            }
            if (link.prevHash != expectedPrev) {
                return VerifyResult(false, i, "chain break at link $i")
            }
            if (link.hash != Sha256Hex.hex(link.linkCanonical())) {
                return VerifyResult(false, i, "hash mismatch at link $i")
            }
            expectedPrev = link.hash
        }
        return VerifyResult(true, links.size, "")
    }
}
