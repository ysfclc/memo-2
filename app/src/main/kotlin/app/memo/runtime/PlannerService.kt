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
 * Plan requests typed or spoken into chat (M3.A1c). [plan] runs the orchestrator: a command
 * goes to the deterministic command path, a plan starts as a workflow run, and every outcome
 * becomes a reply the chat shows as a SYSTEM message. Blocking; the caller runs it off the
 * main thread.
 */
class PlannerService(
    private val orchestratorFor: (ModelRef) -> Orchestrator,
    private val resolveModel: (String?) -> ModelRef?,
    private val templates: () -> List<WorkflowTemplate>,
    private val startRun: (WorkflowSpec) -> Unit,
    private val runCommand: (String) -> String,
) {

    fun plan(text: String, modelId: String?): String {
        val model = resolveModel(modelId) ?: return "Install a chat model in Models to plan."
        return when (val outcome = orchestratorFor(model).orchestrate(text, null, templates())) {
            is Orchestration.Command -> runCommand(text)
            Orchestration.Chat -> "That does not look like a plan request."
            is Orchestration.Plan -> {
                startRun(outcome.spec)
                replyFor(outcome)
            }
        }
    }

    private fun replyFor(plan: Orchestration.Plan): String {
        val title = plan.spec.title
        return when (plan.source) {
            PlanSource.MODEL -> "Planned \"$title\". Review it in Live."
            PlanSource.MODEL_REPAIRED -> "Planned \"$title\". Review it in Live. (repaired ${plan.repairs}x)"
            PlanSource.TEMPLATE_REUSE -> "Planned \"$title\" from a saved template. Review it in Live."
            PlanSource.FALLBACK ->
                "Could not make a valid plan, so Memo made a one-step note instead: \"$title\". Review it in Live."
        }
    }
}
