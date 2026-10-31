package app.memo.ui.chat

import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.PlanSource
import app.memo.ui.components.StepRailItem
import app.memo.ui.components.StepVisual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanCardStateTest {

    private val reason = StepSpec(id = "s1", kind = StepKind.REASON, title = "Draft the plan")
    private val gate = StepSpec(
        id = "s2",
        kind = StepKind.HUMAN_APPROVAL,
        title = "Approve invoice",
        artifactType = "invoice",
        requiresGate = true,
    )
    private val persist = StepSpec(id = "s3", kind = StepKind.DATA_PERSIST, title = "Save invoice")

    private fun run(
        status: RunStatus,
        steps: List<StepState>,
        failure: String = "",
    ) = AgentRun(
        id = "run-1",
        spec = WorkflowSpec(id = "wf-1", title = "Invoice flow", steps = steps.map { it.spec }),
        status = status,
        createdAt = 1L,
        failure = failure,
        steps = steps,
    )

    private fun state(spec: StepSpec, status: StepStatus, artifact: String = "", hash: String = "") =
        StepState(spec = spec, status = status, artifact = artifact, artifactContentHash = hash)

    @Test
    fun `pending run offers run and discard with all steps pending`() {
        val card = PlanCards.from(
            run(
                RunStatus.PENDING,
                listOf(
                    state(reason, StepStatus.PENDING),
                    state(gate, StepStatus.PENDING),
                    state(persist, StepStatus.PENDING),
                ),
            ),
        )
        assertEquals(setOf(PlanAction.RUN, PlanAction.DISCARD), card.actions)
        assertEquals("Ready to run", card.statusLine)
        assertEquals(listOf(StepVisual.PENDING, StepVisual.PENDING, StepVisual.PENDING), card.steps.map { it.visual })
        assertEquals(listOf("Draft the plan", "Approve invoice", "Save invoice"), card.steps.map { it.title })
        assertNull(card.gate)
        assertEquals("run-1", card.runId)
        assertEquals("Invoice flow", card.title)
    }

    @Test
    fun `waiting at a gate with an artifact exposes the gate and approve veto stop`() {
        val json = """{"invoice":"42"}"""
        val card = PlanCards.from(
            run(
                RunStatus.WAITING_USER,
                listOf(
                    state(reason, StepStatus.COMPLETED),
                    state(gate, StepStatus.WAITING_USER, artifact = json, hash = "sha-abc"),
                    state(persist, StepStatus.PENDING),
                ),
            ),
        )
        val g = assertNotNull(card.gate)
        assertEquals("s2", g.stepId)
        assertEquals("invoice", g.artifactType)
        assertEquals(json, g.artifactJson)
        assertEquals("sha-abc", g.artifactHash)
        assertEquals(setOf(PlanAction.APPROVE, PlanAction.VETO, PlanAction.STOP), card.actions)
        assertEquals("Waiting for your approval", card.statusLine)
        assertEquals(StepVisual.WAITING, card.steps[1].visual)
    }

    @Test
    fun `waiting at a gate with a blank artifact has no gate and only stop`() {
        val card = PlanCards.from(
            run(
                RunStatus.WAITING_USER,
                listOf(
                    state(reason, StepStatus.COMPLETED),
                    state(gate, StepStatus.WAITING_USER, artifact = "   ", hash = "sha-abc"),
                ),
            ),
        )
        assertNull(card.gate)
        assertEquals(setOf(PlanAction.STOP), card.actions)
        assertEquals("Waiting for you", card.statusLine)
    }

    @Test
    fun `waiting on a non-approval step has no gate even with an artifact`() {
        val card = PlanCards.from(
            run(
                RunStatus.WAITING_USER,
                listOf(state(persist, StepStatus.WAITING_USER, artifact = "{}", hash = "h")),
            ),
        )
        assertNull(card.gate)
        assertEquals(setOf(PlanAction.STOP), card.actions)
    }

    @Test
    fun `completed run has no actions and every executed step is done`() {
        val card = PlanCards.from(
            run(
                RunStatus.COMPLETED,
                listOf(
                    state(reason, StepStatus.COMPLETED),
                    state(gate, StepStatus.COMPLETED),
                    state(persist, StepStatus.COMPLETED),
                ),
            ),
        )
        assertTrue(card.actions.isEmpty())
        assertEquals("Done", card.statusLine)
        assertTrue(card.steps.all { it.visual == StepVisual.DONE })
        assertNull(card.gate)
    }

    @Test
    fun `running run offers stop only`() {
        val card = PlanCards.from(
            run(RunStatus.RUNNING, listOf(state(reason, StepStatus.RUNNING), state(gate, StepStatus.PENDING))),
        )
        assertEquals(setOf(PlanAction.STOP), card.actions)
        assertEquals("Running", card.statusLine)
        assertEquals(listOf(StepVisual.RUNNING, StepVisual.PENDING), card.steps.map { it.visual })
    }

    @Test
    fun `failed run with a long failure is truncated and offers discard`() {
        val long = "x".repeat(500)
        val card = PlanCards.from(
            run(RunStatus.FAILED, listOf(state(reason, StepStatus.FAILED)), failure = long),
        )
        assertTrue(card.statusLine.startsWith("Failed: "))
        assertTrue(card.statusLine.length <= 128, "was ${card.statusLine.length}")
        assertEquals(setOf(PlanAction.DISCARD), card.actions)
        assertEquals(StepVisual.FAILED, card.steps[0].visual)
    }

    @Test
    fun `failed run with blank failure says just failed`() {
        val card = PlanCards.from(run(RunStatus.FAILED, listOf(state(reason, StepStatus.FAILED))))
        assertEquals("Failed", card.statusLine)
    }

    @Test
    fun `needs review and interrupted offer discard with their label`() {
        val review = PlanCards.from(run(RunStatus.NEEDS_REVIEW, listOf(state(reason, StepStatus.NEEDS_REVIEW))))
        assertEquals("Needs your review", review.statusLine)
        assertEquals(setOf(PlanAction.DISCARD), review.actions)

        val interrupted = PlanCards.from(
            run(RunStatus.INTERRUPTED, listOf(state(gate, StepStatus.INTERRUPTED)), failure = "app killed"),
        )
        assertEquals("Interrupted: app killed", interrupted.statusLine)
        assertEquals(setOf(PlanAction.DISCARD), interrupted.actions)
        assertEquals(StepVisual.WAITING, interrupted.steps[0].visual)
    }

    @Test
    fun `cancelled run has no actions and never-started steps are skipped`() {
        val card = PlanCards.from(
            run(
                RunStatus.CANCELLED,
                listOf(
                    state(reason, StepStatus.COMPLETED),
                    state(gate, StepStatus.PENDING),
                    state(persist, StepStatus.PENDING),
                ),
            ),
        )
        assertTrue(card.actions.isEmpty())
        assertEquals("Stopped", card.statusLine)
        assertEquals(
            listOf(StepVisual.DONE, StepVisual.SKIPPED, StepVisual.SKIPPED),
            card.steps.map { it.visual },
        )
    }

    @Test
    fun `step without a title falls back to its id`() {
        val untitled = StepSpec(id = "raw-step", kind = StepKind.REASON)
        val card = PlanCards.from(run(RunStatus.PENDING, listOf(state(untitled, StepStatus.PENDING))))
        assertEquals(listOf(StepRailItem("raw-step", StepVisual.PENDING)), card.steps)
    }

    @Test
    fun `source label for each plan source and for none`() {
        val r = run(RunStatus.PENDING, listOf(state(reason, StepStatus.PENDING)))
        assertEquals("From a saved template", PlanCards.from(r, PlanSource.TEMPLATE_REUSE).sourceLabel)
        assertEquals("Repaired plan", PlanCards.from(r, PlanSource.MODEL_REPAIRED).sourceLabel)
        assertEquals("Fallback: could not plan", PlanCards.from(r, PlanSource.FALLBACK).sourceLabel)
        assertNull(PlanCards.from(r, PlanSource.MODEL).sourceLabel)
        assertNull(PlanCards.from(r).sourceLabel)
    }
}
