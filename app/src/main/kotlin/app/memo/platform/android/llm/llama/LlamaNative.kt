package app.memo.platform.android.llm.llama

/**
 * JNI bridge to the llama.cpp native library (`libmemo_llama.so`, built from
 * `app/src/main/cpp`). The library is loaded eagerly on first access of this object;
 * a load failure is captured in [loadError] instead of crashing the caller.
 * Callers must check [loadError] to be null before invoking any external function.
 *
 * Native failures are thrown as [LlamaNativeException] (message prefixed with a
 * kind code) or as [OutOfMemoryError]. Nothing is swallowed on the native side.
 */
object LlamaNative {
    val loadError: String? = try {
        System.loadLibrary("memo_llama")
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "UnsatisfiedLinkError: libmemo_llama.so could not be loaded"
    }

    /** Returns the llama.cpp tag and system info string, e.g. "llama.cpp b11460 | ...". */
    external fun nativeVersion(): String

    /** Initialises the llama.cpp backend once per process. Safe to call repeatedly. */
    external fun nativeInitBackend()

    /**
     * Memory-maps the GGUF file at [path] and creates a context of at most [nCtx] tokens
     * (clamped to the model's training context) with [nThreads] threads. Returns an opaque
     * handle to pass to the other functions. Release it with [nativeFree].
     */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long

    /** Releases the context and model behind [handle]. Do not use the handle afterwards. */
    external fun nativeFree(handle: Long)

    /** The context size actually in use for [handle]. */
    external fun nativeContextSize(handle: Long): Int

    /** True when the model's GGUF metadata carries a chat template. */
    external fun nativeHasChatTemplate(handle: Long): Boolean

    /**
     * Renders [roles]/[contents] with the GGUF chat template. Returns null when the model
     * has no template, so the caller can fall back to another format.
     */
    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** Number of tokens [prompt] encodes to. */
    external fun nativeCountTokens(handle: Long, prompt: String, addSpecial: Boolean): Int

    /** Cancels the generation running on [handle]. Safe from any thread. */
    external fun nativeCancel(handle: Long)

    /**
     * Runs one generation of at most [maxTokens] sampled tokens. Each complete UTF-8 chunk is
     * passed to [sink]. Returns the number of tokens sampled. Stops when [sink] returns false,
     * on cancel, at end of generation, or at the token cap.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        addSpecial: Boolean,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
        maxTokens: Int,
        sink: LlamaTokenSink,
    ): Int
}

/** Receives generated text from [LlamaNative.nativeGenerate], on the calling thread. */
interface LlamaTokenSink {
    /** Returns false to stop generation. */
    fun onPiece(text: String): Boolean
}

/** Native failure. The message starts with a kind code: LOAD:, CTX:, GEN: or UNSUPPORTED:. */
class LlamaNativeException(message: String) : RuntimeException(message)
