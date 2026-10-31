package app.memo.core.agent

import app.memo.core.plan.PlanModel
import app.memo.core.prompts.ContextPacket
import org.json.JSONObject

/** Runs a READ-ONLY tool and returns its observation text. Side-effecting tools never reach this. */
fun interface ToolRunner {
    fun run(call: AgentReply.Call): String
}

data class AgentBudget(
    val maxToolCalls: Int = 12,
    val maxMillis: Long = 120_000,
    val maxObservationChars: Int = 2_000,
    val maxRepairs: Int = 2,
) {
    init {
        require(maxToolCalls >= 0) { "maxToolCalls must not be negative" }
        require(maxMillis >= 0) { "maxMillis must not be negative" }
        require(maxObservationChars > 0) { "maxObservationChars must be positive" }
        require(maxRepairs >= 0) { "maxRepairs must not be negative" }
    }
}

enum class AgentStop { FINAL, CALL_BUDGET, TIME_BUDGET, NO_PROGRESS, INVALID_OUTPUT, MODEL_ERROR }

/** A side-effecting call the agent wanted: it becomes a gated workflow step, never executed by the loop. */
data class QueuedSideEffect(val call: AgentReply.Call)

/** One model round. [observation] is null when the reply was not executed (invalid, final, or a stop). */
data class AgentStepRecord(val modelOutput: String, val reply: AgentReply, val observation: String?)

data class AgentResult(
    val stop: AgentStop,
    val answer: String?,
    val steps: List<AgentStepRecord>,
    val queued: List<QueuedSideEffect>,
    val escalate: Boolean,
)

/**
 * Local tool-using agent loop (M3.A4, pure core). The model replies with one
 * JSON tool call or a final answer; calls are schema-checked by
 * [ToolCallParser], read-only tools run through [tools], side-effecting tools
 * are only queued for the workflow engine. Bounded by [budget].
 */
class AgentEngine(
    private val model: PlanModel,
    private val tools: ToolRunner,
    private val clock: () -> Long,
    private val budget: AgentBudget = AgentBudget(),
) {

    fun run(
        goal: String,
        packet: ContextPacket?,
        allowed: List<ToolSpec> = ToolCatalog.all,
        canEscalate: Boolean = false,
    ): AgentResult {
        val system = "$SYSTEM_PROMPT\n\n${ToolCatalog.schemaPrompt(allowed)}"
        val steps = mutableListOf<AgentStepRecord>()
        val queued = mutableListOf<QueuedSideEffect>()
        val transcript = mutableListOf<String>()
        val seenCalls = HashMap<Pair<String, Map<String, Any>>, Int>()
        val start = clock()
        var toolCount = 0
        var consecutiveInvalid = 0
        var repairLine: String? = null

        fun finish(stop: AgentStop, answer: String? = null): AgentResult = AgentResult(
            stop = stop,
            answer = answer,
            steps = steps,
            queued = queued,
            escalate = canEscalate && stop != AgentStop.FINAL,
        )

        while (true) {
            if (clock() - start > budget.maxMillis) return finish(AgentStop.TIME_BUDGET)

            val user = buildUserPrompt(goal, packet, transcript, repairLine)
            repairLine = null
            val output = try {
                model.complete(system, user)
            } catch (e: Exception) {
                return finish(AgentStop.MODEL_ERROR)
            }

            when (val reply = ToolCallParser.parse(output, allowed)) {
                is AgentReply.Invalid -> {
                    steps += AgentStepRecord(output, reply, observation = null)
                    consecutiveInvalid++
                    if (consecutiveInvalid > budget.maxRepairs) return finish(AgentStop.INVALID_OUTPUT)
                    repairLine = "Your last reply was rejected for: " +
                        "${reply.violations.joinToString("; ")}. Reply with ONE valid JSON object."
                }

                is AgentReply.Final -> {
                    steps += AgentStepRecord(output, reply, observation = null)
                    return finish(AgentStop.FINAL, reply.answer)
                }

                is AgentReply.Call -> {
                    consecutiveInvalid = 0
                    if (toolCount >= budget.maxToolCalls) {
                        steps += AgentStepRecord(output, reply, observation = null)
                        return finish(AgentStop.CALL_BUDGET)
                    }
                    val signature = reply.tool.name to reply.args
                    val priorCount = seenCalls.getOrDefault(signature, 0)
                    if (priorCount >= MAX_IDENTICAL_CALLS) {
                        steps += AgentStepRecord(output, reply, observation = null)
                        return finish(AgentStop.NO_PROGRESS)
                    }
                    seenCalls[signature] = priorCount + 1
                    toolCount++

                    val observation = if (reply.tool.sideEffecting) {
                        queued += QueuedSideEffect(reply)
                        QUEUED_OBSERVATION
                    } else {
                        truncate(runTool(reply), budget.maxObservationChars)
                    }
                    steps += AgentStepRecord(output, reply, observation)
                    transcript += "Called ${reply.tool.name} with ${JSONObject(reply.args)}\nObservation: $observation"
                }
            }
        }
    }

    private fun runTool(call: AgentReply.Call): String = try {
        tools.run(call)
    } catch (e: Exception) {
        "Tool failed: ${e.message ?: "unknown error"}"
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length > max) text.take(max) + "…" else text

    private fun buildUserPrompt(
        goal: String,
        packet: ContextPacket?,
        transcript: List<String>,
        repairLine: String?,
    ): String = buildString {
        appendLine("Goal: $goal")
        if (packet != null && !packet.isEmpty()) {
            appendLine("Context:")
            appendLine(packet.render())
        }
        transcript.forEach { appendLine(it) }
        if (repairLine != null) appendLine(repairLine)
    }.trimEnd()

    companion object {
        const val SYSTEM_PROMPT: String =
            "You are Memo's local agent on the user's phone. Use tools only when you need information " +
                "or an action; answer directly when you can. Never invent records, people or numbers."

        const val QUEUED_OBSERVATION: String = "Queued for the user's approval; not executed."

        private const val MAX_IDENTICAL_CALLS = 2
    }
}
