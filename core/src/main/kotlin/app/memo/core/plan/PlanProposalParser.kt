package app.memo.core.plan

import app.memo.core.Limits
import app.memo.core.catalog.ProviderRegistry
import app.memo.core.domain.BranchOutcome
import app.memo.core.domain.BranchSpec
import app.memo.core.domain.RetrySpec
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.WorkflowSpec
import app.memo.core.prompts.Prompts.ARTIFACT_KINDS
import app.memo.core.util.Sha256Hex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict compiler for model-proposed plans (product law: plans are parsed,
 * validated and capped — never trusted). Input is raw orchestrator text;
 * the parser locates the first JSON object, tolerates NO unknown step
 * fields, enforces per-kind requirements, disallows gate-free side effects,
 * and folds RETRY declarations onto their targets. If anything is off, the
 * whole plan is rejected with the exact violation list.
 */
data class PlanParseResult(val spec: WorkflowSpec?, val violations: List<String>) {
    val ok: Boolean get() = spec != null && violations.isEmpty()
}

object PlanProposalParser {

    private val KIND_NAMES: Map<String, StepKind> =
        StepKind.entries.associateBy { it.name.lowercase() }

    private val KNOWN_STEP_KEYS = setOf(
        "id", "kind", "title", "prompt", "provider", "appPackage", "artifactType",
        "artifactHint", "metricKey", "metricUrl", "requiresGate", "target",
        "retry", "branches",
    )

    private val STEP_ID_SHAPE = Regex("^[a-z][a-z0-9_]{0,23}$")

    fun parse(text: String, now: Long): PlanParseResult {
        val violations = mutableListOf<String>()
        val located = Parsing.locateFirstJsonObject(text)
        if (located == null) {
            return PlanParseResult(null, listOf("no JSON plan object found in the output"))
        }
        val root = runCatching { JSONObject(located) }.fold(
            onSuccess = { it },
            onFailure = { return PlanParseResult(null, listOf("invalid JSON: ${it.message}")) },
        )

        val unknownRoot = root.keySet().filterNot { it in setOf("title", "steps") }
        if (unknownRoot.isNotEmpty()) {
            violations += "unknown top-level field(s): ${unknownRoot.sorted().joinToString(", ")}"
        }

        val title = root.optString("title")
        if (title.isBlank()) violations += "title is required"
        if (title.length > Limits.MAX_TITLE_CHARS) violations += "title exceeds ${Limits.MAX_TITLE_CHARS} chars"

        val stepsRaw = root.optJSONArray("steps")
        if (stepsRaw == null || stepsRaw.length() == 0) {
            violations += "steps must be a non-empty array"
            return PlanParseResult(null, violations)
        }
        if (stepsRaw.length() > Limits.MAX_STEPS_PER_RUN) {
            violations += "plans may not exceed ${Limits.MAX_STEPS_PER_RUN} steps"
            return PlanParseResult(null, violations)
        }

        val parsed = mutableListOf<StepSpec>()
        val seen = mutableSetOf<String>()
        for (i in 0 until stepsRaw.length()) {
            val raw = stepsRaw.optJSONObject(i)
            if (raw == null) {
                violations += "steps[$i] is not an object"
                continue
            }
            parseStep(raw, i, parsed, seen, violations)
        }

        if (violations.isEmpty()) {
            violations += WorkflowPolicy.validateSideEffects(parsed)
        }
        if (violations.isEmpty()) {
            val folded = foldRetries(parsed, violations)
            if (folded != null && violations.isEmpty()) {
                val id = "plan-" + Sha256Hex.hex("$title|$now").take(12)
                return PlanParseResult(
                    spec = WorkflowSpec(id = id, title = title, createdAt = now, steps = folded),
                    violations = violations,
                )
            }
        }
        return PlanParseResult(null, violations)
    }

