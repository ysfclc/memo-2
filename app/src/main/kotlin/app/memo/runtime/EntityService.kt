package app.memo.runtime

import app.memo.core.agent.ApprovalSummary
import app.memo.core.agent.EntityExtraction
import app.memo.core.agent.ExtractionResult
import app.memo.core.codec.SynCodec
import app.memo.core.plan.PlanModel

/** Outcome of one extraction from a chat message (ENT.4b). */
sealed interface Extracted {
    /** No chat model is installed or active, so nothing was extracted. */
    object NoModel : Extracted

    /** The model answered; [result] holds candidates for review. Nothing is saved yet. */
    data class Ready(val result: ExtractionResult) : Extracted

    /** The model failed or the extraction threw. [message] is short and content-free. */
    data class Failed(val message: String) : Extracted
}

/**
 * Entity extraction from one chat message and the user's approval of it (ENT.4b, D-059).
 * [extract] is blocking and runs the local model, so the caller runs it off the main thread.
 * [approve] saves only the ticked selection, on the runtime thread that owns the workspace.
 * Model and runtime access are injected, so the service is plain JVM code.
 */
class EntityService(
    private val modelFor: () -> PlanModel?,
    private val applyOnRuntime: (ExtractionResult, Set<Int>, Set<Int>) -> ApprovalSummary,
    private val clock: () -> Long,
) {

    fun extract(text: String, messageId: String): Extracted {
        return try {
            val model = modelFor() ?: return Extracted.NoModel
            // EntityExtraction turns a throwing model into an empty result; record the throw so it surfaces as Failed.
            var modelFailure: Throwable? = null
            val guarded = PlanModel { system, user ->
                try {
                    model.complete(system, user)
                } catch (failure: Throwable) {
                    modelFailure = failure
                    throw failure
                }
            }
            val result = EntityExtraction(guarded, clock).extract(text, SOURCE_KIND, messageId)
            modelFailure?.let { Extracted.Failed(SynCodec.safeMessage(it, MAX_MESSAGE_CHARS)) } ?: Extracted.Ready(result)
        } catch (failure: Exception) {
            Extracted.Failed(SynCodec.safeMessage(failure, MAX_MESSAGE_CHARS))
        }
    }

    /** Saves exactly [selected] items and [selectedRelations] links. Blocks until the runtime finishes. */
    fun approve(result: ExtractionResult, selected: Set<Int>, selectedRelations: Set<Int>): ApprovalSummary =
        applyOnRuntime(result, selected, selectedRelations)

    /** One plain line for the user: counts, then any skipped reasons. */
    fun summaryLine(summary: ApprovalSummary): String = buildString {
        append("Saved ${summary.saved}, reused ${summary.reused}")
        if (summary.updated > 0) append(", updated ${summary.updated}")
        append(", linked ${summary.linked}")
        if (summary.skipped.isNotEmpty()) append("; skipped: ").append(summary.skipped.joinToString("; "))
    }

    companion object {
        /** Source kind recorded in provenance for facts found in chat. */
        const val SOURCE_KIND: String = "chat_message"
        private const val MAX_MESSAGE_CHARS = 160
    }
}
