package app.memo.core.engine

import app.memo.core.audit.HashChainAudit
import app.memo.core.audit.InMemoryAuditSink
import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.PlanProposalParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Proves the WorkflowEngine's outbound audit, wired to the REAL
 * [HashChainAudit] (the way MemoRuntime.kt wires it), records exactly one
 * link per completed side-effect/outbound step and none for pure steps,
 * and that the chain the engine produced verifies.
 */
class WorkflowAuditTest {

    private class MutableClock(var value: Long = 1_700_000_000_000L)

    private val gateArtifact =
        """{"type":"draft","kind":"POST","title":"Hello","body":"Checkpoints beat vibes, honestly."}"""
    private val gateArtifactHash = CanonicalJson.contentHash(JSONObject(gateArtifact))

    private fun plan(json: String, now: Long): WorkflowSpec =
        PlanProposalParser.parse(json, now).spec!!

    /** Engine wired exactly like MemoRuntime: audit appends into the chain. */
    private class Harness(clock: MutableClock, routes: ExecutorRoutes) {
        private val chainSink = InMemoryAuditSink()
        val chain = HashChainAudit(chainSink)
        val engine = WorkflowEngine(
            routes = routes,
            clock = { clock.value },
            audit = OutboundAudit { event -> chain.append(event, clock.value) },
        )
    }

    private val singleThinkPlan =
        """{"title":"Think only","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"x"}]}"""

    private val gatedSendPlan =
        """{"title":"Publish async","steps":[
            {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
            {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
         ]}"""

    // ----------------------------------------------------------------- tests

    @Test
    fun pureOnlyRunLeavesAuditChainEmpty() {
        val clock = MutableClock()
        val h = Harness(
            clock,
            ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("pure output") },
            ),
        )
        val run = h.engine.advance(h.engine.createRun(plan(singleThinkPlan, clock.value)).id)

        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(StepStatus.COMPLETED, run.stepById("think")!!.status)
        assertEquals(0, h.chain.size)
        assertTrue(h.chain.verify().ok)
    }

    @Test
    fun deliveredBrowserSideEffectAddsExactlyOneVerifiableLink() {
        val clock = MutableClock()
        val h = Harness(
            clock,
            ExecutorRoutes(
                browser = BrowserExecutor { _, _ -> Attempt.Pending("tab-1") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
        )
        val opened = h.engine.advance(h.engine.createRun(plan(gatedSendPlan, clock.value)).id)
        val inFlight = h.engine.deliverGate(opened.id, "gate", true, gateArtifactHash, "ok")
        assertEquals(RunStatus.RUNNING, inFlight.status)
        assertEquals(0, h.chain.size) // nothing has been completed yet

        val done = h.engine.deliverExternal(inFlight.id, "send", "tab-1", Attempt.Completed("posted"))
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(1, h.chain.size)
        val verified = h.chain.verify()
        assertTrue(verified.ok)
        assertEquals(1, verified.checked)
        val link = h.chain.entries().single()
        assertEquals(0L, link.seq)
        assertEquals("send", link.event.stepId)
        assertEquals(done.id, link.event.runId)
    }

    @Test
    fun twoSideEffectsAddTwoChainedLinks() {
        val clock = MutableClock()
        val h = Harness(
            clock,
            ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("drafted") },
                browser = BrowserExecutor { _, _ -> Attempt.Completed(gateArtifact) },
                artifactBuilder = ArtifactBuilder { _, type, _ ->
                    Attempt.Completed("""{"type":"$type","kind":"POST","title":"Hello","body":"Checkpoints beat vibes, honestly."}""")
                },
            ),
        )
        // One pure step then TWO gated side effects; each side effect binds
        // to its NEAREST preceding gate, so each gate is delivered in turn.
        val spec = plan(
            """{"title":"Publish twice","steps":[
                {"id":"draft","kind":"reason","title":"Draft","prompt":"write"},
                {"id":"gate1","kind":"human_approval","title":"Approve one","artifactType":"draft"},
                {"id":"post1","kind":"browser_automation","title":"Send one","provider":"grok","artifactHint":"send: one","requiresGate":true},
                {"id":"draft2","kind":"reason","title":"Draft two","prompt":"write again"},
                {"id":"gate2","kind":"human_approval","title":"Approve two","artifactType":"draft"},
                {"id":"post2","kind":"browser_automation","title":"Send two","provider":"grok","artifactHint":"send: two","requiresGate":true}
             ]}""",
            clock.value,
        )
        val firstGate = h.engine.advance(h.engine.createRun(spec).id)
        assertEquals(RunStatus.WAITING_USER, firstGate.status)
        val afterFirst = h.engine.deliverGate(firstGate.id, "gate1", true, gateArtifactHash, "ok")
        assertEquals(StepStatus.COMPLETED, afterFirst.stepById("post1")!!.status)
        assertEquals(RunStatus.WAITING_USER, afterFirst.status) // second gate opens
        assertEquals(1, h.chain.size)

        val done = h.engine.deliverGate(afterFirst.id, "gate2", true, gateArtifactHash, "ok")
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(StepStatus.COMPLETED, done.stepById("post2")!!.status)
        assertEquals(2, h.chain.size)

        val verified = h.chain.verify()
        assertTrue(verified.ok)
        assertEquals(2, verified.checked)
        val links = h.chain.entries()
        assertEquals(0L, links[0].seq)
        assertEquals(1L, links[1].seq)
        assertEquals(links[0].hash, links[1].prevHash)
    }

    @Test
    fun failedSideEffectAddsNoLink() {
        val clock = MutableClock()
        val h = Harness(
            clock,
            ExecutorRoutes(
                browser = BrowserExecutor { _, _ -> Attempt.Failed("site refused login") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
        )
        val opened = h.engine.advance(h.engine.createRun(plan(gatedSendPlan, clock.value)).id)
        val gated = h.engine.deliverGate(opened.id, "gate", true, gateArtifactHash, "ok")
        // A failed side effect is never retried automatically: a human decides.
        assertEquals(RunStatus.NEEDS_REVIEW, gated.status)
        assertEquals(StepStatus.NEEDS_REVIEW, gated.stepById("send")!!.status)
        assertTrue(gated.stepById("send")!!.failure.contains("site refused login"))

        assertEquals(0, h.chain.size)
        assertTrue(h.chain.verify().ok)
    }
}
