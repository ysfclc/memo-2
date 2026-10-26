package app.memo.core.plan

import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.WorkflowSpec

/**
 * Policy law over compiled steps: side-effect discipline (every dangerous
 * step carries a preceding human gate), branch-reference integrity, and a
 * full re-check entry point for user-authored specs (which round-trips
 * through the strict parser — one contract, no drift).
 */
object WorkflowPolicy {

    /** Checks that exist only AFTER all steps are known (cross-step rules). */
    fun validateSideEffects(steps: List<StepSpec>): List<String> {
        val violations = mutableListOf<String>()
        steps.forEachIndexed { index, step ->
            when {
                step.kind == StepKind.BROWSER_AUTOMATION && !step.isBrowserCapture -> {
                    if (!step.requiresGate) {
                        violations += "browser submit step '${step.id}' requires a gate"
                    }
                    requirePrecedingGate(steps, index, step.id, violations)
                }
                step.kind == StepKind.APP_AUTOMATION -> {
                    if (!step.requiresGate) {
                        violations += "app automation step '${step.id}' requires a gate"
                    }
                    requirePrecedingGate(steps, index, step.id, violations)
                }
                step.kind == StepKind.BRANCH -> {
                    step.branches.forEach { branch ->
                        if (steps.none { it.id == branch.whenStep }) {
                            violations += "branch in '${step.id}' references unknown step '${branch.whenStep}'"
                        }
                        if (steps.none { it.id == branch.goto }) {
                            violations += "branch in '${step.id}' jumps to unknown step '${branch.goto}'"
                        }
                    }
                }
            }
        }
        return violations
    }

    private fun requirePrecedingGate(
        steps: List<StepSpec>,
        sideEffectIndex: Int,
        sideEffectId: String,
        violations: MutableList<String>,
    ) {
        val gateExists = steps.subList(0, sideEffectIndex)
            .any { it.kind == StepKind.HUMAN_APPROVAL }
        if (!gateExists) {
            violations += "side-effect step '$sideEffectId' has no preceding HUMAN_APPROVAL gate"
        }
    }

    /**
     * Full validation for specs built by user code (not the parser):
     * round-trips through the strict parser so both paths share one law.
     * Only the parser's input contract (title + steps) is fed back — the
     * compiled-spec envelope (id, createdAt, schemaVersion) is bookkeeping
     * the parser would rightly reject as unknown top-level fields.
     */
    fun validateSpec(spec: WorkflowSpec): List<String> {
        val stepsArr = org.json.JSONArray()
        spec.steps.forEach { stepsArr.put(it.toJson()) }
        val projection = org.json.JSONObject()
            .put("title", spec.title)
            .put("steps", stepsArr)
        val outcome = PlanProposalParser.parse(projection.toString(), spec.createdAt)
        return if (outcome.ok) emptyList() else outcome.violations
    }
}
