package app.memo.core.engine

import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.AgentRun
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.OutcomeParser
import app.memo.core.plan.PlanProposalParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.json.JSONObject

class WorkflowEngineAsyncTest {

    private class MutableClock(var value: Long = 1_700_000_000_000L)

    private class RecordingStore : DataStore {
        val rows = mutableListOf<Triple<String, String, String>>()
        override fun upsert(kind: String, naturalId: String, payloadJson: String): String {
            rows += Triple(kind, naturalId, payloadJson)
            return ""
        }
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

    private fun plan(json: String, now: Long): WorkflowSpec =
        PlanProposalParser.parse(json, now).spec!!

    private val singleThinkPlan =
        """{"title":"Async","steps":[{"id":"think","kind":"reason","title":"Think","prompt":"x"}]}"""

    private val gatedSendPlan =
        """{"title":"Publish async","steps":[
            {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
            {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
         ]}"""

    // ----------------------------------------------------------------- tests

    @Test
    fun pendingThenDeliveredCompletionFinishesRun() {
        val clock = MutableClock()
        var reasonCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    reasonCalls++
                    Attempt.Pending("job-1", pollAfterMs = 500)
                },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        assertEquals(RunStatus.RUNNING, started.status)
        val pending = started.stepById("think")!!
        assertEquals(StepStatus.RUNNING, pending.status)
        assertEquals("job-1", pending.externalTicket)
        assertEquals(1, pending.attempts)
        assertEquals(clock.value, pending.lastAttemptAt)

        val done = engine.deliverExternal(started.id, "think", "job-1", Attempt.Completed("async output"))
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(StepStatus.COMPLETED, done.stepById("think")!!.status)
        assertEquals("", done.stepById("think")!!.externalTicket)
        assertEquals("async output", done.stepById("think")!!.output)
        assertEquals(1, reasonCalls)
    }

