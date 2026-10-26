package app.memo.core.audit

import app.memo.core.Limits
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HashChainAuditTest {

    private fun event(target: String, action: String): OutboundEvent = OutboundEvent(
        runId = "run-1",
        stepId = "send",
        channel = OutboundChannel.FRONTIER_SITE,
        target = target,
        action = action,
        contentHash = "abc",
    )

    @Test
    fun appendsLinkedChainAndVerifies() {
        val sink = InMemoryAuditSink()
        val audit = HashChainAudit(sink)
        val first = audit.append(event("grok.com", "completed: Send"), 1_000L)
        val second = audit.append(event("claude.ai", "completed: Draft"), 2_000L)
        val third = audit.append(event("gemini", "completed: Reply"), 3_000L)

        assertEquals(3, audit.size)
        assertEquals(0L, first.seq)
        assertEquals(Limits.GENESIS_HASH, first.prevHash)
        assertEquals(first.hash, second.prevHash)
        assertEquals(second.hash, third.prevHash)

        val result = audit.verify()
        assertTrue(result.ok, result.reason)
        assertEquals(3, result.checked)

        // Snapshot discipline: every append hands the FULL chain to the sink.
        assertEquals(listOf(1, 2, 3), sink.received.map { it.size })
        assertEquals(3, sink.received.last().size)
    }

    @Test
    fun restoredChainVerifiesStable() {
        val original = HashChainAudit(InMemoryAuditSink())
        original.append(event("grok.com", "completed: A"), 10L)
        original.append(event("grok.com", "completed: B"), 20L)
        val restored = HashChainAudit(InMemoryAuditSink(), restored = original.entries())
        assertTrue(restored.verify().ok)
        assertEquals(2, restored.size)
        assertEquals(original.lastHash(), restored.lastHash())
    }

    @Test
    fun tamperingBreaksVerification() {
        val original = HashChainAudit(InMemoryAuditSink())
        original.append(event("grok.com", "completed: A"), 10L)
        original.append(event("grok.com", "completed: B"), 20L)
        val links = original.entries()

        // Action tamper -> recomputed hash differs.
        val tamperedAction = links.mapIndexed { i, link ->
            if (i == 1) link.copy(event = link.event.copy(action = "TAMPERED")) else link
        }
        val actionResult = HashChainAudit(InMemoryAuditSink(), restored = tamperedAction).verify()
        assertFalse(actionResult.ok)
        assertTrue(actionResult.reason.contains("hash mismatch"), actionResult.reason)

        // Prev-hash tamper -> chain break detected before the hash check.
        val tamperedPrev = links.mapIndexed { i, link ->
            if (i == 1) link.copy(prevHash = "f".repeat(64)) else link
        }
        val prevResult = HashChainAudit(InMemoryAuditSink(), restored = tamperedPrev).verify()
        assertFalse(prevResult.ok)
        assertTrue(prevResult.reason.contains("chain break"), prevResult.reason)

        // Sequence tamper -> sequence break.
        val tamperedSeq = links.mapIndexed { i, link ->
            if (i == 1) link.copy(seq = 5L) else link
        }
        val seqResult = HashChainAudit(InMemoryAuditSink(), restored = tamperedSeq).verify()
        assertFalse(seqResult.ok)
        assertTrue(seqResult.reason.contains("sequence break"), seqResult.reason)

        // A TRUNCATED chain (dropped tail) still verifies — tail-loss detection
        // is the sink's job (full-snapshot persist exists to prevent it).
        val truncated = links.take(1)
        assertTrue(HashChainAudit(InMemoryAuditSink(), restored = truncated).verify().ok)
    }

    @Test
    fun eventRequiresTargetAndAction() {
        val audit = HashChainAudit(InMemoryAuditSink())
        assertFailsWith<IllegalArgumentException> {
            audit.append(OutboundEvent(channel = OutboundChannel.AI_APP, target = "x", action = ""), 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            audit.append(OutboundEvent(channel = OutboundChannel.AI_APP, target = "", action = "y"), 1L)
        }
    }

    @Test
    fun auditTextPassesTheSecretScrubber() {
        val sink = InMemoryAuditSink()
        val audit = HashChainAudit(sink)
        val entry = audit.append(event("grok.com", "send with password=hunter2secret"), 1L)

        assertEquals("send with [REDACTED_SECRET_ASSIGN]", entry.event.action)
        assertFalse(entry.event.action.contains("hunter2secret"))
        assertTrue(
            sink.received.last().last().event.action.contains("[REDACTED_SECRET_ASSIGN]"),
        )
    }
}
