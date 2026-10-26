package app.memo.core.engine

import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.AgentRun
import app.memo.core.domain.BranchOutcome
import app.memo.core.domain.BranchSpec
import app.memo.core.domain.OutboundEvent
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.PlanProposalParser
import app.memo.core.plan.WorkflowPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/** D-023: approval binding to the nearest gate, and crash-safe side effects. */
class WorkflowEngineSafetyTest {

    private class MutableClock(var value: Long = 1_700_000_000_000L)

    private class RecordingStore : DataStore {
        override fun upsert(kind: String, naturalId: String, payloadJson: String): String = ""
    }

    private class RecordingAudit : OutboundAudit {
        val events = mutableListOf<OutboundEvent>()
        override fun record(event: OutboundEvent) {
            events += event
        }
    }

    private val gateArtifact =
        """{"type":"draft","kind":"POST","title":"Hello","body":"Checkpoints beat vibes, honestly."}"""
    private val gateArtifactHash = CanonicalJson.contentHash(JSONObject(gateArtifact))

    private val gate1 = StepSpec(id = "g1", kind = StepKind.HUMAN_APPROVAL, title = "Approve 1", artifactType = "draft")
    private val gate2 = StepSpec(id = "g2", kind = StepKind.HUMAN_APPROVAL, title = "Approve 2", artifactType = "draft")
    private val submitA = StepSpec(
        id = "a", kind = StepKind.BROWSER_AUTOMATION, title = "Submit A",
        provider = "grok", artifactHint = "send: a", requiresGate = true,
    )
    private val branchOnA = StepSpec(
        id = "br", kind = StepKind.BRANCH, title = "Route",
        branches = listOf(BranchSpec(whenStep = "a", outcome = BranchOutcome.FAILED, goto = "c")),
    )
    private val submitC = StepSpec(
        id = "c", kind = StepKind.APP_AUTOMATION, title = "App submit",
        appPackage = "com.example.app", requiresGate = true,
    )

    private val sendPlan = """{"title":"Send once","steps":[
        {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
        {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
     ]}"""

    private fun plan(json: String, now: Long): WorkflowSpec =
        PlanProposalParser.parse(json, now).spec!!

    private fun state(
        spec: StepSpec,
        status: StepStatus,
        failure: String = "",
        approvedHash: String = "",
        ticket: String = "",
    ) = StepState(
        spec = spec,
        status = status,
        failure = failure,
        approvedContentHash = approvedHash,
        externalTicket = ticket,
        artifact = if (spec.kind == StepKind.HUMAN_APPROVAL) gateArtifact else "",
        artifactContentHash = if (spec.kind == StepKind.HUMAN_APPROVAL) gateArtifactHash else "",
    )

    private fun restored(id: String, steps: List<StepState>, status: RunStatus, pointer: Int) = AgentRun(
        id = id,
        spec = WorkflowSpec(id = "restored", title = "restored", createdAt = 0, steps = steps.map { it.spec }),
        status = status,
        createdAt = 0,
        steps = steps,
        pointer = pointer,
    )

    // ------------------------------------------------- plan validation (1b)

    @Test
    fun rescueThatJumpsOverGateIsRejectedBySpecValidation() {
        // G1 / A / Br(A FAILED -> C) / G2 / C: the rescue path would skip G2 and run C under G1.
        val spec = WorkflowSpec(
            id = "bad",
            title = "Rescue skips gate",
            createdAt = 0,
            steps = listOf(gate1, submitA, branchOnA, gate2, submitC),
        )
        val violations = WorkflowPolicy.validateSpec(spec)
        assertTrue(violations.any { it.contains("over a HUMAN_APPROVAL gate") }, violations.toString())
        assertFailsWith<IllegalStateException> {
            WorkflowEngine(routes = ExecutorRoutes(), clock = { 0L }).createRun(spec)
        }
    }

