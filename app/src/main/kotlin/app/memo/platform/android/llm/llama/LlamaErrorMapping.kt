package app.memo.platform.android.llm.llama

import app.memo.core.platform.LlmError

/** Which operation a failure came from. */
enum class LlamaPhase { LOAD, GENERATE }

/**
 * Maps a throwable from the llama.cpp bridge to an [LlmError]. Native messages are
 * fixed text written in `memo_llama.cpp`. Other throwables contribute only their
 * simple class name, never their message.
 */
fun mapLlamaError(t: Throwable, phase: LlamaPhase): LlmError = when (t) {
    is OutOfMemoryError -> LlmError.OutOfMemory
    is UnsatisfiedLinkError -> LlmError.ModelLoad("native library unavailable")
    is LlamaNativeException -> {
        val msg = t.message.orEmpty()
        when {
            msg.startsWith("UNSUPPORTED:") -> LlmError.Unsupported(msg.removePrefix("UNSUPPORTED:").trim())
            msg.startsWith("LOAD:") -> LlmError.ModelLoad(msg.removePrefix("LOAD:").trim())
            msg.startsWith("CTX:") -> LlmError.Other(msg.removePrefix("CTX:").trim())
            msg.startsWith("GEN:") -> LlmError.Other(msg.removePrefix("GEN:").trim())
            else -> generic(t, phase)
        }
    }
    else -> generic(t, phase)
}

private fun generic(t: Throwable, phase: LlamaPhase): LlmError {
    val name = t.javaClass.simpleName.ifBlank { "Throwable" }
    return when (phase) {
        LlamaPhase.LOAD -> LlmError.ModelLoad("load failed: $name")
        LlamaPhase.GENERATE -> LlmError.Other("generation failed: $name")
    }
}