    @Test
    fun wrongTicketAndNonFinalResultsAreRejected() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Pending("job-1") },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)

        assertFailsWith<IllegalArgumentException> {
            engine.deliverExternal(started.id, "think", "job-999", Attempt.Completed("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.deliverExternal(started.id, "think", "job-1", Attempt.Pending("job-2"))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.deliverExternal("no-such-run", "think", "job-1", Attempt.Completed("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.deliverExternal(started.id, "nope", "job-1", Attempt.Completed("x"))
        }

        val still = engine.run(started.id)!!
        assertEquals(StepStatus.RUNNING, still.stepById("think")!!.status)
        assertEquals("job-1", still.stepById("think")!!.externalTicket)
    }

    @Test
    fun deliveredFailureFollowsRetryPolicy() {
        val clock = MutableClock()
        var calls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    calls++
                    if (calls == 1) Attempt.Pending("job-1") else Attempt.Completed("recovered")
                },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Async retry","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"x"},
                {"id":"r","kind":"retry","target":"think","attemptsMax":2}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.RUNNING, started.status)

        val backingOff = engine.deliverExternal(started.id, "think", "job-1", Attempt.Failed("remote timeout"))
        val step = backingOff.stepById("think")!!
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals("", step.externalTicket)
        assertEquals(1, step.attempts)
        assertEquals(clock.value + 4_000L, step.resumeAt)
        assertEquals(RunStatus.RUNNING, backingOff.status)
        assertEquals(1, calls) // backoff window still open: no executor call yet

        clock.value += 4_000L
        val done = engine.advance(backingOff.id)
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(2, done.stepById("think")!!.attempts)
        assertEquals("recovered", done.stepById("think")!!.output)
        assertEquals(2, calls)
    }

    @Test
    fun deliveredFailureWithoutRetryFailsRun() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Pending("job-1") },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)

        val failed = engine.deliverExternal(started.id, "think", "job-1", Attempt.Failed("remote said no"))
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(StepStatus.FAILED, failed.stepById("think")!!.status)
        assertTrue(failed.stepById("think")!!.failure.contains("remote said no"))
        assertTrue(failed.failure.contains("think"))
    }

    @Test
    fun advanceWhilePendingDoesNotReinvokeExecutor() {
        val clock = MutableClock()
        var calls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    calls++
                    Attempt.Pending("job-1")
                },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        clock.value += 60_000L
        engine.advance(started.id)
        val third = engine.advance(started.id)

        assertEquals(1, calls)
        assertEquals(RunStatus.RUNNING, third.status)
        assertEquals(started.stepById("think"), third.stepById("think"))
    }

    @Test
    fun blankTicketIsTreatedAsFailure() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Pending("   ") },
            ),
            clock = { clock.value },
        )
        val run = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("executor returned a blank ticket", run.stepById("think")!!.failure)
    }

    @Test
    fun deliveredMeasurementPersistsMetricSample() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                metric = MetricExecutor { _, _ -> Attempt.Pending("metric-1") },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Measure later","steps":[
                {"id":"m","kind":"metric_collect","title":"Capture views","metricKey":"views","metricUrl":"https://social.example/p1"}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.RUNNING, started.status)
        assertEquals(0, store.rows.size)

        val done = engine.deliverExternal(
            started.id,
            "m",
            "metric-1",
            Attempt.Measured(OutcomeParser.ParsedNumber(77, false)),
        )
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(2, store.rows.size)
        assertEquals("measured views=77", done.stepById("m")!!.output)
    }

    @Test
    fun deliveredSideEffectRecordsOutboundEventOnce() {
        val clock = MutableClock()
        val audit = RecordingAudit()
        var browserCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    browserCalls++
                    Attempt.Pending("tab-1")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            audit = audit,
        )
        val opened = engine.advance(engine.createRun(plan(gatedSendPlan, clock.value)).id)
        val inFlight = engine.deliverGate(opened.id, "gate", true, gateArtifactHash, "ok")
        assertEquals(RunStatus.RUNNING, inFlight.status)
        assertEquals(0, audit.events.size)

        val done = engine.deliverExternal(inFlight.id, "send", "tab-1", Attempt.Completed("posted"))
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals("posted", done.stepById("send")!!.output)
        assertEquals(1, audit.events.size)
        assertEquals(OutboundChannel.FRONTIER_SITE, audit.events.single().channel)
        assertEquals("grok", audit.events.single().target)
        assertEquals(1, browserCalls)
    }

    @Test
    fun pendingSideEffectGoesToNeedsReviewOnResume() {
        val clock = MutableClock()
        var browserCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    browserCalls++
                    Attempt.Pending("tab-42")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
        )
        val opened = engine.advance(engine.createRun(plan(gatedSendPlan, clock.value)).id)
        val inFlight = engine.deliverGate(opened.id, "gate", true, gateArtifactHash, "ok")
        assertEquals("tab-42", inFlight.stepById("send")!!.externalTicket)

        val resumed = engine.resumeInterrupted(inFlight.id)
        assertEquals(RunStatus.NEEDS_REVIEW, resumed.status)
        val send = resumed.stepById("send")!!
        assertEquals(StepStatus.NEEDS_REVIEW, send.status)
        assertEquals("tab-42", send.externalTicket) // kept so a human can look it up
        assertEquals(1, browserCalls)

        // Never auto-resumed: advancing a run in review does not re-invoke the executor.
        val still = engine.advance(resumed.id)
        assertEquals(RunStatus.NEEDS_REVIEW, still.status)
        assertEquals(1, browserCalls)
    }

    @Test
    fun pendingPureStepReRunsOnResume() {
        val clock = MutableClock()
        var calls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    calls++
                    if (calls == 1) Attempt.Pending("r-1") else Attempt.Completed("rerun ok")
                },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        assertEquals("r-1", started.stepById("think")!!.externalTicket)

        val resumed = engine.resumeInterrupted(started.id)
        assertEquals(RunStatus.COMPLETED, resumed.status)
        assertEquals("rerun ok", resumed.stepById("think")!!.output)
        assertEquals("", resumed.stepById("think")!!.externalTicket)
        assertEquals(2, calls)
    }

    @Test
    fun sideEffectWithoutGateIsRefusedEvenIfExecutorWouldReturnPending() {
        val clock = MutableClock()
        var browserCalls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { _, _ ->
                    browserCalls++
                    Attempt.Pending("tab-1")
                },
            ),
            clock = { clock.value },
        )
        // createRun would refuse this spec, so the run is restored directly
        // (the process-death path) to prove the engine itself also refuses.
        val spec = WorkflowSpec(
            id = "gateless",
            title = "gateless",
            steps = listOf(
                StepSpec(
                    id = "send",
                    kind = StepKind.BROWSER_AUTOMATION,
                    title = "Send",
                    provider = "grok",
                    artifactHint = "send: x",
                    requiresGate = true,
                ),
            ),
        )
        engine.putRun(
            AgentRun(
                id = "gateless-run",
                spec = spec,
                status = RunStatus.RUNNING,
                createdAt = clock.value,
                steps = spec.steps.map { StepState(spec = it) },
            ),
        )

        val run = engine.advance("gateless-run")
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(StepStatus.FAILED, run.stepById("send")!!.status)
        assertEquals("", run.stepById("send")!!.externalTicket)
        assertTrue(run.stepById("send")!!.failure.contains("approval binding"))
        assertEquals(0, browserCalls)
    }

    @Test
    fun externalTicketSurvivesJsonRoundTrip() {
        val clock = MutableClock()
        val state = StepState(
            spec = StepSpec(id = "think", kind = StepKind.REASON, title = "Think", prompt = "x"),
            status = StepStatus.RUNNING,
            attempts = 1,
            externalTicket = "job-7",
        )
        val decoded = StepState.fromJson(JSONObject(state.toJson().toString()))!!
        assertEquals("job-7", decoded.externalTicket)
        assertEquals(StepStatus.RUNNING, decoded.status)
        assertEquals(1, decoded.attempts)

        val legacy = JSONObject(state.toJson().toString()).apply { remove("externalTicket") }
        assertEquals("", StepState.fromJson(legacy)!!.externalTicket)

        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Pending("job-1") },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        val restored = AgentRun.fromJson(started.toJson())!!
        assertEquals("job-1", restored.stepById("think")!!.externalTicket)
        assertEquals(RunStatus.RUNNING, restored.status)
    }

    @Test
    fun cancelClearsPendingTicket() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Pending("job-1") },
            ),
            clock = { clock.value },
        )
        val started = engine.advance(engine.createRun(plan(singleThinkPlan, clock.value)).id)
        val cancelled = engine.cancel(started.id, "no longer needed")

        assertEquals(RunStatus.CANCELLED, cancelled.status)
        val step = cancelled.stepById("think")!!
        assertEquals(StepStatus.SKIPPED, step.status)
        assertEquals("", step.externalTicket)
        assertTrue(step.failure.contains("no longer needed"))
    }
}
