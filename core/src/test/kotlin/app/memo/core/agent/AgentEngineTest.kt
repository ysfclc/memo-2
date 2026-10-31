package app.memo.core.agent

import app.memo.core.plan.PlanModel
import app.memo.core.prompts.ContextPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentEngineTest {

    /** Returns the scripted outputs in order and records every user prompt it was given. */
    private class ScriptedModel(private val outputs: List<String>) : PlanModel {
        val userPrompts = mutableListOf<String>()
        private var next = 0

        override fun complete(system: String, user: String): String {
            userPrompts += user
            check(next < outputs.size) { "scripted model exhausted" }
            return outputs[next++]
        }
    }

    private class CountingTools(private val failWith: String? = null) : ToolRunner {
        val calls = mutableListOf<AgentReply.Call>()

        override fun run(call: AgentReply.Call): String {
            calls += call
            failWith?.let { throw IllegalStateException(it) }
            return "result for ${call.tool.name}"
        }
    }

    private class MutableClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun search(query: String) = """{"tool":"search_workspace","args":{"query":"$query"}}"""
    private fun final(answer: String) = """{"final":"$answer"}"""

    @Test
    fun validCallThenFinalReturnsAnswerAndRunsToolOnce() {
        val model = ScriptedModel(listOf(search("invoice"), final("Found it")))
        val tools = CountingTools()
        val result = AgentEngine(model, tools, MutableClock()).run("find invoice", null)

        assertEquals(AgentStop.FINAL, result.stop)
        assertEquals("Found it", result.answer)
        assertEquals(1, tools.calls.size)
        assertEquals(2, model.userPrompts.size)
        assertTrue(model.userPrompts[1].contains("Observation:"))
        assertTrue(model.userPrompts[1].contains("result for search_workspace"))
        assertEquals(2, result.steps.size)
        assertNull(result.steps.last().observation)
    }

    @Test
    fun invalidOutputIsRepairedThenSucceeds() {
        val model = ScriptedModel(listOf("not json at all", final("Done")))
        val result = AgentEngine(model, CountingTools(), MutableClock()).run("goal", null)

        assertEquals(AgentStop.FINAL, result.stop)
        assertEquals("Done", result.answer)
        assertTrue(model.userPrompts[1].contains("rejected for"))
        assertTrue(model.userPrompts[1].contains("no JSON object found"))
    }

    @Test
    fun threeInvalidOutputsInARowStopAndEscalateOnlyWhenAllowed() {
        val invalid = listOf("nope", "still nope", "nope again")

        val escalating = AgentEngine(ScriptedModel(invalid), CountingTools(), MutableClock())
            .run("goal", null, canEscalate = true)
        assertEquals(AgentStop.INVALID_OUTPUT, escalating.stop)
        assertTrue(escalating.escalate)
        assertNull(escalating.answer)

        val local = AgentEngine(ScriptedModel(invalid), CountingTools(), MutableClock())
            .run("goal", null, canEscalate = false)
        assertEquals(AgentStop.INVALID_OUTPUT, local.stop)
        assertEquals(false, local.escalate)
    }

    @Test
    fun callBudgetStopsBeforeRunningAnotherTool() {
        val model = ScriptedModel(listOf(search("a"), search("b"), search("c"), final("never")))
        val tools = CountingTools()
        val budget = AgentBudget(maxToolCalls = 2)
        val result = AgentEngine(model, tools, MutableClock(), budget).run("goal", null)

        assertEquals(AgentStop.CALL_BUDGET, result.stop)
        assertEquals(2, tools.calls.size)
        assertEquals(false, result.escalate)
    }

    @Test
    fun identicalCallThirdTimeStopsWithNoProgress() {
        val repeated = search("same")
        val model = ScriptedModel(listOf(repeated, repeated, repeated, final("never")))
        val tools = CountingTools()
        val result = AgentEngine(model, tools, MutableClock()).run("goal", null)

        assertEquals(AgentStop.NO_PROGRESS, result.stop)
        assertEquals(2, tools.calls.size)
    }

    @Test
    fun sideEffectingToolIsQueuedAndNeverRun() {
        val create = """{"tool":"create_record","args":{"kind":"note","fields_json":"{\"title\":\"x\"}"}}"""
        val model = ScriptedModel(listOf(create, final("Queued it")))
        val tools = CountingTools()
        val result = AgentEngine(model, tools, MutableClock()).run("save a note", null)

        assertEquals(AgentStop.FINAL, result.stop)
        assertEquals("Queued it", result.answer)
        assertEquals(1, result.queued.size)
        assertEquals("create_record", result.queued.single().call.tool.name)
        assertTrue(tools.calls.isEmpty(), "side-effecting tool must not reach ToolRunner")
        assertEquals(AgentEngine.QUEUED_OBSERVATION, result.steps.first().observation)
        assertTrue(model.userPrompts[1].contains("Queued for the user's approval"))
    }

    @Test
    fun clockPastTimeBudgetAfterFirstRoundStopsWithTimeBudget() {
        val clock = MutableClock(now = 1_000L)
        val model = ScriptedModel(listOf(search("x"), final("never")))
        val budget = AgentBudget(maxMillis = 500L)
        val tools = object : ToolRunner {
            override fun run(call: AgentReply.Call): String {
                clock.now += 1_000L
                return "ok"
            }
        }
        val result = AgentEngine(model, tools, clock, budget).run("goal", null)

        assertEquals(AgentStop.TIME_BUDGET, result.stop)
        assertEquals(1, result.steps.size)
    }

    @Test
    fun modelErrorStopsWithoutThrowingAndEscalatesWhenAllowed() {
        val failing = PlanModel { _, _ -> throw IllegalStateException("model crashed") }
        val result = AgentEngine(failing, CountingTools(), MutableClock())
            .run("goal", null, canEscalate = true)

        assertEquals(AgentStop.MODEL_ERROR, result.stop)
        assertTrue(result.escalate)
        assertNull(result.answer)
    }

    @Test
    fun toolFailureBecomesObservationAndLoopContinues() {
        val model = ScriptedModel(listOf(search("boom"), final("recovered")))
        val tools = CountingTools(failWith = "disk offline")
        val result = AgentEngine(model, tools, MutableClock()).run("goal", null)

        assertEquals(AgentStop.FINAL, result.stop)
        assertEquals("recovered", result.answer)
        assertEquals("Tool failed: disk offline", result.steps.first().observation)
        assertTrue(model.userPrompts[1].contains("Tool failed: disk offline"))
    }

    @Test
    fun longObservationIsTruncatedWithEllipsis() {
        val long = object : ToolRunner {
            override fun run(call: AgentReply.Call): String = "x".repeat(5_000)
        }
        val model = ScriptedModel(listOf(search("big"), final("ok")))
        val budget = AgentBudget(maxObservationChars = 100)
        val result = AgentEngine(model, long, MutableClock(), budget).run("goal", null)

        val observation = result.steps.first().observation!!
        assertEquals(101, observation.length)
        assertTrue(observation.endsWith("…"))
        assertEquals("x".repeat(100) + "…", observation)
    }

    @Test
    fun packetContextIsIncludedWhenNonEmpty() {
        val packet = ContextPacket.Builder().put("name", "Ada").build()
        val model = ScriptedModel(listOf(final("hi")))
        AgentEngine(model, CountingTools(), MutableClock()).run("greet", packet)

        assertTrue(model.userPrompts.first().contains("Context:"))
        assertTrue(model.userPrompts.first().contains("name: Ada"))
    }
}
