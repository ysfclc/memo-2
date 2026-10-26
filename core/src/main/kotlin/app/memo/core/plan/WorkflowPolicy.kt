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

    /**
     * Checks that exist only AFTER all steps are known (cross-step rules).
     *
     * Branch rule (D-023, documented): "a branch may not jump over a HUMAN_APPROVAL
     * step". A BRANCH row is rejected when a gate lies strictly between the branch
     * step and its goto target, in either direction. A watched step is rejected
     * when a gate lies strictly between it and its watching branch, because the
     * failure rescue jumps from the watched step to the branch. Gates at the
     * endpoints are allowed. This is deliberately simpler than a path analysis:
     * it can reject some safe plans, but it never admits a skipped gate.
     */
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
                        val watchedIndex = steps.indexOfFirst { it.id == branch.whenStep }
                        val targetIndex = steps.indexOfFirst { it.id == branch.goto }
                        if (watchedIndex < 0) {
                            violations += "branch in '${step.id}' references unknown step '${branch.whenStep}'"
                        }
                        if (targetIndex < 0) {
                            violations += "branch in '${step.id}' jumps to unknown step '${branch.goto}'"
                        }
                        // D-023: a branch jump, or a failure rescue (which jumps from the
                        // watched step to the branch), must not skip over a gate. A skipped
                        // gate would leave later side effects bound to an older approval.
                        if (targetIndex >= 0) {
                            requireNoGateBetween(
                                steps, index, targetIndex,
                                "branch '${step.id}' jumps to '${branch.goto}' over a HUMAN_APPROVAL gate",
                                violations,
                            )
                        }
                        if (watchedIndex in 0 until index) {
                            requireNoGateBetween(
                                steps, watchedIndex, index,
                                "branch '${step.id}' watches '${branch.whenStep}' across a HUMAN_APPROVAL gate " +
                                    "(a failure rescue would skip it)",
                                violations,
                            )
                        }
                    }
                }
            }
        }
        return violations
    }

    /**
     * Rejects any HUMAN_APPROVAL strictly between two step indices, in either
     * direction. Gates at the two endpoints are allowed.
     */
    private fun requireNoGateBetween(
        steps: List<StepSpec>,
        from: Int,
        to: Int,
        message: String,
        violations: MutableList<String>,
    ) {
        val low = minOf(from, to)
        val high = maxOf(from, to)
        val gateBetween = (low + 1 until high).any { steps[it].kind == StepKind.HUMAN_APPROVAL }
        if (gateBetween) violations += message
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