    @Test
    fun parserRejectsTheSameRescueJumpOverGate() {
        val json = """{"title":"Rescue skips gate","steps":[
            {"id":"g1","kind":"human_approval","title":"Approve 1","artifactType":"draft"},
            {"id":"a","kind":"browser_automation","title":"Submit A","provider":"grok","artifactHint":"send: a","requiresGate":true},
            {"id":"br","kind":"branch","branches":[{"whenStep":"a","outcome":"FAILED","goto":"c"}]},
            {"id":"g2","kind":"human_approval","title":"Approve 2","artifactType":"draft"},
            {"id":"c","kind":"app_automation","title":"App submit","appPackage":"com.example.app","requiresGate":true}
        ]}"""
        val result = PlanProposalParser.parse(json, 0)
        assertFalse(result.ok)
        assertTrue(result.violations.any { it.contains("jumps to 'c' over a HUMAN_APPROVAL gate") }, result.violations.toString())
    }

    @Test
    fun failureRescueWatchingAcrossGateIsRejected() {
        // The watched step sits before a gate; the rescue from it would skip that gate.
        val json = """{"title":"Watch across gate","steps":[
            {"id":"x","kind":"reason","title":"Work","prompt":"do it"},
            {"id":"g1","kind":"human_approval","title":"Approve","artifactType":"draft"},
            {"id":"br","kind":"branch","branches":[{"whenStep":"x","outcome":"FAILED","goto":"y"}]},
            {"id":"y","kind":"reason","title":"Fallback","prompt":"recover"}
        ]}"""
        val result = PlanProposalParser.parse(json, 0)
        assertFalse(result.ok)
        assertTrue(result.violations.any { it.contains("watches 'x' across a HUMAN_APPROVAL gate") }, result.violations.toString())
    }

    @Test
    fun branchWithoutGateInBetweenIsStillAccepted() {
        val json = """{"title":"Safe rescue","steps":[
            {"id":"g1","kind":"human_approval","title":"Approve","artifactType":"draft"},
            {"id":"a","kind":"browser_automation","title":"Submit A","provider":"grok","artifactHint":"send: a","requiresGate":true},
            {"id":"br","kind":"branch","branches":[{"whenStep":"a","outcome":"FAILED","goto":"c"}]},
            {"id":"c","kind":"reason","title":"Fallback","prompt":"recover"}
        ]}"""
        assertTrue(PlanProposalParser.parse(json, 0).ok)
    }

    // ------------------------------------------------- runtime binding (1a)

