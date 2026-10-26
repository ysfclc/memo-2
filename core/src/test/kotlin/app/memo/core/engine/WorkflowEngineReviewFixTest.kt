package app.memo.core.engine

import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.PlanProposalParser
import app.memo.core.plan.WorkflowPolicy
import app.memo.core.domain.RetrySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/** Second-review fixes for the engine: veto skipping, persist binding, side-effect review, bounded state. */
class WorkflowEngineReviewFixTest {

    private class MutableClock(var value: Long = 1_700_000_000_000L)

    private class RecordingStore : DataStore {
        val rows = mutableListOf<Triple<String, String, String>>()
        override fun upsert(kind: String, naturalId: String, payloadJson: String): String {
            rows += Triple(kind, naturalId, payloadJson)
            return ""
        }
    }

    private val gateArtifact =
        """{"type":"draft","kind":"POST","title":"Hello","body":"Checkpoints beat vibes, honestly."}"""
    private val gateArtifactHash = CanonicalJson.contentHash(JSONObject(gateArtifact))

    private fun plan(json: String, now: Long): WorkflowSpec = PlanProposalParser.parse(json, now).spec!!

    private fun gateState(spec: StepSpec, status: StepStatus, approvedHash: String = "") = StepState(
        spec = spec,
        status = status,
        approvedContentHash = approvedHash,
        artifact = gateArtifact,
        artifactContentHash = gateArtifactHash,
        output = if (status == StepStatus.COMPLETED) gateArtifact else "",
    )

    // ------------------------------------------------- A1: veto skips persist

