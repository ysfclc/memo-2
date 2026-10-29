package app.memo.runtime

import app.memo.core.chat.ModelSettings
import app.memo.core.chat.ModelSettingsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatSettingsRepositoryTest {
    private val modelId = "gemma-4-e2b-it-litert"

    @Test
    fun missingSettingsUseDefaults() {
        assertEquals(ModelSettings.DEFAULT, ChatSettingsRepository(MemoryVault()).load(modelId))
    }

    @Test
    fun settingsRoundTripThroughVault() {
        val vault = MemoryVault()
        val store = ChatSettingsRepository(vault)
        val settings = ModelSettings(systemPrompt = "Be concise", temperature = 0.7, contextTokens = 8192)

        store.save(modelId, settings)

        assertEquals(settings, store.load(modelId))
        assertNotNull(vault.data["model_settings_$modelId"])
    }

    @Test
    fun corruptSettingsCannotBeOverwritten() {
        val vault = MemoryVault()
        val name = "model_settings_$modelId"
        val original = byteArrayOf(0x7b, 0x62, 0x61, 0x64, 0x7d)
        vault.data[name] = original.copyOf()
        val store = ChatSettingsRepository(vault)

        try {
            store.load(modelId)
            fail("invalid settings must be reported")
        } catch (expected: ModelSettingsException) {
            // Expected: do not substitute defaults for corrupt saved data.
        }
        try {
            store.save(modelId, ModelSettings.DEFAULT)
            fail("corrupt settings must not be overwritten")
        } catch (expected: ModelSettingsException) {
            // Expected: save re-reads and validates the existing value.
        }

        assertTrue(original.contentEquals(vault.data[name]))
    }

    @Test
    fun liveModelIdsAreAcceptedButUnsafeIdsAreRejected() {
        val vault = MemoryVault()
        val store = ChatSettingsRepository(vault)
        val longModelId = "live-" + "x".repeat(59)
        assertEquals(ModelSettings.DEFAULT, store.load(longModelId))
        val settings = ModelSettings(systemPrompt = "Keep the answer concise")
        store.save(longModelId, settings)
        assertEquals(settings, store.load(longModelId))
        assertEquals(64, vault.data.keys.single().length)
        try {
            store.load("unsafe/model")
            fail("settings ids must be safe vault names")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }
}
