package app.memo.platform.android.llm.llama

import app.memo.core.platform.GenParams

/** The sampling values passed to [LlamaNative.nativeGenerate], already in native types. */
internal data class NativeSampling(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val seed: Int,
    val maxTokens: Int,
)

/**
 * Maps validated [GenParams] to the native call. [seed] is supplied per request so
 * that the caller controls randomness. Ranges were already checked by [GenParams],
 * so nothing is clamped here.
 */
internal fun GenParams.toNativeSampling(seed: Int): NativeSampling = NativeSampling(
    temperature = temperature.toFloat(),
    topK = topK,
    topP = topP.toFloat(),
    seed = seed,
    maxTokens = maxOutputTokens,
)
