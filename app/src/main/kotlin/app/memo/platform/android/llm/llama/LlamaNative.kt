package app.memo.platform.android.llm.llama

/**
 * JNI bridge to the llama.cpp native library (`libmemo_llama.so`, built from
 * `app/src/main/cpp`). The library is loaded eagerly on first access of this object;
 * a load failure is captured in [loadError] instead of crashing the caller.
 * Callers must check [loadError] to be null before invoking any external function.
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
}
