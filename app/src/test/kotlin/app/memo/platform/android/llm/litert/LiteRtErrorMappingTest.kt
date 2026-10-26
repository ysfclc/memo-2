package app.memo.platform.android.llm.litert

import app.memo.core.platform.LlmError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiteRtErrorMappingTest {

    @Test
    fun outOfMemoryMapsToOutOfMemoryInBothPhases() {
        assertEquals(LlmError.OutOfMemory, mapLiteRtError(OutOfMemoryError("x"), LiteRtPhase.LOAD))
        assertEquals(LlmError.OutOfMemory, mapLiteRtError(OutOfMemoryError("x"), LiteRtPhase.GENERATE))
    }

    @Test
    fun illegalStateDuringLoadIsModelLoad() {
        val e = mapLiteRtError(IllegalStateException("secret"), LiteRtPhase.LOAD)
        assertIs<LlmError.ModelLoad>(e)
    }

    @Test
    fun illegalStateDuringGenerationIsOther() {
        val e = mapLiteRtError(IllegalStateException("secret"), LiteRtPhase.GENERATE)
        assertIs<LlmError.Other>(e)
    }

    @Test
    fun genericErrorDuringLoadIsModelLoad() {
        assertIs<LlmError.ModelLoad>(mapLiteRtError(RuntimeException("x"), LiteRtPhase.LOAD))
    }

    @Test
    fun genericErrorDuringGenerationIsOther() {
        assertIs<LlmError.Other>(mapLiteRtError(RuntimeException("x"), LiteRtPhase.GENERATE))
    }

    @Test
    fun unsupportedOperationMapsToUnsupported() {
        assertIs<LlmError.Unsupported>(
            mapLiteRtError(UnsupportedOperationException("x"), LiteRtPhase.GENERATE),
        )
    }

    @Test
    fun messagesAreNotCopiedIntoTheError() {
        val secret = "my private prompt text"
        val e = mapLiteRtError(RuntimeException(secret), LiteRtPhase.GENERATE)
        assertEquals(false, e.toString().contains(secret))
    }
}
