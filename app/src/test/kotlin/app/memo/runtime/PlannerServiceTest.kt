package app.memo.runtime

import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.Orchestrator
import app.memo.core.plan.PlanModel
import app.memo.core.plan.TemplateLibrary
import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.ModelFormat
import app.memo.core.platform.ModelRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerServiceTest {


    /** Scripted plan model: returns [output] for every call and counts the calls. */
    private class FakePlanModel(private val output: String) : PlanModel {
        var callCount = 0
            private set

        override fun complete(system: String, user: String): String {
            callCount++
            return output
        }
    }

    private val seedPlan = TemplateLibrary.seeds.single { it.name == "uc2_crm_capture" }.planJson

    /** Builds a service around the real orchestrator with [planModel] as its only model. */
    private class Harness(
        val planModel: FakePlanModel,
        resolved: Boolean = true,
    ) {
        val proposed = mutableListOf<WorkflowSpec>()
        val commands = mutableListOf<String>()
        private val resolvedModel = if (resolved) ModelRef("local-chat", ModelFormat.GGUF, "/m.gguf", "a".repeat(64), 1L) else null

        val service = PlannerService(
            orchestratorFor = { Orchestrator(model = planModel, clock = { 1_700_000_000_000L }) },
            resolveModel = { resolvedModel },
            templates = { TemplateLibrary.seeds },
            propose = { spec ->
                proposed += spec
                "run-proposal-${proposed.size}"
            },
            runCommand = { text ->
                commands += text
                "COMMAND REPLY"
            },
        )
    }

    @Test
    fun noInstalledModelRepliesWithoutStartingAnything() {
        val harness = Harness(FakePlanModel(seedPlan), resolved = false)

        val reply = harness.service.plan("plan a post about our launch", null)

        assertContains(reply.text, "Install a chat model")
        assertNull(reply.runId)
        assertTrue(harness.proposed.isEmpty())
        assertEquals(0, harness.planModel.callCount)
    }

    @Test
    fun commandTextGoesToTheCommandPathOnly() {
        val harness = Harness(FakePlanModel(seedPlan))
        val text = "remind me to call Ana in 10 minutes"

        val reply = harness.service.plan(text, "local-chat")

        assertEquals(PlanReply("COMMAND REPLY", null), reply)
        assertEquals(listOf(text), harness.commands)
        assertTrue(harness.proposed.isEmpty())
        assertEquals(0, harness.planModel.callCount)
    }

    @Test
    fun validModelPlanIsProposedOnceAndLinkedToItsRun() {
        val harness = Harness(FakePlanModel(seedPlan))

        val reply = harness.service.plan("plan a post about our launch", "local-chat")

        assertEquals(1, harness.planModel.callCount)
        assertEquals(1, harness.proposed.size)
        assertEquals("Research a contact and capture the person record", harness.proposed.single().title)
        assertEquals("run-proposal-1", reply.runId)
        assertTrue(reply.text.startsWith("Planned \""), reply.text)
        assertTrue(reply.text.endsWith("then tap Run."), reply.text)
    }

    @Test
    fun storedTemplateIsReusedWithoutCallingTheModel() {
        val harness = Harness(FakePlanModel("not used"))
        val example = TemplateLibrary.seeds.first().intentExamples.first()

        val reply = harness.service.plan("plan $example", "local-chat")

        assertContains(reply.text, "from a saved template")
        assertEquals(0, harness.planModel.callCount)
        assertEquals(1, harness.proposed.size)
        assertEquals("run-proposal-1", reply.runId)
    }

    @Test
    fun garbageModelOutputBecomesALabelledOneStepFallback() {
        val harness = Harness(FakePlanModel("this is not a plan"))

        val reply = harness.service.plan("plan something the model cannot shape", "local-chat")

        assertContains(reply.text, "Could not make a valid plan")
        assertEquals(1, harness.proposed.size)
        assertEquals(1, harness.proposed.single().steps.size)
        assertEquals("run-proposal-1", reply.runId)
    }

    @Test
    fun modelOutputIsCollectedUntilComplete() {
        val turns = mutableListOf<List<ChatTurn>>()
        val params = mutableListOf<GenParams>()
        val planner = InferencePlanModel(
            submit = { _, sentTurns, sentParams, sink ->
                turns += sentTurns
                params += sentParams
                sink.onToken("a")
                sink.onToken("b")
                sink.onComplete(GenStats(1, 2, 3, "test"))
                7L
            },
            cancel = { },
            model = modelRef(),
        )

        assertEquals("ab", planner.complete("system text", "user text"))
        assertEquals(
            listOf(ChatTurn.Role.SYSTEM, ChatTurn.Role.USER),
            turns.single().map { it.role },
        )
        assertEquals(listOf("system text", "user text"), turns.single().map { it.text })
        assertEquals(false, params.single().thinking)
    }

    @Test
    fun modelErrorThrows() {
        val planner = InferencePlanModel(
            submit = { _, _, _, sink ->
                sink.onError(LlmError.Other("boom"))
                1L
            },
            cancel = { },
            model = modelRef(),
        )

        assertFailsWith<IllegalStateException> { planner.complete("s", "u") }
    }

    @Test
    fun neverCompletingRequestTimesOutAndIsCancelled() {
        val cancelled = mutableListOf<Long>()
        val planner = InferencePlanModel(
            submit = { _, _, _, _ -> 9L },
            cancel = { cancelled += it },
            model = modelRef(),
            timeoutMs = 50,
        )

        assertFailsWith<IllegalStateException> { planner.complete("s", "u") }
        assertEquals(listOf(9L), cancelled)
    }

    private fun modelRef(): ModelRef = ModelRef("local-chat", ModelFormat.GGUF, "/m.gguf", "a".repeat(64), 1L)
}
