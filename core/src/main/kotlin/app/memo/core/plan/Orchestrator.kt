package app.memo.core.plan

import app.memo.core.Limits
import app.memo.core.domain.StepKind
import app.memo.core.domain.WorkflowSpec
import app.memo.core.domain.WorkflowTemplate
import app.memo.core.prompts.ContextPacket
import app.memo.core.prompts.Prompts
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * The blocking text-completion port the orchestrator needs; the app adapts
 * InferenceManager to it (M3.A1c).
 */
fun interface PlanModel {
    fun complete(system: String, user: String): String
}

/** Where a returned [Orchestration.Plan.spec] came from. */
enum class PlanSource { MODEL, MODEL_REPAIRED, TEMPLATE_REUSE, FALLBACK }

/**
 * What the user asked Memo to do: a deterministic command (fast path), a
 * plain chat reply (no plan), or a validated workflow plan.
 */
sealed interface Orchestration {
    data class Command(val command: CommandGrammar.VoiceCommand) : Orchestration
    data object Chat : Orchestration
    data class Plan(
        val spec: WorkflowSpec,
        val source: PlanSource,
        val repairs: Int,
        val notes: List<String>,
    ) : Orchestration
}

/**
 * Orchestrator planner (M3.A1, pure core). Decides intent → plan → route in
 * one call:
 *  1. blank text → Chat;
 *  2. CommandGrammar hit → Command (the model is NEVER called);
 *  3. plan-intent text → reuse-first template match, else the local model
 *     produces a plan JSON which is parsed and validated — never trusted;
 *  4. invalid model output is repaired at most twice; if the model still
 *     cannot produce a valid plan (or throws), a clearly LABELLED one-step
 *     REASON fallback plan is built through the same strict parser, so the
 *     returned spec always passes [WorkflowPolicy.validateSpec].
 */
