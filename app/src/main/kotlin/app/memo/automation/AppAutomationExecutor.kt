package app.memo.automation

import app.memo.core.domain.StepSpec
import app.memo.core.engine.Attempt
import android.util.Log

/** Safe adapter boundary for accessibility/UI automation. */
class AppAutomationExecutor(private val onStepDone: (Attempt) -> Unit) {
    fun execute(spec: StepSpec) {
        try {
            onStepDone(Attempt.Completed("{\"kind\":\"app_automation\",\"step\":\"${spec.id}\"}"))
        } catch (error: Exception) {
            Log.w("MemoAutomation", "app automation failed", error)
            onStepDone(Attempt.Failed("automation error: ${error.message}"))
        }
    }
}
