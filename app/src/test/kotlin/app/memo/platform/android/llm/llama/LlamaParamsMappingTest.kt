package app.memo.platform.android.llm.llama

import app.memo.core.platform.GenParams
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaParamsMappingTest {

    @Test
    fun everyFieldMapsToTheNativeCall() {
        val params = GenParams(
            temperature = 0.7,
            topK = 50,
            topP = 0.85,
            maxOutputTokens = 512,
            thinking = true,
            stop = listOf("END"),
        )
        val native = params.toNativeSampling(seed = 1234)
        assertEquals(
            NativeSampling(temperature = 0.7f, topK = 50, topP = 0.85f, seed = 1234, maxTokens = 512),
            native,
        )
    }

    @Test
    fun temperatureZeroStaysZeroForGreedyDecoding() {
        val native = GenParams(0.0, 1, 1.0, 16, false, emptyList()).toNativeSampling(seed = 0)
        assertEquals(0.0f, native.temperature)
    }

    @Test
    fun routineDefaultsMapUnchanged() {
        val native = GenParams.ROUTINE.toNativeSampling(seed = 7)
        assertEquals(0.3f, native.temperature)
        assertEquals(40, native.topK)
        assertEquals(0.9f, native.topP)
        assertEquals(700, native.maxTokens)
        assertEquals(7, native.seed)
    }

    @Test
    fun maxOutputTokensAtTheCoreLimitIsPassedThrough() {
        assertEquals(32_768, GenParams.ROUTINE.copy(maxOutputTokens = 32_768).toNativeSampling(1).maxTokens)
    }
}
