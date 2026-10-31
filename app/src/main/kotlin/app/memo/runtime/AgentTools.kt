package app.memo.runtime

import app.memo.core.agent.AgentReply
import app.memo.core.agent.AgentResult
import app.memo.core.agent.AgentStop
import app.memo.core.agent.ToolRunner
import app.memo.core.engine.Attempt
import app.memo.core.plan.PlanModel
import app.memo.core.prompts.PacketBuilder
import app.memo.core.registry.Registry
import app.memo.core.registry.Workspace
import org.json.JSONObject

/**
 * Read-only tools for the local agent (M3.A4c). Side-effecting tools never reach this
 * runner: [app.memo.core.agent.AgentEngine] queues them for the workflow engine.
 *
 * Known limitation: the workspace is owned by the runtime thread, and this runner reads it
 * from the planner thread through [workspace] without locking. A read can therefore see a
 * registry mid-update. Results are advisory context; this runner never writes.
 *
 * `ask_user` only records the question in its observation. The user-facing question queue is
 * not built yet, so nothing waits for an answer.
 *
 * `browse_capture` is listed in the catalog but the in-app browser arrives in M4.
 */
class WorkspaceToolRunner(
    private val workspace: () -> Workspace,
    private val model: PlanModel?,
) : ToolRunner {

    override fun run(call: AgentReply.Call): String = when (call.tool.name) {
        "search_workspace" -> search(call.args.getValue("query") as String, call.args["kind"] as? String)
        "get_record" -> getRecord(call.args.getValue("kind") as String, call.args.getValue("key") as String)
        "draft_text" -> complete(
            DRAFT_SYSTEM,
            withLimit(call.args.getValue("instruction") as String, call.args["max_words"] as? Long),
        )
        "summarize" -> complete(
            SUMMARY_SYSTEM,
            withLimit(call.args.getValue("text") as String, call.args["max_words"] as? Long),
        )
        "extract_json" -> complete(
            EXTRACT_SYSTEM,
            "Fields: ${(call.args.getValue("fields") as List<*>).joinToString(", ")}\n\n" +
                call.args.getValue("text") as String,
        )
        "ask_user" -> "Question noted for the user: ${call.args.getValue("question")}"
        "browse_capture" -> BROWSER_UNAVAILABLE
        else -> throw IllegalArgumentException("tool '${call.tool.name}' is not a read-only tool")
    }

    private fun search(query: String, kind: String?): String {
        val hits = PacketBuilder.rank(query, workspace())
            .filter { kind == null || it.kind == kind }
            .take(MAX_HITS)
        if (hits.isEmpty()) return NO_MATCHES
        return hits.joinToString("\n") { "${it.kind} | ${it.label} | ${it.key}" }
    }

    private fun getRecord(kind: String, key: String): String {
        val ws = workspace()
        val registry = ws.all().firstOrNull { it.kind == kind } ?: return notFound(kind, key)
        return recordJson(registry, key, ws)?.toString() ?: notFound(kind, key)
    }

    /**
     * The row whose natural key equals [key]. Failing that, the best name match of that kind
     * (mention or whole-phrase only; keyword overlap is too loose to name one record).
     */
    private fun <T> recordJson(registry: Registry<T>, key: String, ws: Workspace): JSONObject? {
        val wanted = key.trim().lowercase()
        fun byKey(k: String): T? = registry.items().firstOrNull { registry.scheme.id(it).trim().lowercase() == k }
        val item = byKey(wanted) ?: PacketBuilder.rank(key, ws)
            .firstOrNull { it.kind == registry.kind && it.match != PacketBuilder.Match.KEYWORD }
            ?.let { byKey(it.key) }
        return item?.let { registry.scheme.toJson(it) }
    }

    private fun complete(system: String, user: String): String {
        val planModel = model ?: return NO_MODEL
        return planModel.complete(system, user).trim()
    }

    private fun withLimit(text: String, maxWords: Long?): String =
        if (maxWords == null) text else "$text\n\nLimit: about $maxWords words."

    private fun notFound(kind: String, key: String): String = "No record $kind/$key."

    companion object {
        const val NO_MATCHES = "No matching records."
        const val NO_MODEL = "No local model is available."
        const val BROWSER_UNAVAILABLE = "The in-app browser is not available yet (M4)."
        private const val MAX_HITS = 8

        private const val DRAFT_SYSTEM = "Write the text the instruction asks for. Reply with the text only."
        private const val SUMMARY_SYSTEM = "Summarize the text. Reply with the summary only."
        private const val EXTRACT_SYSTEM =
            "Extract the named fields from the text. Reply with one JSON object only, using exactly those field names."

        /**
         * Maps a finished agent run to the workflow step outcome. FINAL completes with the
         * answer. Any other stop completes with an honest note when the agent took at least one
         * step, and fails when it never got going. Queued side effects are reported, never run.
         */
        fun summarize(result: AgentResult): Attempt {
            val queuedLine = if (result.queued.isEmpty()) {
                ""
            } else {
                "\nNeeds approval (not executed): " + result.queued.joinToString(", ") { it.call.tool.name }
            }
            return when {
                result.stop == AgentStop.FINAL -> Attempt.Completed(output = result.answer.orEmpty() + queuedLine)
                result.steps.isEmpty() -> Attempt.Failed(result.stop.name)
                else -> {
                    val last = result.steps.mapNotNull { it.observation }.lastOrNull() ?: "no answer"
                    Attempt.Completed(output = "Agent stopped (${result.stop.name}): $last$queuedLine")
                }
            }
        }
    }
}
