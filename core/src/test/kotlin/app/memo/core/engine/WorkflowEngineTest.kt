package app.memo.core.engine

import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.AgentRun
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.PlanProposalParser
import app.memo.core.prompts.ContextPacket
import app.memo.core.registry.NaturalKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.json.JSONObject

class WorkflowEngineTest {

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

    // ----------------------------------------------------------------- tests

    @Test
    fun reasonOnlyRunCompletesHonestOutput() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("ok output") },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Just think","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"derive a verdict"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(StepStatus.COMPLETED, run.steps.single().status)
        assertEquals("ok output", run.steps.single().output)
    }

    @Test
    fun dataPersistRoutesThroughStoreWithNaturalKey() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    Attempt.Completed("""{"kind":"POST","title":"Hello","body":"World, quietly"}""")
                },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Save it","steps":[
                {"id":"write","kind":"reason","title":"Compose","prompt":"write"},
                {"id":"keep","kind":"data_persist","title":"Persist","artifactType":"draft"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(1, store.rows.size)
        assertEquals("draft", store.rows.single().first)
        assertTrue(store.rows.single().second.startsWith("d:"))
        assertTrue(run.steps[1].output.startsWith("persisted:"))
    }

    @Test
    fun gateFlowBindsHashAndExecutesBoundSideEffect() {
        val clock = MutableClock()
        val audit = RecordingAudit()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("not used by gate") },
                browser = BrowserExecutor { ctx, _ ->
                    check(ctx.approvedArtifactHash == gateArtifactHash) { "executor saw wrong approval binding" }
                    Attempt.Completed("posted")
                },
                dataStore = RecordingStore(),
                artifactBuilder = ArtifactBuilder { _, type, _ ->
                    Attempt.Completed("""{"type":"$type","kind":"POST","title":"Hello","body":"Checkpoints beat vibes, honestly."}""")
                },
            ),
            clock = { clock.value },
            packetProvider = { ContextPacket.Builder().put("brief", "post about hiring").build() },
            audit = audit,
        )
        val spec = plan(
            """{"title":"Publish","steps":[
                {"id":"draft","kind":"reason","title":"Draft","prompt":"write"},
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: publish","requiresGate":true}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.WAITING_USER, started.status)
        val gate = started.stepById("gate")!!
        assertEquals(StepStatus.WAITING_USER, gate.status)
        assertEquals(gateArtifactHash, gate.artifactContentHash)

        val finished = engine.deliverGate(started.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "looks good")
        assertEquals(RunStatus.COMPLETED, finished.status)
        assertEquals("posted", finished.stepById("send")!!.output)
        assertEquals(1, audit.events.size)
        assertEquals(OutboundChannel.FRONTIER_SITE, audit.events.single().channel)
        assertEquals("grok", audit.events.single().target)
    }

    @Test
    fun vetoSkipsSideEffectsUpToNextGate() {
        val clock = MutableClock()
        val audit = RecordingAudit()
        var browserRan = false
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    Attempt.Completed("""{"kind":"POST","title":"T","body":"B"}""")
                },
                browser = BrowserExecutor { _, _ -> browserRan = true; Attempt.Completed("should not happen") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            audit = audit,
        )
        val spec = plan(
            """{"title":"Vetoed","steps":[
                {"id":"draft","kind":"reason","title":"Draft","prompt":"write"},
                {"id":"gate1","kind":"human_approval","title":"Approve draft","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true},
                {"id":"gate2","kind":"human_approval","title":"Approve again","artifactType":"task"},
                {"id":"later","kind":"reason","title":"Later","prompt":"continue"}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.WAITING_USER, started.status)

        val afterVeto = engine.deliverGate(started.id, "gate1", approved = false, contentHashAtApproval = "", note = "not my words")
        // Veto law: gate1 skipped, side effects between gate1 and the NEXT gate
        // skipped — and the run hands straight into that next gate.
        assertEquals(RunStatus.WAITING_USER, afterVeto.status)
        assertEquals(StepStatus.SKIPPED, afterVeto.stepById("gate1")!!.status)
        assertEquals(StepStatus.SKIPPED, afterVeto.stepById("send")!!.status)
        assertEquals(StepStatus.WAITING_USER, afterVeto.stepById("gate2")!!.status)
        assertEquals(false, browserRan)

        // Later gates are unaffected by the earlier veto.
        val gate2 = afterVeto.stepById("gate2")!!
        val finished = engine.deliverGate(
            afterVeto.id,
            "gate2",
            approved = true,
            contentHashAtApproval = gate2.artifactContentHash,
            note = "task ok",
        )
        assertEquals(RunStatus.COMPLETED, finished.status)
        assertEquals(StepStatus.COMPLETED, finished.stepById("gate2")!!.status)
        assertEquals(StepStatus.COMPLETED, finished.stepById("later")!!.status)
        assertEquals(StepStatus.SKIPPED, finished.stepById("send")!!.status)
        assertEquals(false, browserRan)
        assertEquals(0, audit.events.size) // nothing left the device
    }

    @Test
    fun approvalHashMismatchLandsInNeedsReviewAndCanReopenGate() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("x") },
                browser = BrowserExecutor { _, _ -> Attempt.Completed("posted") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Tamper","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val started = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.WAITING_USER, started.status)

        val mismatch = engine.deliverGate(started.id, "gate", approved = true, contentHashAtApproval = "0".repeat(64), note = "")
        assertEquals(RunStatus.NEEDS_REVIEW, mismatch.status)
        assertEquals(StepStatus.NEEDS_REVIEW, mismatch.stepById("gate")!!.status)

        val reopened = engine.resolveReview(mismatch.id, "gate", retry = true)
        assertEquals(RunStatus.WAITING_USER, reopened.status) // gate rebuilt, waits again

        val finished = engine.deliverGate(reopened.id, "gate", approved = true, contentHashAtApproval = gateArtifactHash, note = "re-bound")
        assertEquals(RunStatus.COMPLETED, finished.status)
        assertEquals("posted", finished.stepById("send")!!.output)
    }

    @Test
    fun metricCapturePersistsPostShellAndSample() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                metric = MetricExecutor { _, _ -> Attempt.Measured(app.memo.core.plan.OutcomeParser.ParsedNumber(1234, false)) },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Measure","steps":[
                {"id":"m","kind":"metric_collect","title":"Capture views","metricKey":"views","metricUrl":"https://social.example/p1"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(2, store.rows.size)
        val postKey = NaturalKeys.post("https://social.example/p1")
        assertEquals("post", store.rows[0].first)
        assertEquals(postKey, store.rows[0].second)
        assertEquals("metric_sample", store.rows[1].first)
        assertEquals(NaturalKeys.metric(postKey, "views", clock.value), store.rows[1].second)
        assertEquals("measured views=1234", run.steps.single().output)
    }

    @Test
    fun branchOnFailureDivertsToFallbackPath() {
        val clock = MutableClock()
        val store = RecordingStore()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    Attempt.Completed("""{"url":"https://social.example/p1"}""")
                },
                metric = MetricExecutor { _, _ -> Attempt.Failed("no numbers on page") },
                dataStore = store,
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Resilient capture","steps":[
                {"id":"seed","kind":"reason","title":"Seed url","prompt":"produce url"},
                {"id":"m","kind":"metric_collect","title":"Capture","metricKey":"likes","metricUrl":"https://social.example/p1"},
                {"id":"decide","kind":"branch","branches":[{"whenStep":"m","outcome":"FAILED","goto":"save"}]},
                {"id":"persist_draft","kind":"data_persist","title":"Persist draft","artifactType":"draft"},
                {"id":"save","kind":"data_persist","title":"Save post","artifactType":"post"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(StepStatus.COMPLETED, run.stepById("seed")!!.status)
        assertEquals(StepStatus.FAILED, run.stepById("m")!!.status)
        assertEquals(StepStatus.COMPLETED, run.stepById("decide")!!.status)
        assertEquals(StepStatus.SKIPPED, run.stepById("persist_draft")!!.status)
        assertEquals(StepStatus.COMPLETED, run.stepById("save")!!.status)
        // Only the fallback persist actually stored a row ("seed" -> post shell from url).
        assertEquals(1, store.rows.size)
        assertEquals("post", store.rows.single().first)
    }

    @Test
    fun retryBackoffWaitsClockAndRecovers() {
        val clock = MutableClock()
        var calls = 0
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    if (calls++ == 0) Attempt.Failed("flaky network") else Attempt.Completed("recovered")
                },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Flaky","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"x"},
                {"id":"r","kind":"retry","target":"think","attemptsMax":2}
             ]}""",
            clock.value,
        )
        val first = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.RUNNING, first.status)
        assertEquals(StepStatus.PENDING, first.stepById("think")!!.status)
        assertEquals(1, first.stepById("think")!!.attempts)
        assertEquals(clock.value + 4_000L, first.stepById("think")!!.resumeAt)

        // Not yet: backoff window still open.
        val still = engine.advance(first.id)
        assertEquals(RunStatus.RUNNING, still.status)
        assertEquals(1, engine.run(first.id)!!.stepById("think")!!.attempts)

        clock.value += 4_000L
        val done = engine.advance(first.id)
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(2, done.stepById("think")!!.attempts)
        assertEquals("recovered", done.stepById("think")!!.output)
    }

    @Test
    fun interruptionOfSideEffectNeedsHumanReview() {
        val clock = MutableClock()
        var throwOnSend = true
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("x") },
                browser = BrowserExecutor { _, _ ->
                    if (throwOnSend) error("device died mid flight")
                    Attempt.Completed("posted later")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Interrupt","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val opened = engine.advance(engine.createRun(spec).id)
        val approved = engine.deliverGate(opened.id, "gate", true, gateArtifactHash, "ok")
        assertEquals(RunStatus.INTERRUPTED, approved.status) // executor threw mid side effect
        assertEquals(StepStatus.INTERRUPTED, approved.stepById("send")!!.status)

        // The law: interrupted side effects NEVER auto-resume.
        val resumed = engine.resumeInterrupted(approved.id)
        assertEquals(RunStatus.NEEDS_REVIEW, resumed.status)
        assertEquals(StepStatus.NEEDS_REVIEW, resumed.stepById("send")!!.status)

        throwOnSend = false
        val resolved = engine.resolveReview(resumed.id, "send", retry = true)
        assertEquals(RunStatus.COMPLETED, resolved.status)
        assertEquals("posted later", resolved.stepById("send")!!.output)
    }

    @Test
    fun pureStepsAutoResumeAfterInterruption() {
        val clock = MutableClock()
        var throwOnce = true
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ ->
                    if (throwOnce) error("transient crash")
                    Attempt.Completed("after crash")
                },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Pure","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"x"}
             ]}""",
            clock.value,
        )
        val interrupted = engine.advance(engine.createRun(spec).id)
        assertEquals(RunStatus.INTERRUPTED, interrupted.status)
        throwOnce = false
        val resumed = engine.resumeInterrupted(interrupted.id)
        assertEquals(RunStatus.COMPLETED, resumed.status)
        assertEquals("after crash", resumed.stepById("think")!!.output)
    }

    @Test
    fun createRunRefusesGatelessSideEffects() {
        val gateless = WorkflowSpec(
            id = "bad",
            title = "bad",
            steps = listOf(
                StepSpec(id = "send", kind = StepKind.BROWSER_AUTOMATION, provider = "grok", artifactHint = "send: x", requiresGate = true),
            ),
        )
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(),
            clock = { 0L },
        )
        assertFailsWith<IllegalStateException> { engine.createRun(gateless) }
    }

    @Test
    fun persistedRunRestoresAndContinuesAfterGate() {
        val clock = MutableClock()
        val audit = RecordingAudit()
        fun buildEngine(): WorkflowEngine = WorkflowEngine(
            routes = ExecutorRoutes(
                browser = BrowserExecutor { ctx, _ ->
                    check(ctx.approvedArtifactHash == gateArtifactHash) { "binding lost across restore" }
                    Attempt.Completed("posted after restore")
                },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
            audit = audit,
        )
        val first = buildEngine()
        val spec = plan(
            """{"title":"Resume","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val opened = first.advance(first.createRun(spec).id)
        val decoded = AgentRun.fromJson(opened.toJson())!!

        val second = buildEngine()
        second.putRun(decoded)
        val finished = second.deliverGate(decoded.id, "gate", true, gateArtifactHash, "restored ok")
        assertEquals(RunStatus.COMPLETED, finished.status)
        assertEquals("posted after restore", finished.stepById("send")!!.output)
        assertEquals(1, audit.events.size)
    }

    @Test
    fun cancelSkipsOpenWorkAndCarriesReason() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("x") },
                artifactBuilder = ArtifactBuilder { _, _, _ -> Attempt.Completed(gateArtifact) },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"Cancel me","steps":[
                {"id":"gate","kind":"human_approval","title":"Approve","artifactType":"draft"},
                {"id":"send","kind":"browser_automation","title":"Send","provider":"grok","artifactHint":"send: x","requiresGate":true}
             ]}""",
            clock.value,
        )
        val opened = engine.advance(engine.createRun(spec).id)
        val cancelled = engine.cancel(opened.id, "changed my mind")
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals(StepStatus.SKIPPED, cancelled.stepById("gate")!!.status)
        assertEquals(StepStatus.SKIPPED, cancelled.stepById("send")!!.status)
        assertTrue(cancelled.stepById("gate")!!.failure.contains("changed my mind"))
    }

    @Test
    fun runJsonRoundTripIsLosslessForTheCoreFields() {
        val clock = MutableClock()
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = ReasonExecutor { _, _ -> Attempt.Completed("out") },
            ),
            clock = { clock.value },
        )
        val spec = plan(
            """{"title":"RT","steps":[
                {"id":"think","kind":"reason","title":"Think","prompt":"x"}
             ]}""",
            clock.value,
        )
        val run = engine.advance(engine.createRun(spec).id)
        val decoded = AgentRun.fromJson(run.toJson())!!
        assertEquals(run.status, decoded.status)
        assertEquals(run.pointer, decoded.pointer)
        assertEquals(run.steps.single().output, decoded.steps.single().output)
        assertEquals(StepStatus.COMPLETED, decoded.steps.single().status)
        assertEquals(run.spec.title, decoded.spec.title)
    }
}