    @Test
    fun vetoSkipsFollowingDataPersist() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("""{"kind":"POST","title":"T","body":"B"}""") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Save after review","steps":[
                {"id":"draft","kind":"reason","title":"Draft","prompt":"write"},
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"save","kind":"data_persist","title":"Save","artifactType":"draft"}
             ]}""",
            clock.value,
        )

        // Approve path: the approved artifact is saved.
        val approvedRun = engine.advance(engine.createRun(spec).id)
        val saved = engine.deliverGate(approvedRun.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "")
        assertEquals(RunStatus.COMPLETED, saved.status)
        assertEquals(1, store.rows.size)

        // Veto path: nothing is saved, the run ends, the save step is skipped.
        val vetoRun = engine.advance(engine.createRun(spec).id)
        val vetoed = engine.deliverGate(vetoRun.id, "gate", approved = false, contentHashAtApproval = "", note = "no")
        assertEquals(RunStatus.COMPLETED, vetoed.status)
        assertEquals(StepStatus.SKIPPED, vetoed.stepById("save")!!.status)
        assertEquals(StepStatus.SKIPPED, vetoed.stepById("gate")!!.status)
        assertEquals(1, store.rows.size) // registry unchanged by the veto
    }

    @Test
    fun resolveReviewSkipOfGateSkipsGuardedSteps() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Mismatch","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"save","kind":"data_persist","title":"Save","artifactType":"draft"}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        val mismatch = engine.deliverGate(started.id, "gate", approved = true, contentHashAtApproval = "0".repeat(64), note = "")
        assertEquals(RunStatus.NEEDS_REVIEW, mismatch.status)

        val skipped = engine.resolveReview(mismatch.id, "gate", retry = false)
        assertEquals(RunStatus.COMPLETED, skipped.status)
        assertEquals(StepStatus.SKIPPED, skipped.stepById("save")!!.status)
        assertTrue(store.rows.isEmpty())
    }

    // ------------------------------------------ A1: persist binding & refusal

    @Test
    fun dataPersistRefusedWhenGateNotCompleted() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(dataStore = store),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Forged","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"save","kind":"data_persist","title":"Save","artifactType":"draft"}
             ]}""",
            clock.value,
        )
        // Gate SKIPPED (not COMPLETED): the persist step must be refused.
        val skippedGate = AgentRun(
            id = "skipped-gate",
            spec = spec,
            status = RunStatus.RUNNING,
            createdAt = clock.value,
            steps = listOf(
                gateState(spec.steps[0], StepStatus.SKIPPED),
                StepState(spec = spec.steps[1]),
            ),
            pointer = 1,
        )
        engine.putRun(skippedGate)
        val refused = engine.advance(skippedGate.id)
        assertEquals(StepStatus.FAILED, refused.stepById("save")!!.status)
        assertTrue(refused.stepById("save")!!.failure.contains("refused"))
        assertTrue(store.rows.isEmpty())

        // Gate COMPLETED but bound to a different hash than the artifact: also refused.
        val mismatched = AgentRun(
            id = "mismatched-gate",
            spec = spec,
            status = RunStatus.RUNNING,
            createdAt = clock.value,
            steps = listOf(
                gateState(spec.steps[0], StepStatus.COMPLETED, approvedHash = "0".repeat(64)),
                StepState(spec = spec.steps[1]),
            ),
            pointer = 1,
        )
        engine.putRun(mismatched)
        val refusedAgain = engine.advance(mismatched.id)
        assertEquals(StepStatus.FAILED, refusedAgain.stepById("save")!!.status)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun dataPersistWritesApprovedArtifactNotLaterOutput() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    Attempt.Completed("""{"kind":"POST","title":"Later","body":"Something else entirely"}""")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Bind","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"later","kind":"reason","title":"Later","prompt":"write more"},
                {"id":"save","kind":"data_persist","title":"Save","artifactType":"draft"}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        val finished = engine.deliverGate(started.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "ok")
        assertEquals(RunStatus.COMPLETED, finished.status)
        assertEquals(1, store.rows.size)
        assertEquals(gateArtifact, store.rows.single().third)
    }

    // ------------------------------------------- A2: side effects never retry

    @Test
    fun retryPolicyOnSideEffectIsRejectedBySpecValidation() {
        val outcome = PlanProposalParser.parse(
            """{"title":"Retry send","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true},
                {"id":"r","kind":"retry","target":"send","attemptsMax":3}
             ]}""",
            0L,
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.violations.any { it.contains("must not retry") })

        // Declared directly on a spec (bypassing the parser) it is still refused by the policy.
        val direct = WorkflowSpec(
            id = "x",
            title = "x",
            steps = listOf(
                StepSpec(id = "gate", kind = StepKind.HUMAN_APPROVAL, title = "Approve", artifactType = "draft"),
                StepSpec(
                    id = "send", kind = StepKind.APP_AUTOMATION, title = "Send", appPackage = "com.example",
                    requiresGate = true, retry = RetrySpec(attemptsMax = 2),
                ),
            ),
        )
        val directViolations = WorkflowPolicy.validateSpec(direct)
        assertTrue(directViolations.any { it.contains("must not retry") }, directViolations.toString())
    }

    @Test
    fun sideEffectFailureGoesToNeedsReviewAndIsNeverRetried() {
        val clock = MutableClock()
        var sends = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ -> sends++; Attempt.Failed("network dropped after submit") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Send once","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        val failed = engine.deliverGate(started.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "")
        assertEquals(RunStatus.NEEDS_REVIEW, failed.status)
        assertEquals(StepStatus.NEEDS_REVIEW, failed.stepById("send")!!.status)
        assertEquals(1, sends)

        // Even with time passing, the engine does not re-run the side effect on its own.
        clock.value += 60_000L
        val again = engine.advance(failed.id)
        assertEquals(RunStatus.NEEDS_REVIEW, again.status)
        assertEquals(1, sends)
    }

    @Test
    fun defenceInDepthSideEffectWithRetryStillDoesNotRetry() {
        val clock = MutableClock()
        var sends = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ -> sends++; Attempt.Failed("flaky") },
            ),
            clock = { clock.value },
        )
        // Built directly (no parser), so the policy is bypassed on purpose: the engine must still refuse to retry.
        val spec = WorkflowSpec(
            id = "w", title = "w",
            steps = listOf(
                StepSpec(id = "gate", kind = StepKind.HUMAN_APPROVAL, title = "Approve", artifactType = "draft"),
                StepSpec(
                    id = "send", kind = StepKind.BROWSER_AUTOMATION, provider = "grok",
                    artifactHint = "send: x", requiresGate = true, retry = RetrySpec(attemptsMax = 4),
                ),
            ),
        )
        val restored = AgentRun(
            id = "retry-run",
            spec = spec,
            status = RunStatus.RUNNING,
            createdAt = clock.value,
            steps = listOf(
                gateState(spec.steps[0], StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                StepState(spec = spec.steps[1]),
            ),
            pointer = 1,
        )
        engine.putRun(restored)
        val run = engine.advance(restored.id)
        assertEquals(RunStatus.NEEDS_REVIEW, run.status)
        assertEquals(StepStatus.NEEDS_REVIEW, run.stepById("send")!!.status)
        assertEquals(1, sends)
    }

    // ------------------------------------------ A3: no hash of a truncated artifact

    @Test
    fun gateArtifactOverLimitFailsInsteadOfHashingTruncatedCopy() {
        val clock = MutableClock()
        val huge = """{"type":"draft","kind":"POST","title":"T","body":"${"x".repeat(6_500)}"}"""
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(huge) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Too big","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.FAILED, run.status)
        val gate = run.stepById("gate")!!
        assertEquals(StepStatus.FAILED, gate.status)
        assertTrue(gate.failure.contains("truncated"), gate.failure)
        assertEquals("", gate.artifactContentHash)
        assertEquals("", gate.artifact)
    }

    // ----------------------------------------------- A5: bounded state

    @Test
    fun throwableFailureStoresClassNameAndBoundedMessage() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> throw IllegalStateException("secret-token-".repeat(60)) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Throws","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"p"}]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.INTERRUPTED, run.status)
        val failure = run.stepById("think")!!.failure
        assertTrue(failure.startsWith("executor threw: IllegalStateException: "), failure)
        val message = failure.substringAfter("IllegalStateException: ")
        assertTrue(message.length <= 160, "message was ${message.length} chars")
    }

    @Test
    fun rememberedAttemptResultsAreCappedAtTwenty() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(reason = ReasonExecutor { _, _ -> Attempt.Completed("ok") }),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"One","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"p"}]}""",
            clock.value,
        )
        repeat(25) { engine.advance(engine.createRun(spec).id) }
        assertEquals(20, engine.rememberedAttemptCount())
    }

    // ------------------------------------------------ B13: markNeedsReview

    @Test
    fun markNeedsReviewHoldsUnsettledSideEffectWithoutExecuting() {
        val clock = MutableClock()
        var sends = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(browser = BrowserExecutor { _, _ -> sends++; Attempt.Completed("sent") }),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Restored","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val restored = AgentRun(
            id = "backup-run",
            spec = spec,
            status = RunStatus.RUNNING,
            createdAt = clock.value,
            steps = listOf(
                gateState(spec.steps[0], StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                StepState(spec = spec.steps[1], status = StepStatus.RUNNING),
            ),
            pointer = 1,
        )
        engine.putRun(restored)
        val marked = engine.markNeedsReview(restored.id, "restored from backup")
        assertEquals(RunStatus.NEEDS_REVIEW, marked.status)
        assertEquals(StepStatus.NEEDS_REVIEW, marked.stepById("send")!!.status)
        assertEquals("restored from backup", marked.failure)
        assertEquals(0, sends)

        // A human decision resolves it; the run then continues from the review.
        val skipped = engine.resolveReview(restored.id, "send", retry = false)
        assertEquals(RunStatus.COMPLETED, skipped.status)
        assertEquals(0, sends)
    }

    @Test
    fun forgetAllRunsEmptiesTheEngine() {
        val clock = MutableClock()
        val engine = WorkflowEngine(routes = ExecutorRoutes(), clock = { clock.value })
        val spec = plan(
            """{"title":"One","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"p"}]}""",
            clock.value,
        )
        engine.createRun(spec)
        engine.createRun(spec)
        assertEquals(2, engine.runs().size)
        engine.forgetAllRuns()
        assertTrue(engine.runs().isEmpty())
        assertEquals(0, engine.rememberedAttemptCount())
    }

    @Test
    fun markNeedsReviewLeavesTerminalRunsAlone() {
        val clock = MutableClock()
        val engine = WorkflowEngine(routes = ExecutorRoutes(), clock = { clock.value })
        val spec = plan(
            """{"title":"Done","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"p"}]}""",
            clock.value,
        )
        val done = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.FAILED, done.status) // no reason executor registered: fails closed
        assertEquals(RunStatus.FAILED, engine.markNeedsReview(done.id, "late").status)
    }
}
