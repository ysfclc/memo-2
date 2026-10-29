package app.memo.runtime

import app.memo.core.chat.ChatLimits
import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.ConversationDoc
import app.memo.core.chat.ContextBudget
import app.memo.core.chat.MessageStatus
import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.ModelFormat
import app.memo.core.platform.ModelRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runs chat replies through [InferenceManager] and stores each finished message.
 *
 * - **One reply at a time.** [generate] refuses while a reply is streaming. Tokens reach
 *   [streaming] as they arrive, from the real runtime only.
 * - **History within budget.** The prompt is built from the stored messages with
 *   [ContextBudget]: the system prompt and the new user turn are kept, and the oldest turns are dropped.
 * - **Persistence.** When the reply ends, the assistant message is appended and the conversation
 *   is saved: DONE on completion, STOPPED with the partial text on cancel, ERROR with a fixed
 *   reason on failure. Error text never includes prompt or output text.
 * - **Threads.** Sinks run on the manager's worker thread. Storage writes are queued by the
 *   repository, so nothing blocks the generation thread.
 *
 * @param runtimeFactory creates the runtime for a model format (called on the manager's worker thread).
 * @param resolveModel returns the verified model for an id, or null when it is not installed.
 * @param onRuntimeState receives manager state changes, on the manager's worker thread.
 */
class ChatService(
    private val repository: ChatRepository,
    runtimeFactory: (ModelFormat) -> LlmRuntime,
    private val resolveModel: (String) -> ModelRef?,
    onRuntimeState: (InferenceManager.State) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    /** A reply in progress. [loading] is true until the first token or thinking chunk arrives. */
    data class Streaming(
        val conversationId: String,
        val requestId: Long,
        val text: String,
        val thinking: String,
        val loading: Boolean,
    )

    /** The loaded model, as the chat screen shows it. */
    sealed interface ModelStatus {
        data object None : ModelStatus
        data class Loading(val modelId: String) : ModelStatus
        data class Ready(val modelId: String) : ModelStatus
        data class Failed(val modelId: String, val reason: String) : ModelStatus
    }

    sealed interface StartResult {
        data class Started(val requestId: Long) : StartResult
        data class Refused(val reason: String) : StartResult
    }

    private val streamingState = MutableStateFlow<Streaming?>(null)
    val streaming: StateFlow<Streaming?> = streamingState.asStateFlow()

    private val modelState = MutableStateFlow<ModelStatus>(ModelStatus.None)
    val modelStatus: StateFlow<ModelStatus> = modelState.asStateFlow()

    private val saveErrorState = MutableStateFlow<String?>(null)

    /** The last failure to save a reply, or null. Cleared on the next successful save. */
    val saveError: StateFlow<String?> = saveErrorState.asStateFlow()

    private val lastLoaded = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val notify = onRuntimeState

    private val inference = InferenceManager(runtimeFactory) { state ->
        onManagerState(state)
        notify(state)
    }

    /**
     * Starts one reply to the last user message of [doc]. [onFinished] runs on the worker thread
     * with the conversation including the new assistant message.
     */
    @Synchronized
    fun generate(
        doc: ConversationDoc,
        modelId: String,
        params: GenParams,
        systemPrompt: String?,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        onFinished: (ConversationDoc) -> Unit,
    ): StartResult {
        if (streamingState.value != null) return StartResult.Refused("a reply is already in progress")
        val model = resolveModel(modelId) ?: return StartResult.Refused("this model is not installed")
        val budget = ContextBudget.budgetChars(contextTokens, params.maxOutputTokens)
        val selection = ContextBudget.select(systemPrompt, ContextBudget.historyTurns(doc.messages), budget)
            ?: return StartResult.Refused("this message is too long for the model context")

        val job = Job(doc, ChatRepository.newId(), modelId, now(), onFinished)
        val requestId = inference.submit(model, selection.turns, params, Sink(job))
        job.requestId = requestId
        streamingState.value = Streaming(doc.conversation.id, requestId, "", "", loading = true)
        return StartResult.Started(requestId)
    }

    /** Cancels the reply in progress. Its partial text is saved as STOPPED. Returns false when nothing is streaming. */
    fun stop(): Boolean {
        val current = streamingState.value ?: return false
        return inference.cancel(current.requestId)
    }

    /**
     * Loads [modelId] into the runtime without keeping its output: a one-token request with no
     * visible reply. Load failures show in [modelStatus].
     */
    fun preload(modelId: String): Boolean {
        val model = resolveModel(modelId) ?: return false
        val turns = listOf(ChatTurn(ChatTurn.Role.USER, "Hello"))
        inference.submit(model, turns, WARMUP_PARAMS, object : ChatSink {
            override fun onToken(text: String) = Unit
            override fun onThinking(text: String) = Unit
            override fun onComplete(stats: GenStats) = Unit
            override fun onError(e: LlmError) = Unit
        })
        return true
    }

    /** Unloads resident models only when inference has no active or queued requests. */
    fun unloadIfIdle(): Boolean {
        if (streamingState.value != null || !inference.unloadIfIdle()) return false
        lastLoaded.set(null)
        modelState.value = ModelStatus.None
        return true
    }

    /**
     * Marks replies that were still streaming when the app closed as STOPPED, and saves them.
     * Returns the same document when nothing was interrupted.
     */
    fun recoverInterrupted(doc: ConversationDoc): ConversationDoc {
        if (doc.messages.none { it.status == MessageStatus.STREAMING }) return doc
        val fixed = doc.copy(
            messages = doc.messages.map { m ->
                if (m.status == MessageStatus.STREAMING) m.copy(status = MessageStatus.STOPPED) else m
            },
        )
        repository.save(fixed)
        return fixed
    }

    override fun close() {
        inference.close()
    }

    private fun onManagerState(state: InferenceManager.State) {
        modelState.update { current ->
            when (state) {
                is InferenceManager.State.Loading -> ModelStatus.Loading(state.modelId)
                is InferenceManager.State.Generating -> ModelStatus.Ready(state.modelId)
                InferenceManager.State.Idle -> lastLoaded.get()?.let { ModelStatus.Ready(it) } ?: current
            }
        }
    }

    private inner class Job(
        val doc: ConversationDoc,
        val messageId: String,
        val modelId: String,
        val createdAt: Long,
        val onFinished: (ConversationDoc) -> Unit,
    ) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        @Volatile var requestId: Long = 0L
        var finished = false
    }

    private inner class Sink(private val job: Job) : ChatSink {
        override fun onToken(text: String) {
            synchronized(job) {
                if (job.finished) return
                if (job.text.length + text.length > ChatLimits.MAX_MESSAGE_CHARS) {
                    // Stop at the storage limit: the partial reply is kept as STOPPED.
                    inference.cancel(job.requestId)
                    return
                }
                job.text.append(text)
                lastLoaded.set(job.modelId)
                publish(job)
            }
        }

        override fun onThinking(text: String) {
            synchronized(job) {
                if (job.finished) return
                if (job.thinking.length + text.length <= ChatLimits.MAX_THINKING_CHARS) job.thinking.append(text)
                publish(job)
            }
        }

        override fun onComplete(stats: GenStats) {
            lastLoaded.set(job.modelId)
            finish(job, MessageStatus.DONE, "")
        }

        override fun onError(e: LlmError) {
            when (e) {
                LlmError.Cancelled -> finish(job, MessageStatus.STOPPED, "")
                is LlmError.ModelLoad -> {
                    modelState.value = ModelStatus.Failed(job.modelId, "the model file could not be loaded")
                    finish(job, MessageStatus.ERROR, "The model could not be loaded.")
                }
                is LlmError.OutOfMemory -> finish(job, MessageStatus.ERROR, "Not enough memory to run this model.")
                is LlmError.Unsupported -> finish(job, MessageStatus.ERROR, "This model does not support the request.")
                is LlmError.Other -> finish(job, MessageStatus.ERROR, "Generation failed.")
            }
        }
    }

    private fun publish(job: Job) {
        val snapshot = Streaming(
            conversationId = job.doc.conversation.id,
            requestId = job.requestId,
            text = job.text.toString(),
            thinking = job.thinking.toString(),
            loading = job.text.isEmpty() && job.thinking.isEmpty(),
        )
        streamingState.value = snapshot
    }

    private fun finish(job: Job, status: MessageStatus, errorText: String) {
        val text: String
        val thinking: String
        synchronized(job) {
            if (job.finished) return
            job.finished = true
            text = job.text.toString()
            thinking = job.thinking.toString()
        }
        val message = ChatMessage(
            id = job.messageId,
            role = ChatRole.ASSISTANT,
            text = text,
            thinking = thinking,
            modelId = job.modelId,
            createdAt = job.createdAt,
            status = status,
            errorText = errorText,
        )
        val updated = job.doc.copy(
            conversation = job.doc.conversation.copy(updatedAt = now(), modelId = job.modelId),
            messages = job.doc.messages + message,
        )
        streamingState.update { current -> if (current?.requestId == job.requestId) null else current }
        repository.save(updated) { failure ->
            if (failure != null) saveErrorState.value = "The reply could not be saved."
        }
        job.onFinished(updated)
    }

    companion object {
        /** Context size used for budgeting when the runtime does not report one. */
        const val DEFAULT_CONTEXT_TOKENS: Int = 4096

        private val WARMUP_PARAMS = GenParams(
            temperature = 0.0,
            topK = 1,
            topP = 1.0,
            maxOutputTokens = 1,
            thinking = false,
            stop = emptyList(),
        )
    }
}
