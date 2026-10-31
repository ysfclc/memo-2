package app.memo.runtime

import app.memo.core.agent.AgentReply
import app.memo.core.agent.AgentResult
import app.memo.core.agent.AgentStepRecord
import app.memo.core.agent.AgentStop
import app.memo.core.agent.QueuedSideEffect
import app.memo.core.agent.ToolCatalog
import app.memo.core.domain.Person
import app.memo.core.engine.Attempt
import app.memo.core.plan.PlanModel
import app.memo.core.registry.Workspace
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AgentToolsTest {

    private val seeded = Workspace().apply {
        people.upsert(Person(fullName = "Ana Silva", notes = "quarterly forecast"), now = 1L)
    }
    private val anaKey = seeded.people.scheme.id(seeded.people.items().single()).trim().lowercase()

    private fun call(tool: String, vararg args: Pair<String, Any>): AgentReply.Call =
        AgentReply.Call(ToolCatalog.byName(tool)!!, mapOf(*args))

    /** Records every prompt it is given and answers with [reply]. */
    private class FakeModel(private val reply: String) : PlanModel {
        val prompts = mutableListOf<Pair<String, String>>()
        override fun complete(system: String, user: String): String {
            prompts += system to user
            return reply
        }
    }

    private fun runner(model: PlanModel? = null, ws: Workspace = seeded) = WorkspaceToolRunner({ ws }, model)

    @Test
    fun searchWorkspaceReturnsHitLineForSeededPerson() {
        val out = runner().run(call("search_workspace", "query" to "ana silva"))

        assertEquals("people | ana silva | $anaKey", out)
    }

    @Test
    fun searchWorkspaceHonoursKindFilterAndReportsNoMatches() {
        val out = runner().run(call("search_workspace", "query" to "ana silva", "kind" to "companies"))

        assertEquals(WorkspaceToolRunner.NO_MATCHES, out)
    }

    @Test
    fun getRecordByKeyReturnsItsJson() {
        val out = runner().run(call("get_record", "kind" to "people", "key" to anaKey))

        val json = JSONObject(out)
        assertEquals("Ana Silva", json.getString("fullName"))
        assertEquals("quarterly forecast", json.getString("notes"))
    }

    @Test
    fun getRecordByDisplayNameFindsTheRow() {
        val out = runner().run(call("get_record", "kind" to "people", "key" to "Ana Silva"))

        assertEquals("Ana Silva", JSONObject(out).getString("fullName"))
    }

    @Test
    fun getRecordReportsMissingRecordAndUnknownKind() {
        assertEquals("No record people/nobody-here.", runner().run(call("get_record", "kind" to "people", "key" to "nobody-here")))
        assertEquals("No record widgets/x.", runner().run(call("get_record", "kind" to "widgets", "key" to "x")))
    }

    @Test
    fun draftTextUsesTheModelAndForwardsTheWordLimit() {
        val model = FakeModel("  Hello Ana, the forecast is ready.  ")

        val out = runner(model).run(call("draft_text", "instruction" to "reply to Ana", "max_words" to 40L))

        assertEquals("Hello Ana, the forecast is ready.", out)
        assertContains(model.prompts.single().second, "reply to Ana")
        assertContains(model.prompts.single().second, "about 40 words")
    }

    @Test
    fun modelToolsReportWhenNoLocalModelIsAvailable() {
        assertEquals(WorkspaceToolRunner.NO_MODEL, runner(null).run(call("draft_text", "instruction" to "x")))
        assertEquals(WorkspaceToolRunner.NO_MODEL, runner(null).run(call("summarize", "text" to "x")))
        assertEquals(
            WorkspaceToolRunner.NO_MODEL,
            runner(null).run(call("extract_json", "text" to "x", "fields" to listOf("name"))),
        )
    }

    @Test
    fun askUserRecordsTheQuestion() {
        assertEquals(
            "Question noted for the user: Which Ana?",
            runner().run(call("ask_user", "question" to "Which Ana?")),
        )
    }

    @Test
    fun browseCaptureReportsTheM4Limit() {
        assertEquals(
            "The in-app browser is not available yet (M4).",
            runner().run(call("browse_capture", "provider" to "p", "what" to "w")),
        )
    }

    @Test
    fun sideEffectingAndUnknownToolsNeverRun() {
        assertFailsWith<IllegalArgumentException> {
            runner().run(call("create_record", "kind" to "people", "fields_json" to "{}"))
        }
        assertFailsWith<IllegalArgumentException> {
            runner().run(AgentReply.Call(ToolCatalog.all.first().copy(name = "no_such_tool"), emptyMap()))
        }
    }

    @Test
    fun summarizeFinalCompletesWithTheAnswer() {
        val result = AgentResult(AgentStop.FINAL, "Ana is free on Monday.", emptyList(), emptyList(), escalate = false)

        assertEquals(Attempt.Completed(output = "Ana is free on Monday."), WorkspaceToolRunner.summarize(result))
    }

    @Test
    fun summarizeIncompleteRunStatesTheStopAndLastObservation() {
        val step = AgentStepRecord(
            modelOutput = "{}",
            reply = AgentReply.Invalid(listOf("no tool")),
            observation = "Ana Silva | people",
        )
        val result = AgentResult(AgentStop.NO_PROGRESS, null, listOf(step), emptyList(), escalate = false)

        val attempt = WorkspaceToolRunner.summarize(result)

        assertIs<Attempt.Completed>(attempt)
        assertEquals("Agent stopped (NO_PROGRESS): Ana Silva | people", attempt.output)
    }

    @Test
    fun summarizeReportsQueuedSideEffectsAsNotExecuted() {
        val queued = QueuedSideEffect(call("create_record", "kind" to "people", "fields_json" to "{}"))
        val step = AgentStepRecord("{}", queued.call, observation = "Queued for the user's approval; not executed.")
        val result = AgentResult(AgentStop.FINAL, "Drafted the record.", listOf(step), listOf(queued), escalate = false)

        val attempt = WorkspaceToolRunner.summarize(result)

        assertIs<Attempt.Completed>(attempt)
        assertContains(attempt.output, "Needs approval (not executed): create_record")
    }

    @Test
    fun summarizeModelErrorWithoutStepsFails() {
        val result = AgentResult(AgentStop.MODEL_ERROR, null, emptyList(), emptyList(), escalate = false)

        assertEquals(Attempt.Failed("MODEL_ERROR"), WorkspaceToolRunner.summarize(result))
    }
}
