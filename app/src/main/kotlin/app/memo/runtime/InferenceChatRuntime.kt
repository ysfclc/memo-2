package app.memo.runtime

import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.LoadResult
import app.memo.core.platform.ModelFormat
import app.memo.core.platform.ModelRef
import app.memo.core.platform.RequestHandle
import app.memo.core.platform.RuntimeInfo

/**
 * An [LlmRuntime] view of an [InferenceManager]. [VoiceLoop] keeps its raw-runtime contract,
 * but every request joins the manager's FIFO queue, so the voice loop and the chat UI share
 * the one resident model instead of loading a second one behind the manager's back.
 *
 * Residency is the manager's business: [load] is a success no-op, [unload]/[close] do nothing
 * (the loop never unloads mid-conversation, and the manager's owner closes it).
 */
class InferenceChatRuntime(
    private val inference: InferenceManager,
    private val model: ModelRef,
) : LlmRuntime {

    override val formats: Set<ModelFormat> = ModelFormat.entries.toSet()

    override fun load(ref: ModelRef): LoadResult =
        LoadResult.Loaded(RuntimeInfo(backend = BACKEND, loadedModelId = ref.id, contextTokens = 0))

    override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
        val id = inference.submit(model, turns, params, sink)
        return object : RequestHandle {
            override fun cancel() {
                inference.cancel(id)
            }
        }
    }

    override fun unload() {}

    override fun info(): RuntimeInfo = when (val current = inference.state()) {
        is InferenceManager.State.Loading -> RuntimeInfo(BACKEND, current.modelId, 0)
        is InferenceManager.State.Generating -> RuntimeInfo(BACKEND, current.modelId, 0)
        InferenceManager.State.Idle -> RuntimeInfo(BACKEND, null, 0)
    }

    override fun close() {}

    private companion object {
        const val BACKEND = "inference-queue"
    }
}