    @Test
    fun sideEffectUnderSkippedNearestGateIsRefusedAtRuntime() {
        // A restored run that bypasses validation: G1 approved, A failed, the rescue
        // skipped G2 and diverted to C. C must not run under G1's approval.
        var appCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                app = AppAutomationExecutor { _, _ ->
                    appCalls += 1
                    Attempt.Completed("submitted c")
                },
            ),
            clock = { 0L },
        )
        val run = restored(
            id = "run-skip",
            steps = listOf(
                state(gate1, StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                state(submitA, StepStatus.FAILED, failure = "submit rejected"),
                state(branchOnA, StepStatus.PENDING),
                state(gate2, StepStatus.PENDING),
                state(submitC, StepStatus.PENDING),
            ),
            status = RunStatus.RUNNING,
            pointer = 1,
        )
        engine.putRun(run)

        val after = engine.advance(run.id)
        assertEquals(0, appCalls)
        assertEquals(StepStatus.SKIPPED, after.stepById("g2")!!.status)
        assertEquals(StepStatus.FAILED, after.stepById("c")!!.status)
        assertTrue(
            after.stepById("c")!!.failure.contains("nearest preceding gate 'g2' is SKIPPED"),
            after.stepById("c")!!.failure,
        )
        assertEquals(RunStatus.FAILED, after.status)
    }

    @Test
    fun deliverExternalChecksTheNearestGateNotAnOlderOne() {
        val vetoedNearest = restored(
            id = "run-ext-skipped",
            steps = listOf(
                state(gate1, StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                state(gate2, StepStatus.SKIPPED, failure = "vetoed: no"),
                state(submitC, StepStatus.RUNNING, ticket = "job-1"),
            ),
            status = RunStatus.RUNNING,
            pointer = 2,
        )
        val refusing = WorkflowEngine(routes = ExecutorRoutes(), clock = { 0L })
        refusing.putRun(vetoedNearest)
        assertFailsWith<IllegalStateException> {
            refusing.deliverExternal(vetoedNearest.id, "c", "job-1", Attempt.Completed("done"))
        }

        // Control: the same run with the nearest gate COMPLETED settles normally.
        val audit = RecordingAudit()
        val approvedNearest = restored(
            id = "run-ext-approved",
            steps = listOf(
                state(gate1, StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                state(gate2, StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                state(submitC, StepStatus.RUNNING, ticket = "job-2"),
            ),
            status = RunStatus.RUNNING,
            pointer = 2,
        )
        val settling = WorkflowEngine(routes = ExecutorRoutes(), clock = { 0L }, audit = audit)
        settling.putRun(approvedNearest)
        val done = settling.deliverExternal(approvedNearest.id, "c", "job-2", Attempt.Completed("done"))
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(1, audit.events.size)
    }

    @Test
    fun deliverExternalRefusesAnInflightCheckpointTicket() {
        val engine = WorkflowEngine(routes = ExecutorRoutes(), clock = { 0L })
        val run = restored(
            id = "run-inflight",
            steps = listOf(
                state(gate1, StepStatus.COMPLETED, approvedHash = gateArtifactHash),
                state(submitC, StepStatus.RUNNING, ticket = "inflight:run-inflight:c:1"),
            ),
            status = RunStatus.RUNNING,
            pointer = 1,
        )
        engine.putRun(run)
        assertFailsWith<IllegalStateException> {
            engine.deliverExternal(run.id, "c", "inflight:run-inflight:c:1", Attempt.Completed("done"))
        }
    }

    // ------------------------------------------------- checkpoint (2)

    @Test
    fun checkpointRunsOnceBeforeSideEffectExecutor() {
        val clock = MutableClock()
        val events = mutableListOf<String>()
        val checkpoints = mutableListOf<AgentRun>()
        val audit = RecordingAudit()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    events += "executor"
                    Attempt.Completed("posted")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
                dataStore = RecordingStore(),
            ),
            clock = { clock.value },
            audit = audit,
            checkpoint = { run ->
                events += "checkpoint"
                checkpoints += run
            },
        )
        val spec = plan(sendPlan, clock.value)
        val opened = engine.advance(engine.createRun(spec).id)
        val done = engine.deliverGate(opened.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "ok")

        assertEquals(listOf("checkpoint", "executor"), events)
        assertEquals(1, checkpoints.size)
        val atCheckpoint = checkpoints.single().stepById("send")!!
        assertEquals(StepStatus.RUNNING, atCheckpoint.status)
        assertEquals("inflight:${opened.id}:send:1", atCheckpoint.externalTicket)

        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(StepStatus.COMPLETED, done.stepById("send")!!.status)
        assertEquals("", done.stepById("send")!!.externalTicket)
        assertEquals(1, audit.events.size) // audit stays exactly once per completed side effect
    }

    @Test
    fun pendingExecutorTicketReplacesInflightTicket() {
        val clock = MutableClock()
        val checkpoints = mutableListOf<AgentRun>()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ -> Attempt.Pending(ticket = "remote-job-9") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            checkpoint = { checkpoints += it },
        )
        val spec = plan(sendPlan, clock.value)
        val opened = engine.advance(engine.createRun(spec).id)
        val waiting = engine.deliverGate(opened.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "ok")

        assertEquals(RunStatus.RUNNING, waiting.status)
        assertEquals(1, checkpoints.size)
        assertEquals("remote-job-9", waiting.stepById("send")!!.externalTicket)
        assertEquals(StepStatus.RUNNING, waiting.stepById("send")!!.status)
    }

    @Test
    fun checkpointFailureMeansExecutorIsNotCalled() {
        val clock = MutableClock()
        var executorCalls = 0
        val audit = RecordingAudit()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    executorCalls += 1
                    Attempt.Completed("posted")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            audit = audit,
            checkpoint = { error("disk full") },
        )
        val spec = plan(sendPlan, clock.value)
        val opened = engine.advance(engine.createRun(spec).id)
        val failed = engine.deliverGate(opened.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "ok")

        assertEquals(0, executorCalls)
        assertEquals(0, audit.events.size)
        assertEquals(RunStatus.FAILED, failed.status)
        assertTrue(
            failed.stepById("send")!!.failure.contains("could not checkpoint before side effect"),
            failed.stepById("send")!!.failure,
        )
    }

    @Test
    fun pureStepDoesNotTriggerCheckpoint() {
        val clock = MutableClock()
        var checkpoints = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("thought") },
            ),
            clock = { clock.value },
            checkpoint = { checkpoints += 1 },
        )
        val spec = plan(
            """{"title":"Think","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"x"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(0, checkpoints)
    }

    // ------------------------------------------------- crash recovery (2)

    @Test
    fun crashDuringSideEffectRestoresAsNeedsReviewAndNeverReExecutes() {
        val clock = MutableClock()
        val saved = mutableListOf<AgentRun>()
        var firstExecutorCalls = 0
        val first = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    firstExecutorCalls += 1
                    error("process killed mid submit")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            checkpoint = { saved += it },
        )
        val spec = plan(sendPlan, clock.value)
        val opened = first.advance(first.createRun(spec).id)
        first.deliverGate(opened.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "ok")
        assertEquals(1, firstExecutorCalls)

        // What the app persisted at the checkpoint, round-tripped as the restore path would read it.
        val persisted = AgentRun.fromJson(saved.single().toJson())!!
        assertEquals(RunStatus.RUNNING, persisted.status)
        assertTrue(persisted.stepById("send")!!.externalTicket.startsWith("inflight:"))

        var secondExecutorCalls = 0
        val second = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    secondExecutorCalls += 1
                    Attempt.Completed("posted again")
                },
            ),
            clock = { clock.value },
        )
        second.putRun(persisted)
        val recovered = second.recoverAfterRestart(persisted.id)
        assertEquals(RunStatus.NEEDS_REVIEW, recovered.status)
        assertEquals(StepStatus.NEEDS_REVIEW, recovered.stepById("send")!!.status)
        assertTrue(recovered.stepById("send")!!.failure.contains("external work in flight"))

        // Driving the run again must not re-execute the side effect.
        val again = second.advance(persisted.id)
        assertEquals(RunStatus.NEEDS_REVIEW, again.status)
        assertEquals(0, secondExecutorCalls)
    }

    @Test
    fun recoverAfterRestartNeverInvokesExecutorsForPureSteps() {
        var reasonCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    reasonCalls += 1
                    Attempt.Completed("should not run during recovery")
                },
            ),
            clock = { 0L },
        )
        val run = restored(
            id = "run-pure",
            steps = listOf(
                StepState(
                    spec = StepSpec(id = "think", kind = StepKind.REASON, title = "Think", prompt = "x"),
                    status = StepStatus.RUNNING,
                    externalTicket = "pure-ticket",
                ),
            ),
            status = RunStatus.RUNNING,
            pointer = 0,
        )
        engine.putRun(run)
        val recovered = engine.recoverAfterRestart(run.id)
        assertEquals(0, reasonCalls)
        assertEquals(RunStatus.RUNNING, recovered.status)
        assertEquals(StepStatus.PENDING, recovered.stepById("think")!!.status)
        assertEquals("", recovered.stepById("think")!!.externalTicket)
    }
}
