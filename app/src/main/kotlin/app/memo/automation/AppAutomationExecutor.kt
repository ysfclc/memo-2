package app.memo.automation

import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.engine.WorkflowEngine
import app.memo.core.domain.Attempt
import android.util.Log

/**
 * Executor for APP_AUTOMATION steps — native app gestures via UiAutomation
 * or accessibility services.
 * 
 * The engine only dispatches APP_AUTOMATION after a HUMAN_APPROVAL gate
 * has been COMPLETED (contentHash bound).  This executor must not start
 * any automation until the gate is done.
 * 
 * On completion it returns Attempt.Completed(outputJson) or
 * Attempt.Failed(reason) to the engine.
 */
class AppAutomationExecutor(
    private val engine: WorkflowEngine,
    private val onStepDone: (Attempt<Unit>) -> Unit,
) {

    /** called by the engine when it's time to execute this step. */
    fun execute(spec: StepSpec) {
        // Safety: the engine guarantees the preceding gate is COMPLETED.
        // If somehow we get here without a gate, fail fast.
        val hasPrecedingGate = engine.hasPrecedingGate(spec.id)
        require(hasPrecedingGate) {
            "APP_AUTOMATION step '${spec.id}' must follow a HUMAN_APPROVAL gate"
        }

        try {
            // In a real implementation we would use UiAutomation or
            // accessibility services to drive the target app.
            // For now, simulate a successful run.
            val outputJson = """{"kind":"automation","step":"${spec.id}","status":"completed"}"""
            onStepDone(Attempt.Completed(outputJson))
        } catch (e: Exception) {
            Log.w("AppAutomationExecutor", "automation failed", e)
            onStepDone(Attempt.Failed("automation error: ${e.message}"))
        }
    }
}