    private fun parseStep(
        raw: JSONObject,
        index: Int,
        parsed: MutableList<StepSpec>,
        seen: MutableSet<String>,
        violations: MutableList<String>,
    ) {
        val unknownFields = raw.keySet().filterNot { it in KNOWN_STEP_KEYS }
        if (unknownFields.isNotEmpty()) {
            violations += "steps[$index]: unknown field(s) ${unknownFields.sorted().joinToString(", ")}"
            return
        }
        val id = raw.optString("id")
        if (id.isBlank()) {
            violations += "steps[$index]: id is required"
            return
        }
        if (!STEP_ID_SHAPE.matches(id)) {
            violations += "steps[$index]: id '$id' must match lowercase [a-z][a-z0-9_]{0,23}"
            return
        }
        if (!seen.add(id)) {
            violations += "steps[$index]: duplicate id '$id'"
            return
        }
        val kindText = raw.optString("kind").lowercase()
        val kind = KIND_NAMES[kindText]
        if (kind == null) {
            violations += "steps[$index]: unknown kind '$kindText'"
            return
        }
        val title = raw.optString("title")
        if (title.length > Limits.MAX_STEP_TITLE_CHARS) {
            violations += "steps[$index]: title too long"
            return
        }

        var retrySpec: RetrySpec? = raw.optJSONObject("retry")?.let { RetrySpec.fromJson(it) }
        if (kind == StepKind.RETRY && retrySpec == null) {
            // Declarative form: {"kind":"retry","target":"x","attemptsMax":N}.
            // The declared cap is honored, never silently replaced by defaults.
            val declared = raw.optInt("attemptsMax", RetrySpec().attemptsMax)
            if (declared !in Limits.MIN_STEP_ATTEMPTS..Limits.MAX_STEP_ATTEMPTS) {
                violations += "steps[$index]: attemptsMax must be in " +
                    "${Limits.MIN_STEP_ATTEMPTS}..${Limits.MAX_STEP_ATTEMPTS}"
            } else {
                retrySpec = RetrySpec(attemptsMax = declared)
            }
        }
        val branches = raw.optJSONArray("branches")?.let { arr ->
            buildList {
                for (b in 0 until arr.length()) {
                    val obj = arr.optJSONObject(b) ?: continue
                    BranchSpec.fromJson(obj)?.let { add(it) }
                }
            }
        } ?: emptyList()

        val spec = StepSpec(
            id = id,
            kind = kind,
            title = title,
            prompt = normalizePrompt(raw.optString("prompt")),
            provider = raw.optString("provider").trim(),
            appPackage = raw.optString("appPackage").trim(),
            artifactType = raw.optString("artifactType").trim(),
            artifactHint = raw.optString("artifactHint").trim(),
            metricKey = raw.optString("metricKey").trim(),
            metricUrl = raw.optString("metricUrl").trim(),
            requiresGate = raw.optBoolean("requiresGate", false),
            target = raw.optString("target").trim(),
            retry = if (kind == StepKind.RETRY) retrySpec else null,
            branches = branches,
        )

        when (kind) {
            StepKind.REASON -> {
                if (spec.prompt.isBlank()) violations += "steps[$index]: REASON requires a prompt"
                if (spec.prompt.length > Limits.MAX_STEP_PROMPT_CHARS) {
                    violations += "steps[$index]: prompt exceeds ${Limits.MAX_STEP_PROMPT_CHARS} chars"
                }
            }
            StepKind.BROWSER_AUTOMATION -> {
                val provider = ProviderRegistry.byId(spec.provider)
                if (provider == null) {
                    violations += "steps[$index]: unknown provider '${spec.provider}' " +
                        "(known: ${ProviderRegistry.knownIds().sorted().joinToString(", ")})"
                }
                val isCapture = spec.artifactHint.startsWith("capture:")
                val isSend = spec.artifactHint.startsWith("send:")
                if (!isCapture && !isSend) {
                    violations += "steps[$index]: BROWSER_AUTOMATION needs artifactHint \"capture:...\" or \"send:...\""
                }
            }
            StepKind.APP_AUTOMATION -> {
                if (spec.appPackage.isBlank() ||
                    !Regex("^[a-z][a-z0-9_.]{2,119}$").matches(spec.appPackage)
                ) {
                    violations += "steps[$index]: APP_AUTOMATION needs a valid appPackage"
                }
            }
            StepKind.HUMAN_APPROVAL, StepKind.DATA_PERSIST -> {
                if (spec.artifactType !in ARTIFACT_KINDS) {
                    violations += "steps[$index]: artifactType '${spec.artifactType}' not in ${ARTIFACT_KINDS.sorted()}"
                }
            }
            StepKind.METRIC_COLLECT -> {
                if (spec.metricKey.isBlank()) {
                    violations += "steps[$index]: METRIC_COLLECT needs a metricKey"
                }
                val url = spec.metricUrl
                if (!url.startsWith("https://") || url.length > Limits.MAX_METRIC_TARGET_CHARS) {
                    violations += "steps[$index]: METRIC_COLLECT metricUrl must be an https URL"
                }
            }
            StepKind.BRANCH -> {
                if (branches.size !in 1..Limits.MAX_BRANCHES_PER_STEP) {
                    violations += "steps[$index]: BRANCH needs 1..${Limits.MAX_BRANCHES_PER_STEP} branches"
                }
                val duplicates = branches.groupBy { it.toJson().toString() }
                    .any { it.value.size > 1 }
                if (duplicates) violations += "steps[$index]: duplicate branch rows"
                if (branches.any { it.goto == id }) {
                    violations += "steps[$index]: branch cannot target itself"
                }
            }
            StepKind.RETRY -> {
                val target = spec.target
                if (target.isBlank()) {
                    violations += "steps[$index]: RETRY needs a target step id"
                }
                if (target == id) {
                    violations += "steps[$index]: RETRY cannot target itself"
                }
            }
        }
        parsed += spec
    }

    private fun normalizePrompt(promptIn: String): String {
        val prompt = promptIn.trim()
        return prompt.take(Limits.MAX_STEP_PROMPT_CHARS)
    }

    /** Fold RETRY declarations onto their targets; retry steps vanish from the compiled plan. */
    private fun foldRetries(
        steps: List<StepSpec>,
        violations: MutableList<String>,
    ): List<StepSpec>? {
        val retryDeclarations = steps.filter { it.kind == StepKind.RETRY }
        if (retryDeclarations.isEmpty()) return steps
        val result = steps.filter { it.kind != StepKind.RETRY }.toMutableList()
        for (declaration in retryDeclarations) {
            val index = result.indexOfFirst { it.id == declaration.target }
            if (index < 0) {
                violations += "RETRY '${declaration.id}' targets unknown step '${declaration.target}'"
                return null
            }
            val target = result[index]
            if (target.retry != null) {
                violations += "step '${target.id}' already has a retry policy"
                return null
            }
            result[index] = target.copy(
                retry = declaration.retry ?: RetrySpec(),
                branches = declaration.branches.ifEmpty { target.branches },
            )
        }
        return result
    }

    /** Helper for tests + UI: render a violation summary in one line. */
    fun summarize(violations: List<String>): String = violations.joinToString(" · ")

    /** Test helper: ids of branch references (used by policy unit tests). */
    fun branchReferences(spec: StepSpec): List<BranchSpec> = spec.branches

    /** Test helper: counts parsed steps in a raw steps array. */
    fun rawStepCount(steps: JSONArray): Int = steps.length()
}
