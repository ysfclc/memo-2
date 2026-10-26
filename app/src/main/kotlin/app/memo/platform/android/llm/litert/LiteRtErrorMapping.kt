package app.memo.platform.android.llm.litert

import app.memo.core.platform.LlmError

/** Which operation a failure came from. Decides how generic failures are typed. */
enum class LiteRtPhase { LOAD, GENERATE }

/**
 * Maps a throwable from LiteRT-LM to the typed [LlmError] the core contract uses.
 *
 * Only fixed text and the exception's simple class name reach the result. Exception
 * messages are never copied, because they can echo prompt or output text.
 */
fun mapLiteRtError(t: Throwable, phase: LiteRtPhase): LlmError {
    val name = t.javaClass.simpleName.ifBlank { "Throwable" }
    return when (t) {
        is OutOfMemoryError -> LlmError.OutOfMemory
        is UnsupportedOperationException -> LlmError.Unsupported("feature not supported by LiteRT-LM: $name")
        is IllegalStateException -> when (phase) {
            LiteRtPhase.LOAD -> LlmError.ModelLoad("engine not ready: $name")
            LiteRtPhase.GENERATE -> LlmError.Other("runtime state: $name")
        }
        else -> when (phase) {
            LiteRtPhase.LOAD -> LlmError.ModelLoad("load failed: $name")
            LiteRtPhase.GENERATE -> LlmError.Other("generation failed: $name")
        }
    }
}
