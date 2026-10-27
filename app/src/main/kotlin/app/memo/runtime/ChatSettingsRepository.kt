package app.memo.runtime

import app.memo.core.chat.ModelSettings
import app.memo.core.chat.ModelSettingsCodec
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultName
import java.security.MessageDigest

/** Per-model chat settings stored in the encrypted vault. Calls perform blocking vault I/O. */
class ChatSettingsRepository(private val vault: Vault) {

    /** Returns defaults only when no saved record exists; malformed records are reported. */
    fun load(modelId: String): ModelSettings {
        val bytes = vault.read(entryName(modelId)) ?: return ModelSettings.DEFAULT
        return ModelSettingsCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    /** Validates an existing record before replacing it, so corrupt settings are never overwritten. */
    fun save(modelId: String, settings: ModelSettings) {
        val name = entryName(modelId)
        val existing = vault.read(name)
        if (existing != null) ModelSettingsCodec.decode(existing.toString(Charsets.UTF_8))
        vault.write(name, ModelSettingsCodec.encode(settings).toByteArray(Charsets.UTF_8))
    }

    private fun entryName(modelId: String): String {
        require(MODEL_ID.matches(modelId)) { "invalid model id" }
        val key = if (modelId.length <= DIRECT_ID_MAX_CHARS) {
            modelId
        } else {
            val digest = MessageDigest.getInstance("SHA-256").digest(modelId.toByteArray(Charsets.UTF_8))
            "x" + digest.take(HASH_CHARS / 2).joinToString("") { byte ->
                byte.toUByte().toString(16).padStart(2, '0')
            }
        }
        return VaultName.validate("model_settings_$key")
    }

    private companion object {
        const val DIRECT_ID_MAX_CHARS = 49
        const val HASH_CHARS = 48
        val MODEL_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    }
}
