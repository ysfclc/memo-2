package app.memo

import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelRole
import app.memo.core.Limits
import app.memo.core.domain.StepKind
import kotlin.Result

/**
 * Model manager for the on-device litertlm-android models.
 * 
 * Only the 3 pinned models from ModelCatalog may be loaded.
 * Their sizes, SHA-256 pins and URL revisions are immutable — any change
 * requires a new catalog entry and audit trail.
 * 
 * The orchestrator prompt (Prompts.orchestratorSystem()) always expects
 * exactly ONE JSON plan output; the app enforces this by discarding any
 * non‑JSON model output and re‑prompting.
 */
object ModelManager {

    /** The three models that the on‑device AI may ever trust. */
    private val byId = ModelCatalog.entries.associateBy { it.id }

    /** Returns true if a model on device meets the minimum byte budget. */
    val isModelAvailable: Boolean
        get() = byId.values.any { it.isRunnableSize }

    /** Load the smallest runnable model and return it, or null. */
    fun loadRunnableModel(): Result<ModelRole, String> {
        val runnable = byId.values.filter { it.isRunnableSize }
        if (runnable.isEmpty()) return Result.failure("no runnable model on device")

        // Pick the smallest runnable model (lowest byte count).
        val chosen = runnable.smallestBy { it.sizeBytes }
        return Result.success(chosen.role)
    }

    /** Prompt the orchestrator with the chosen model role.
     *  The app must enforce that the model outputs exactly ONE JSON plan.
     */
    fun promptOrchestrator(role: ModelRole, userGoal: String, outputCap: Int): String {
        Prompts.orchestratorSystem() + "\n\n" +
            "ROLE: $role\n" +
            "USER GOAL: $userGoal\n" +
            "OUTPUT CAP: $outputCap characters\n" +
            " PRODUCE EXACTLY ONE JSON PLAN — no prose, no code fences."
    }

    /** Verify that the model's output is valid JSON plan format.
     *  Returns the parsed plan or null if the output is not a valid plan.
     */
    fun validatePlanOutput(output: String): String? {
        try {
            val json = JSONObject(output)
            // Must have title and steps at minimum per parser contract.
            json.has("title") && json.has("steps") let {
                json
            }
        } catch (_: Exception) {
            null
        }
    }
}