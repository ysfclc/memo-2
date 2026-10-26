package app.memo.platform.android.llm.llama

import app.memo.core.platform.LlmError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LlamaErrorMappingTest {

    @Test
    fun outOfMemoryMapsToOutOfMemory() {
        assertEquals(LlmError.OutOfMemory, mapLlamaError(OutOfMemoryError(), LlamaPhase.LOAD))
    }

    @Test
    fun loadPrefixMapsToModelLoad() {
        val e = mapLlamaError(LlamaNativeException("LOAD: model file could not be opened"), LlamaPhase.LOAD)
        assertEquals(LlmError.ModelLoad("model file could not be opened"), e)
    }

    @Test
    fun contextPrefixMapsToOther() {
        val e = mapLlamaError(LlamaNativeException("CTX: prompt and output do not fit"), LlamaPhase.GENERATE)
        assertIs<LlmError.Other>(e)
    }

    @Test
    fun unsupportedPrefixMapsToUnsupported() {
        val e = mapLlamaError(LlamaNativeException("UNSUPPORTED: max_tokens must be positive"), LlamaPhase.GENERATE)
        assertIs<LlmError.Unsupported>(e)
    }

    @Test
    fun genericErrorDependsOnPhase() {
        assertIs<LlmError.ModelLoad>(mapLlamaError(RuntimeException("x"), LlamaPhase.LOAD))
        assertIs<LlmError.Other>(mapLlamaError(RuntimeException("x"), LlamaPhase.GENERATE))
    }

    @Test
    fun missingNativeLibraryIsModelLoad() {
        assertEquals(
            LlmError.ModelLoad("native library unavailable"),
            mapLlamaError(UnsatisfiedLinkError("dlopen"), LlamaPhase.LOAD),
        )
    }
}
