package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelSettingsCodecTest {

    private val custom = ModelSettings(
        systemPrompt = "Be brief.\n\"Quoted\" ünïcode",
        temperature = 0.75,
        topK = 20,
        topP = 0.8,
        contextTokens = 8192,
        thinking = true,
    )

    @Test
    fun roundTripsExactly() {
        assertEquals(custom, ModelSettingsCodec.decode(ModelSettingsCodec.encode(custom)))
    }

    @Test
    fun defaultsRoundTrip() {
        assertEquals(ModelSettings.DEFAULT, ModelSettingsCodec.decode(ModelSettingsCodec.encode(ModelSettings.DEFAULT)))
    }

    @Test
    fun rejectsUnknownFieldAndMissingField() {
        val withExtra = ModelSettingsCodec.encode(custom).replace("\"v\":1", "\"v\":1,\"x\":2")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(withExtra) }
        val missing = ModelSettingsCodec.encode(custom).replace("\"topK\":20,", "")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(missing) }
    }

    @Test
    fun rejectsWrongTypeAndVersion() {
        val wrongType = ModelSettingsCodec.encode(custom).replace("\"thinking\":true", "\"thinking\":\"yes\"")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(wrongType) }
        val wrongVersion = ModelSettingsCodec.encode(custom).replace("\"v\":1", "\"v\":9")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(wrongVersion) }
    }

    @Test
    fun rejectsOutOfRangeValues() {
        val hot = ModelSettingsCodec.encode(custom).replace("\"temperature\":0.75", "\"temperature\":5")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(hot) }
        val tiny = ModelSettingsCodec.encode(custom).replace("\"contextTokens\":8192", "\"contextTokens\":8")
        assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode(tiny) }
    }

    @Test
    fun rejectsNotJsonWithoutEchoingIt() {
        val e = assertFailsWith<ModelSettingsException> { ModelSettingsCodec.decode("{secret") }
        assertEquals(false, e.message!!.contains("secret"))
    }

    @Test
    fun genParamsCarryTheSettings() {
        val p = custom.genParams(maxOutputTokens = 512)
        assertEquals(0.75, p.temperature, 0.0)
        assertEquals(20, p.topK)
        assertEquals(true, p.thinking)
        assertEquals(512, p.maxOutputTokens)
    }
}
