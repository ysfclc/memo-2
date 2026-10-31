package app.memo.runtime

import app.memo.core.domain.WorkflowSpec
import app.memo.core.domain.WorkflowTemplate
import app.memo.core.plan.Orchestration
import app.memo.core.plan.Orchestrator
import app.memo.core.plan.PlanModel
import app.memo.core.plan.PlanSource
import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.ModelRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapts the chat model to the orchestrator's blocking [PlanModel] port (M3.A1c). The request
 * goes through the same [InferenceManager] queue as chat, so it never runs beside another
 * generation. Lambdas keep this class free of Android so it runs on the JVM.
 *
 * Must not be called on the main thread: [complete] blocks until the model finishes.
 */
class InferencePlanModel(
    private val submit: (ModelRef, List<ChatTurn>, GenParams, ChatSink) -> Long,
    private val cancel: (Long) -> Unit,
    private val model: ModelRef,
    private val timeoutMs: Long = 90_000,
) : PlanModel {

    override fun complete(system: String, user: String): String {
        val output = StringBuilder()
        val failure = AtomicReference<LlmError?>(null)
        val done = CountDownLatch(1)
        val sink = object : ChatSink {
            override fun onToken(text: String) {
                output.append(text)
            }

            override fun onThinking(text: String) = Unit

            override fun onComplete(stats: GenStats) {
                done.countDown()
            }

            override fun onError(e: LlmError) {
                failure.set(e)
                done.countDown()
            }
        }
        val turns = listOf(ChatTurn(ChatTurn.Role.SYSTEM, system), ChatTurn(ChatTurn.Role.USER, user))
        val requestId = submit(model, turns, PLAN_PARAMS, sink)
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            cancel(requestId)
            throw IllegalStateException("planning timed out")
        }
        failure.get()?.let { throw IllegalStateException("planning failed: ${describe(it)}") }
        return output.toString()
    }

    private fun describe(error: LlmError): String = when (error) {
        LlmError.OutOfMemory -> "out of memory"
        LlmError.Cancelled -> "cancelled"
        is LlmError.ModelLoad -> "model could not be loaded"
        is LlmError.Unsupported -> error.msg
        is LlmError.Other -> error.msg
    }

    private companion object {
        /** Plans are short JSON: low temperature, no reasoning channel, bounded output. */
        val PLAN_PARAMS = GenParams(
            temperature = 0.2,
            topK = 40,
            topP = 0.9,
            maxOutputTokens = 1200,
            thinking = false,
            stop = emptyList(),
        )
    }
}

/**
 * A planner outcome for the chat. [runId] is set only when a plan was created as a proposal:
 * the SYSTEM message then links to that run, and the user runs it from the plan card.
 */
data class PlanReply(val text: String, val runId: String?)

/**
 * Plan requests typed or spoken into chat (M3.A1c). [plan] runs the orchestrator: a command
 * goes to the deterministic command path, a plan is created as a proposal through [propose]
 * (never started here), and every outcome becomes a reply the chat shows as a SYSTEM message.
 * Blocking; the caller runs it off the main thread.
 */
class PlannerService(
    private val orchestratorFor: (ModelRef) -> Orchestrator,
    private val resolveModel: (String?) -> ModelRef?,
    private val templates: () -> List<WorkflowTemplate>,
    private val propose: (WorkflowSpec) -> String,
    private val runCommand: (String) -> String,
) {

    fun plan(text: String, modelId: String?): PlanReply {
        val model = resolveModel(modelId) ?: return PlanReply("Install a chat model in Models to plan.", null)
        return when (val outcome = orchestratorFor(model).orchestrate(text, null, templates())) {
            is Orchestration.Command -> PlanReply(runCommand(text), null)
            Orchestration.Chat -> PlanReply("That does not look like a plan request.", null)
            is Orchestration.Plan -> PlanReply(replyFor(outcome), propose(outcome.spec))
        }
    }

    private fun replyFor(plan: Orchestration.Plan): String {
        val title = plan.spec.title
        return when (plan.source) {
            PlanSource.MODEL -> "Planned \"$title\". Review the steps below, then tap Run."
            PlanSource.MODEL_REPAIRED -> "Planned \"$title\". Review the steps below, then tap Run. (repaired ${plan.repairs}x)"
            PlanSource.TEMPLATE_REUSE -> "Planned \"$title\" from a saved template. Review the steps below, then tap Run."
            PlanSource.FALLBACK ->
                "Could not make a valid plan, so Memo made a one-step note instead: \"$title\"."
        }
    }
}
