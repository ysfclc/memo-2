package app.memo

import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelRole
import org.json.JSONObject

object ModelManager {
    private val byId = ModelCatalog.entries.associateBy { it.id }

    val isModelAvailable: Boolean
        get() = byId.values.any { it.isRunnableSize }

    fun loadRunnableModel(): Result<ModelRole> {
        val runnable = byId.values.filter { it.isRunnableSize }
        if (runnable.isEmpty()) return Result.failure(IllegalStateException("no runnable model on device"))
        return Result.success(runnable.minBy { it.sizeBytes }.role)
    }

    fun promptOrchestrator(role: ModelRole, userGoal: String, outputCap: Int): String =
        buildString {
            appendLine(app.memo.core.prompts.Prompts.orchestratorSystem())
            appendLine("ROLE: $role")
            appendLine("USER GOAL: ${userGoal.take(4000)}")
            appendLine("OUTPUT CAP: $outputCap characters")
            append("PRODUCE EXACTLY ONE JSON PLAN — no prose, no code fences.")
        }

    fun validatePlanOutput(output: String): String? = runCatching {
        val json = JSONObject(output.trim())
        require(json.has("title") && json.has("steps"))
        json.toString()
    }.getOrNull()
}