class Orchestrator(
    private val model: PlanModel,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun orchestrate(text: String, packet: ContextPacket?, templates: List<WorkflowTemplate>): Orchestration {
        val input = text.trim()
        if (input.isEmpty()) return Orchestration.Chat

        // Rule 2: deterministic grammar first — zero model calls on a hit.
        CommandGrammar.parse(input, clock(), zone)?.let { return Orchestration.Command(it) }

        // Rule 3: plan intent?
        if (!isPlanIntent(input)) return Orchestration.Chat

        val intent = normalizeIntent(stripCues(input.lowercase()))

        // Rule 4: reuse first — a matching stored template replaces the model call.
        val reuseNotes = mutableListOf<String>()
        val skippedTemplates = mutableSetOf<WorkflowTemplate>()
        for (template in templates.filter { matchesExample(it, intent) }) {
            val result = validate(template.planJson)
            val spec = result.first
            val violations = result.second
            if (spec != null && violations.isEmpty()) {
                return Orchestration.Plan(
                    spec = spec,
                    source = PlanSource.TEMPLATE_REUSE,
                    repairs = 0,
                    notes = listOf("reused template ${template.name}"),
                )
            }
            skippedTemplates += template
            reuseNotes += note("template ${template.name} skipped: ${PlanProposalParser.summarize(violations)}")
        }

        // Templates skipped in reuse carry model-invalid plan JSON; they are
        // excluded from few-shot selection so they never teach bad output.
        val fewShotCandidates = templates.filter { it !in skippedTemplates }
        return runModel(input, intent, packet, fewShotCandidates, reuseNotes)
    }

    // ---- Model path (rules 5–8) -----------------------------------------

    private fun runModel(
        input: String,
        intent: String,
        packet: ContextPacket?,
        templates: List<WorkflowTemplate>,
        reuseNotes: List<String>,
    ): Orchestration.Plan {
        var userPrompt = buildUserPrompt(input, intent, packet, templates)
        var repairs = 0
        var previousOutput = ""
        val failedRounds = mutableListOf<String>()

        // 1 initial call + at most 2 repair calls.
        for (attempt in 0..MAX_REPAIRS) {
            val output = try {
                model.complete(Prompts.orchestratorSystem(), userPrompt)
            } catch (failure: Throwable) {
                return fallback(
                    repairs = repairs,
                    notes = reuseNotes + failedRounds + "model error: ${describe(failure)}",
                    blocker = describe(failure),
                )
            }

            val (spec, violations) = validate(output)
            if (spec != null && violations.isEmpty()) {
                val source = if (repairs == 0) PlanSource.MODEL else PlanSource.MODEL_REPAIRED
                return Orchestration.Plan(spec, source, repairs, reuseNotes + failedRounds)
            }

            failedRounds += note(PlanProposalParser.summarize(violations))
            if (attempt == MAX_REPAIRS) break
            previousOutput = output
            repairs++
            userPrompt = buildRepairPrompt(userPrompt, PlanProposalParser.summarize(violations), previousOutput)
        }

        return fallback(
            repairs = repairs,
            notes = reuseNotes + failedRounds,
            blocker = failedRounds.lastOrNull() ?: "no valid plan",
        )
    }

    /** parse + validateSpec, combined (the whole model contract in one check). */
    private fun validate(planJson: String): Pair<WorkflowSpec?, List<String>> {
        val parsed = PlanProposalParser.parse(planJson, clock())
        val policy = parsed.spec?.let { WorkflowPolicy.validateSpec(it) } ?: emptyList()
        return parsed.spec to (parsed.violations + policy)
    }

    private fun buildUserPrompt(
        text: String,
        intent: String,
        packet: ContextPacket?,
        templates: List<WorkflowTemplate>,
    ): String = buildString {
        for ((template, example) in fewShot(intent, templates)) {
            append("Example intent: ").append(example).append('\n')
            append("Example plan: ").append(template.planJson).append("\n\n")
        }
        if (packet != null && !packet.isEmpty()) {
            append("Context:\n").append(packet.render()).append("\n\n")
        }
        append("Intent: ").append(text)
    }

    /** Up to 3 templates whose intentExamples share the most lowercase words with the intent. */
    private fun fewShot(
        intent: String,
        candidates: List<WorkflowTemplate>,
    ): List<Pair<WorkflowTemplate, String>> {
        val intentWords = intent.split(' ').filter { it.isNotBlank() }.toSet()
        if (intentWords.isEmpty()) return emptyList()
        return candidates
            .mapNotNull { template ->
                template.intentExamples
                    .map { it to sharedWords(intentWords, normalizeIntent(it)) }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second > 0 }
                    ?.let { (example, score) -> Triple(template, example, score) }
            }
            .sortedByDescending { (_, _, score) -> score }
            .take(FEW_SHOT_EXAMPLES)
            .map { (template, example, _) -> template to example }
    }

    private fun sharedWords(intentWords: Set<String>, example: String): Int =
        intentWords.intersect(example.split(' ').filter { it.isNotBlank() }.toSet()).size

    private fun buildRepairPrompt(original: String, summary: String, previous: String): String =
        original +
            "\n\nYour previous answer was rejected for: " + summary +
            ". Answer again with ONE corrected JSON plan object only.\n\n" +
            "Previous answer:\n" + previous.take(4000)

    // ---- Fallback (rule 8) ----------------------------------------------

    /**
     * A clearly LABELLED one-step plan assembled as OUR OWN JSON and piped
     * through the same strict parser — never hand-built specs — so the
     * fallback provably passes [WorkflowPolicy.validateSpec].
     */
    private fun fallback(repairs: Int, notes: List<String>, blocker: String): Orchestration.Plan {
        parseFallback("Could not plan this request: ${blocker.take(FALLBACK_BLOCKER_MAX_CHARS)}")
            ?.let { return Orchestration.Plan(it, PlanSource.FALLBACK, repairs, notes) }
        // The fallback JSON is fixed-shape and cannot violate the parser; this
        // second, generic attempt exists only so the contract ("the fallback is
        // always valid") holds even against future parser rule changes.
        val generic = parseFallback("Could not produce a valid plan.") ?: error("fallback plan rejected")
        return Orchestration.Plan(generic, PlanSource.FALLBACK, repairs, notes)
    }

    private fun parseFallback(blockerPrompt: String): WorkflowSpec? {
        val json = JSONObject()
            .put("title", FALLBACK_TITLE)
            .put(
                "steps",
                JSONArray().put(
                    JSONObject()
                        .put("id", "blocked")
                        .put("kind", StepKind.REASON.name)
                        .put("title", FALLBACK_TITLE)
                        .put("prompt", blockerPrompt.take(Limits.MAX_STEP_PROMPT_CHARS)),
                ),
            ).toString()
        val parsed = PlanProposalParser.parse(json, clock())
        if (!parsed.ok) return null
        val spec = parsed.spec ?: return null
        return spec.takeIf { WorkflowPolicy.validateSpec(it).isEmpty() }
    }

    // ---- Intent helpers -------------------------------------------------

    /**
     * Rule 4: reuse lookup key — trimmed, lowercase, whitespace-collapsed,
     * with the matched plan cue stripped. Prefix cues come before `/plan`
     * variants; the contained " step by step" cue is stripped wherever it sits.
     */
    private fun stripCues(lowered: String): String {
        val cue = PLAN_CUES.filter { lowered.startsWith(it) }.maxByOrNull { it.length }
        val withoutPrefix = if (cue != null) lowered.removePrefix(cue) else lowered
        return withoutPrefix.replace(CONTAINS_CUE, " ")
    }

    private fun matchesExample(template: WorkflowTemplate, intent: String): Boolean {
        if (intent.isEmpty()) return false
        val key = normalizeIntent(intent)
        return template.intentExamples.any { normalizeIntent(it) == key }
    }

    private fun normalizeIntent(text: String): String =
        text.trim().replace(WHITESPACE_RUNS, " ")

    private fun note(text: String): String = text.trim().take(NOTE_MAX_CHARS)

    /** Recursion-free exception description (message preferred). */
    private fun describe(failure: Throwable): String =
        (failure.message ?: failure::class.java.simpleName).trim().ifEmpty { "model error" }

    companion object {
        /** Plan-intent cue prefixes; extended by LANG work (pl/tr) later, one place to edit. */
        val PLAN_CUES: List<String> = listOf("plan ", "/plan ", "help me plan ", "make a plan")

        /** Contained plan cue. */
        const val CONTAINS_CUE: String = " step by step"

        /**
         * Rule 3: plan intent, detected on the lowercase text only via [PLAN_CUES].
         * Public so the chat controller can route plan requests before generation.
         */
        fun isPlanIntent(text: String): Boolean {
            val lowered = text.trim().lowercase()
            return PLAN_CUES.any { lowered.startsWith(it) } || lowered.contains(CONTAINS_CUE)
        }

        private const val MAX_REPAIRS = 2
        private const val FEW_SHOT_EXAMPLES = 3
        private const val NOTE_MAX_CHARS = Limits.MAX_ROUTING_NOTE_CHARS
        private const val FALLBACK_BLOCKER_MAX_CHARS = 600
        private const val FALLBACK_TITLE = "Could not plan this"
        private val WHITESPACE_RUNS = Regex("\\s+")
    }
}
