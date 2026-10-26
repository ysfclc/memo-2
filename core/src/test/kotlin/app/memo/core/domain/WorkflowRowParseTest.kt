package app.memo.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject

/** Unknown enum values make a row unparseable; they are never defaulted. */
class WorkflowRowParseTest {

    private val goodStep = StepSpec(id = "s1", kind = StepKind.REASON, title = "Think")

    private fun goodRun(): AgentRun = AgentRun(
        id = "run-1",
        spec = WorkflowSpec(id = "w", title = "w", steps = listOf(goodStep)),
        status = RunStatus.RUNNING,
        createdAt = 1L,
        steps = listOf(StepState(spec = goodStep)),
    )

    @Test
    fun knownRowsRoundTrip() {
        val run = goodRun()
        assertEquals(run, AgentRun.fromJson(JSONObject(run.toJson().toString())))
    }

    @Test
    fun unknownStepKindIsUnparseable() {
        val json = JSONObject(goodStep.toJson().toString()).put("kind", "TELEPORT")
        assertNull(StepSpec.fromJson(json))
    }

    @Test
    fun unknownStepStatusIsUnparseable() {
        val json = JSONObject(StepState(spec = goodStep).toJson().toString()).put("status", "ALMOST_DONE")
        assertNull(StepState.fromJson(json))
    }

    @Test
    fun missingStepStatusIsUnparseableNotPending() {
        val json = JSONObject(StepState(spec = goodStep).toJson().toString())
        json.remove("status")
        assertNull(StepState.fromJson(json))
    }

    @Test
    fun unknownRunStatusIsUnparseable() {
        val json = JSONObject(goodRun().toJson().toString()).put("status", "FLYING")
        assertNull(AgentRun.fromJson(json))
    }

    @Test
    fun runWithAnUnknownStepKindIsDroppedWholeNotShortened() {
        val run = goodRun()
        val json = JSONObject(run.toJson().toString())
        json.getJSONObject("spec").getJSONArray("steps").getJSONObject(0).put("kind", "TELEPORT")
        json.getJSONArray("steps").getJSONObject(0).getJSONObject("spec").put("kind", "TELEPORT")
        assertNull(AgentRun.fromJson(json))
    }
}
