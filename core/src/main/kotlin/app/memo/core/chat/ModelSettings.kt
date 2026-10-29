package app.memo.core.chat

import app.memo.core.platform.GenParams
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-model settings the user edits in the model sheet. Each value is validated on construction;
 * out-of-range values are rejected, never clamped.
 */
data class ModelSettings(
    val systemPrompt: String = "",
    val temperature: Double = 0.3,
    val topK: Int = 40,
    val topP: Double = 0.9,
    val contextTokens: Int = 4096,
    val thinking: Boolean = false,
) {
    init {
        require(systemPrompt.length <= MAX_PROMPT_CHARS) { "system prompt too long" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "temperature must be in 0.0..2.0" }
        require(topK in 1..1000) { "topK must be in 1..1000" }
        require(topP.isFinite() && topP > 0.0 && topP <= 1.0) { "topP must be in (0.0, 1.0]" }
        require(contextTokens in CONTEXT_TOKENS) { "context size out of range" }
    }

    /** Sampling parameters for one reply, with the output allowance chosen by the caller. */
    fun genParams(maxOutputTokens: Int): GenParams = GenParams(
        temperature = temperature,
        topK = topK,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        thinking = thinking,
        stop = emptyList(),
    )

    companion object {
        const val MAX_PROMPT_CHARS: Int = 4_000
        val CONTEXT_TOKENS: IntRange = 512..32_768
        val DEFAULT = ModelSettings()
    }
}

/** Strict JSON codec for [ModelSettings] (format v1). Unknown fields and wrong types are rejected. */
object ModelSettingsCodec {
    const val VERSION: Int = 1
    private val KEYS = setOf("v", "systemPrompt", "temperature", "topK", "topP", "contextTokens", "thinking")

    fun encode(settings: ModelSettings): String = JSONObject()
        .put("v", VERSION)
        .put("systemPrompt", settings.systemPrompt)
        .put("temperature", settings.temperature)
        .put("topK", settings.topK)
        .put("topP", settings.topP)
        .put("contextTokens", settings.contextTokens)
        .put("thinking", settings.thinking)
        .toString()

    /** @throws ModelSettingsException when the text does not match the format. */
    fun decode(text: String): ModelSettings {
        if (text.length > 16 * 1024) throw ModelSettingsException("settings too large")
        val o = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ModelSettingsException("settings are not valid JSON")
        }
        o.keys().forEach { if (it !in KEYS) throw ModelSettingsException("unknown field") }
        KEYS.forEach { if (!o.has(it) || o.isNull(it)) throw ModelSettingsException("missing field") }
        if (o.get("v") != VERSION) throw ModelSettingsException("unsupported format version")
        return try {
            ModelSettings(
                systemPrompt = str(o, "systemPrompt"),
                temperature = number(o, "temperature"),
                topK = int(o, "topK"),
                topP = number(o, "topP"),
                contextTokens = int(o, "contextTokens"),
                thinking = bool(o, "thinking"),
            )
        } catch (e: IllegalArgumentException) {
            throw ModelSettingsException("settings out of range")
        }
    }

    private fun str(o: JSONObject, key: String): String = (o.get(key) as? String) ?: fail(key)
    private fun bool(o: JSONObject, key: String): Boolean = (o.get(key) as? Boolean) ?: fail(key)
    private fun number(o: JSONObject, key: String): Double = (o.get(key) as? Number)?.toDouble() ?: fail(key)

    private fun int(o: JSONObject, key: String): Int {
        val v = o.get(key)
        if (v !is Int && v !is Long) fail(key)
        return (v as Number).toInt()
    }

    private fun fail(key: String): Nothing = throw ModelSettingsException("field $key has the wrong type")
}

/** Raised for settings text that cannot be read. Never carries the settings content. */
class ModelSettingsException(message: String) : RuntimeException(message)
