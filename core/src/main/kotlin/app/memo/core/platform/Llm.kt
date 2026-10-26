package app.memo.core.platform

/** Model container formats a runtime may be able to load. */
enum class ModelFormat { LITERTLM, GGUF }

/**
 * A pinned, verified model file on device.
 *
 * [id] is the catalog id (lowercase, `[a-z0-9][a-z0-9.-]{0,63}`), [path] is
 * the app-private location of the file, [sha256] is the lowercase hex digest
 * of the verified bytes. [sizeBytes] is 0 when the size is unknown.
 */
data class ModelRef(
    val id: String,
    val format: ModelFormat,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(ID_PATTERN.matches(id)) { "invalid model id: $id" }
        require(path.isNotBlank()) { "model path must not be blank" }
        require(SHA256_PATTERN.matches(sha256)) { "sha256 must be 64 lowercase hex characters" }
        require(sizeBytes >= 0) { "sizeBytes must be >= 0" }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * One message in a chat request. [toolName] is required for [Role.TOOL]
 * turns and must be null for every other role.
 */
data class ChatTurn(
    val role: Role,
    val text: String,
    val toolName: String? = null,
) {
    /** Speaker of a turn. */
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    init {
        require((role == Role.TOOL) == (toolName != null)) {
            "toolName is required for TOOL turns and forbidden otherwise"
        }
        require(toolName == null || toolName.isNotBlank()) { "toolName must not be blank" }
    }
}

/**
 * Sampling parameters for one request. All ranges are validated on
 * construction; out-of-range values are rejected, never clamped.
 *
 * - [temperature] in 0.0..2.0
 * - [topK] in 1..1000
 * - [topP] in (0.0, 1.0]
 * - [maxOutputTokens] in 1..32768
 * - [thinking] enables the model's reasoning channel when it has one
 * - [stop] at most 8 non-blank sequences of at most 64 characters each
 */
data class GenParams(
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    val maxOutputTokens: Int,
    val thinking: Boolean,
    val stop: List<String>,
) {
    init {
        require(temperature.isFinite() && temperature in 0.0..2.0) { "temperature must be in 0.0..2.0" }
        require(topK in 1..1000) { "topK must be in 1..1000" }
        require(topP.isFinite() && topP > 0.0 && topP <= 1.0) { "topP must be in (0.0, 1.0]" }
        require(maxOutputTokens in 1..32_768) { "maxOutputTokens must be in 1..32768" }
        require(stop.size <= 8) { "at most 8 stop sequences" }
        require(stop.all { it.isNotBlank() && it.length <= 64 }) {
            "stop sequences must be non-blank and at most 64 characters"
        }
    }

    companion object {
        /** Conservative defaults for routine, thinking-off steps. */
        val ROUTINE = GenParams(
            temperature = 0.3,
            topK = 40,
            topP = 0.9,
            maxOutputTokens = 700,
            thinking = false,
            stop = emptyList(),
        )
    }
}

/** Accounting for one completed request. [backend] names the engine that served it. */
data class GenStats(
    val promptTokens: Int,
    val outputTokens: Int,
    val millis: Long,
    val backend: String,
) {
    init {
        require(promptTokens >= 0 && outputTokens >= 0) { "token counts must be >= 0" }
        require(millis >= 0) { "millis must be >= 0" }
        require(backend.isNotBlank()) { "backend must not be blank" }
    }
}

/** Why a load or a request failed. */
sealed class LlmError {
    /** The runtime ran out of memory. The runtime is left unloaded. */
    data object OutOfMemory : LlmError()

    /** The model file could not be opened, parsed or initialised. */
    data class ModelLoad(val msg: String) : LlmError()

    /** The model or request uses a feature this runtime does not support. */
    data class Unsupported(val msg: String) : LlmError()

    /** The request was cancelled through its [RequestHandle] or by [LlmRuntime.unload]. */
    data object Cancelled : LlmError()

    /** Any other failure. [msg] must not contain prompt or output text. */
    data class Other(val msg: String) : LlmError()
}

/** Result of [LlmRuntime.load]. */
sealed class LoadResult {
    /** The model is resident and ready for [LlmRuntime.chat]. */
    data class Loaded(val info: RuntimeInfo) : LoadResult()

    /** The model is not loaded. The runtime keeps no partial state. */
    data class Failed(val error: LlmError) : LoadResult()
}

/**
 * Snapshot of a runtime. [loadedModelId] is null when nothing is loaded.
 * [contextTokens] is 0 when the runtime cannot report its context size.
 */
data class RuntimeInfo(
    val backend: String,
    val loadedModelId: String?,
    val contextTokens: Int,
)

/**
 * Receives the output of one chat request.
 *
 * Exactly one of [onComplete] or [onError] is called, last. No callback is
 * made after either. [onToken] and [onThinking] arrive in generation order.
 * Callbacks run on a runtime-owned thread, never on the caller's thread, and
 * must not block: hand work to a queue and return.
 */
interface ChatSink {
    /** A chunk of visible answer text. */
    fun onToken(text: String)

    /** A chunk of reasoning text. Only called when [GenParams.thinking] is true. */
    fun onThinking(text: String)

    /** The request finished normally. */
    fun onComplete(stats: GenStats)

    /** The request failed or was cancelled. */
    fun onError(e: LlmError)
}

/**
 * Handle for one in-flight chat request.
 *
 * [cancel] is idempotent and best effort. Once it is called, no further
 * [ChatSink.onToken] or [ChatSink.onThinking] is delivered. The sink then
 * receives [LlmError.Cancelled], unless [ChatSink.onComplete] or
 * [ChatSink.onError] was already delivered. Calling [cancel] after the request
 * has ended does nothing.
 */
interface RequestHandle {
    /** Requests cancellation of this request. Safe to call from any thread. */
    fun cancel()
}

/**
 * On-device language model runtime. Implemented by the app over LiteRT-LM and
 * llama.cpp, and by desktop builds with their own engines.
 *
 * Contract:
 * - **One request at a time.** [chat] while another request is in flight
 *   throws [IllegalStateException]. Callers queue requests themselves.
 * - **Threading.** Callbacks run on a runtime-owned thread (see [ChatSink]).
 *   [load], [chat], [unload] and [info] may be called from any thread, but
 *   the caller must not call them concurrently for the same runtime.
 * - **Cancellation.** See [RequestHandle]. [unload] cancels an in-flight
 *   request first, so its sink receives [LlmError.Cancelled].
 * - **Formats.** [chat] requires a loaded model. A [ModelRef.format] not in
 *   [formats] fails [load] with [LlmError.Unsupported].
 * - **Closing.** [close] unloads the model and releases the runtime. The
 *   runtime must not be used after [close].
 */
interface LlmRuntime : AutoCloseable {
    /** Model formats this runtime can load. */
    val formats: Set<ModelFormat>

    /**
     * Loads [ref], unloading any previous model first. The file must already
     * be verified against [ModelRef.sha256] by the caller.
     */
    fun load(ref: ModelRef): LoadResult

    /**
     * Starts one chat request over a loaded model. Results arrive through
     * [sink]. The returned handle cancels this request only.
     *
     * @throws IllegalStateException if no model is loaded or another request is in flight.
     */
    fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle

    /** Cancels any in-flight request, then releases the loaded model's memory. */
    fun unload()

    /** Reports the current state. Cheap, and safe to call while a request is in flight. */
    fun info(): RuntimeInfo
}
