package app.memo.ui.components

import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class StepRailStateTest {

    @Test
    fun everyStepStatusMapsWhileTheRunIsLive() {
        val live = RunStatus.RUNNING
        assertEquals(StepVisual.PENDING, stepVisual(StepStatus.PENDING, live))
        assertEquals(StepVisual.RUNNING, stepVisual(StepStatus.RUNNING, live))
        assertEquals(StepVisual.WAITING, stepVisual(StepStatus.WAITING_USER, live))
        assertEquals(StepVisual.DONE, stepVisual(StepStatus.COMPLETED, live))
        assertEquals(StepVisual.FAILED, stepVisual(StepStatus.FAILED, live))
        assertEquals(StepVisual.SKIPPED, stepVisual(StepStatus.SKIPPED, live))
        assertEquals(StepVisual.WAITING, stepVisual(StepStatus.NEEDS_REVIEW, live))
        assertEquals(StepVisual.WAITING, stepVisual(StepStatus.INTERRUPTED, live))
    }

    @Test
    fun everyStepStatusHasAVisual() {
        StepStatus.entries.forEach { status ->
            RunStatus.entries.forEach { run ->
                // Must not throw for any pair.
                stepVisual(status, run)
            }
        }
    }

    @Test
    fun pendingStepsAreSkippedOnceTheRunIsTerminal() {
        listOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED).forEach { run ->
            assertEquals(StepVisual.SKIPPED, stepVisual(StepStatus.PENDING, run), "run $run")
        }
        assertEquals(StepVisual.PENDING, stepVisual(StepStatus.PENDING, RunStatus.RUNNING))
        assertEquals(StepVisual.PENDING, stepVisual(StepStatus.PENDING, RunStatus.NEEDS_REVIEW))
    }

    @Test
    fun cancellingARunSkipsRunningAndWaitingSteps() {
        assertEquals(StepVisual.SKIPPED, stepVisual(StepStatus.RUNNING, RunStatus.CANCELLED))
        assertEquals(StepVisual.SKIPPED, stepVisual(StepStatus.WAITING_USER, RunStatus.CANCELLED))
        assertEquals(StepVisual.RUNNING, stepVisual(StepStatus.RUNNING, RunStatus.RUNNING))
    }

    @Test
    fun completedAndFailedStepsKeepTheirOutcomeInAnyRun() {
        RunStatus.entries.forEach { run ->
            assertEquals(StepVisual.DONE, stepVisual(StepStatus.COMPLETED, run), "run $run")
            assertEquals(StepVisual.FAILED, stepVisual(StepStatus.FAILED, run), "run $run")
        }
    }
}
