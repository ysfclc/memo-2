package app.memo.ui.chat

import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.plan.PlanSource
import app.memo.ui.components.StepRailItem
import app.memo.ui.components.stepVisual

/** A button the inline plan card can show for the current run state. */
enum class PlanAction { RUN, DISCARD, APPROVE, VETO, STOP }

/** The gate a person must decide on: the filled artifact and its content hash. */
data class PlanGate(
    val stepId: String,
    val artifactType: String,
    val artifactJson: String,
    val artifactHash: String,
)

/** Everything the inline plan card shows, derived purely from a workflow run. */
data class PlanCardState(
    val runId: String,
    val title: String,
    val sourceLabel: String?,
    val steps: List<StepRailItem>,
    val actions: Set<PlanAction>,
    val gate: PlanGate?,
    val statusLine: String,
)

/** Pure mapping from a workflow run to the plan card. Never throws for any run shape. */
object PlanCards {

    private const val REASON_MAX = 120

    fun from(run: AgentRun, source: PlanSource? = null): PlanCardState {
        val steps = run.spec.steps.map { spec ->
            val state = run.stepById(spec.id)
            StepRailItem(
                title = spec.title.ifBlank { spec.id },
                visual = stepVisual(state?.status ?: StepStatus.PENDING, run.status),
            )
        }
        val waiting = run.steps.firstOrNull { it.status == StepStatus.WAITING_USER }
        val gate = waiting?.gateOrNull()
        val (actions, statusLine) = when (run.status) {
            RunStatus.PENDING -> setOf(PlanAction.RUN, PlanAction.DISCARD) to "Ready to run"
            RunStatus.RUNNING -> setOf(PlanAction.STOP) to "Running"
            RunStatus.WAITING_USER -> when {
                gate != null -> setOf(PlanAction.APPROVE, PlanAction.VETO, PlanAction.STOP) to
                    "Waiting for your approval"
                else -> setOf(PlanAction.STOP) to "Waiting for you"
            }
            RunStatus.NEEDS_REVIEW -> setOf(PlanAction.DISCARD) to withReason("Needs your review", run.failure)
            RunStatus.INTERRUPTED -> setOf(PlanAction.DISCARD) to withReason("Interrupted", run.failure)
            RunStatus.COMPLETED -> emptySet<PlanAction>() to "Done"
            RunStatus.FAILED -> setOf(PlanAction.DISCARD) to withReason("Failed", run.failure)
            RunStatus.CANCELLED -> emptySet<PlanAction>() to "Stopped"
        }
        return PlanCardState(
            runId = run.id,
            title = run.spec.title.ifBlank { "Untitled plan" },
            sourceLabel = sourceLabel(source),
            steps = steps,
            actions = actions,
            gate = gate,
            statusLine = statusLine,
        )
    }

    /** The gate for a waiting HUMAN_APPROVAL step that has a filled artifact; null otherwise. */
    private fun StepState.gateOrNull(): PlanGate? {
        if (spec.kind != StepKind.HUMAN_APPROVAL || artifact.isBlank()) return null
        return PlanGate(
            stepId = spec.id,
            artifactType = spec.artifactType,
            artifactJson = artifact,
            artifactHash = artifactContentHash,
        )
    }

    private fun withReason(label: String, failure: String): String {
        val reason = failure.trim()
        if (reason.isEmpty()) return label
        val shown = if (reason.length > REASON_MAX) reason.take(REASON_MAX - 1) + "…" else reason
        return "$label: $shown"
    }

    private fun sourceLabel(source: PlanSource?): String? = when (source) {
        PlanSource.TEMPLATE_REUSE -> "From a saved template"
        PlanSource.MODEL_REPAIRED -> "Repaired plan"
        PlanSource.FALLBACK -> "Fallback: could not plan"
        PlanSource.MODEL, null -> null
    }
}
