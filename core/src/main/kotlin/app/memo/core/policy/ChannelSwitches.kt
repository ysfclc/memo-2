package app.memo.core.policy

import org.json.JSONObject

/**
 * Immutable operator switches consulted before any automation step acts.
 *
 * - [killSwitch] on denies everything, regardless of the maps below.
 * - [channels] keys are channel names ("web", "app", "frontier", "publish",
 *   "notification"). A missing key means the channel is ENABLED.
 * - [providers] keys are provider ids or app packages. A missing key means the
 *   provider is DISABLED until discovery enables it (see [withProvider]).
 *
 * Copy helpers return new instances; the receiver is never mutated.
 */
data class ChannelSwitches(
    val killSwitch: Boolean = false,
    val channels: Map<String, Boolean> = emptyMap(),
    val providers: Map<String, Boolean> = emptyMap(),
) {
    /** True when a step on [channel] targeting [providerId] may proceed. */
    fun allows(channel: String, providerId: String): Boolean {
        if (killSwitch) return false
        val channelOn = channels[channel] ?: true
        val providerOn = providers[providerId] ?: false
        return channelOn && providerOn
    }

    fun withKill(on: Boolean): ChannelSwitches = copy(killSwitch = on)

    fun withChannel(channel: String, on: Boolean): ChannelSwitches =
        copy(channels = channels + (channel to on))

    fun withProvider(providerId: String, on: Boolean): ChannelSwitches =
        copy(providers = providers + (providerId to on))

    fun toJson(): JSONObject = JSONObject()
        .put("killSwitch", killSwitch)
        .put("channels", channels.toJsonObject())
        .put("providers", providers.toJsonObject())

    companion object {
        fun fromJson(raw: JSONObject): ChannelSwitches = ChannelSwitches(
            killSwitch = raw.optBoolean("killSwitch", false),
            channels = raw.optJSONObject("channels").toBooleanMap(),
            providers = raw.optJSONObject("providers").toBooleanMap(),
        )

        private fun Map<String, Boolean>.toJsonObject(): JSONObject {
            val obj = JSONObject()
            for ((key, value) in this) obj.put(key, value)
            return obj
        }

        private fun JSONObject?.toBooleanMap(): Map<String, Boolean> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, Boolean>()
            for (key in keys()) out[key] = optBoolean(key, false)
            return out
        }
    }
}
