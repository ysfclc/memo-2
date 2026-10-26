package app.memo.runtime

import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeRulesTest {

    private val now = 1_700_000_000_000L

    private val reason = StepSpec(id = "think", kind = StepKind.REASON, title = "Think")
    private val gate = StepSpec(id = "gate", kind = StepKind.HUMAN_APPROVAL, title = "Approve", artifactType = "draft")
    private val send = StepSpec(
        id = "send", kind = StepKind.BROWSER_AUTOMATION, title = "Send",
        provider = "grok", artifactHint = "send: x", requiresGate = true,
    )

    private fun run(
        steps: List<StepState>,
        status: RunStatus = RunStatus.RUNNING,
        pointer: Int = 0,
    ) = AgentRun(
        id = "r",
        spec = WorkflowSpec(id = "w", title = "w", steps = steps.map { it.spec }),
        status = status,
        createdAt = now,
        steps = steps,
        pointer = pointer,
    )

    // ------------------------------------------------------------ isDue

    @Test
    fun runIsDueOnceItsWaitingStepReachesResumeAt() {
        val waiting = StepState(spec = reason, resumeAt = now + 5_000L)
        assertFalse(RuntimeRules.isDue(run(listOf(waiting)), now))
        assertTrue(RuntimeRules.isDue(run(listOf(waiting)), now + 5_000L))
    }

    @Test
    fun runWithoutRunningStatusIsNeverDue() {
        val ready = StepState(spec = reason, resumeAt = 0)
        assertFalse(RuntimeRules.isDue(run(listOf(ready), status = RunStatus.WAITING_USER), now))
        assertFalse(RuntimeRules.isDue(run(listOf(ready), status = RunStatus.NEEDS_REVIEW), now))
    }

    @Test
    fun runWaitingOnExternalWorkIsNotDueAndTheDriverDoesNotPoll() {
        val inFlight = StepState(spec = send, status = StepStatus.RUNNING, externalTicket = "job-1", resumeAt = 0)
        assertFalse(RuntimeRules.isDue(run(listOf(gateCompleted(), inFlight), pointer = 1), now))
    }

    private fun gateCompleted() = StepState(spec = gate, status = StepStatus.COMPLETED)

    // ---------------------------------------------------------- nextWake

    @Test
    fun nextWakeIsTheEarliestFutureBackoff() {
        val early = run(listOf(StepState(spec = reason, resumeAt = now + 3_000L)))
        val late = run(listOf(StepState(spec = reason, resumeAt = now + 9_000L)))
        assertEquals(now + 3_000L, RuntimeRules.nextWake(listOf(late, early), now))
    }

    @Test
    fun nextWakeIsNullWhenNothingWaits() {
        val ready = run(listOf(StepState(spec = reason, resumeAt = 0)))
        val paused = run(listOf(StepState(spec = reason, resumeAt = now + 1_000L)), status = RunStatus.WAITING_USER)
        assertNull(RuntimeRules.nextWake(listOf(ready, paused), now))
    }

    @Test
    fun tickIsNeverSoonerThanOneSecond() {
        assertEquals(1_000L, RuntimeRules.tickDelayMs(now + 10L, now))
        assertEquals(4_000L, RuntimeRules.tickDelayMs(now + 4_000L, now))
    }

    // --------------------------------------------------- decisionMessage

    @Test
    fun decisionReportsTheRealOutcome() {
        val approved = run(listOf(gateCompleted()), pointer = 1)
        assertEquals("Approved.", RuntimeRules.decisionMessage(true, "gate", Result.success(approved)))
        assertEquals("Vetoed.", RuntimeRules.decisionMessage(false, "gate", Result.success(approved)))

        val mismatch = run(listOf(StepState(spec = gate, status = StepStatus.NEEDS_REVIEW)))
        assertEquals(
            "Content changed, review again.",
            RuntimeRules.decisionMessage(true, "gate", Result.success(mismatch)),
        )
    }

    @Test
    fun decisionErrorIsReportedWithoutItsFullText() {
        val failed = Result.failure<AgentRun>(IllegalStateException("gate secret-body-text " + "x".repeat(300)))
        val message = RuntimeRules.decisionMessage(true, "gate", failed)
        assertTrue(message.startsWith("Could not record the decision: IllegalStateException: "), message)
        assertTrue(message.length <= 220, "message was ${message.length} chars")
    }

    // ------------------------------------------------- needsBackupReview

    @Test
    fun sideEffectAfterCompletedGateNeedsBackupReview() {
        val run = run(listOf(gateCompleted(), StepState(spec = send)))
        assertTrue(RuntimeRules.needsBackupReview(run))
    }

    @Test
    fun sideEffectWithoutCompletedGateDoesNotNeedBackupReview() {
        val waitingGate = StepState(spec = gate, status = StepStatus.WAITING_USER)
        assertFalse(RuntimeRules.needsBackupReview(run(listOf(waitingGate, StepState(spec = send)))))
        assertFalse(RuntimeRules.needsBackupReview(run(listOf(StepState(spec = reason), StepState(spec = send)))))
    }
}
