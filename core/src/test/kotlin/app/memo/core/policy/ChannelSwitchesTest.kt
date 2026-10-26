package app.memo.core.policy

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSwitchesTest {

    private val chatgpt = "chatgpt"

    @Test
    fun missingChannelIsEnabledAndMissingProviderIsDisabled() {
        val base = ChannelSwitches()
        assertTrue(base.channels.isEmpty())
        assertFalse(base.allows("web", chatgpt), "unknown provider must stay denied until discovery enables it")
        assertTrue(base.withProvider(chatgpt, true).allows("web", chatgpt))
    }

    @Test
    fun allowsMatrixForChannelAndProvider() {
        val enabled = ChannelSwitches()
            .withProvider(chatgpt, true)
            .withProvider("com.example.app", true)
        // channel enabled (missing key) x provider enabled
        assertTrue(enabled.allows("web", chatgpt))
        assertTrue(enabled.allows("app", "com.example.app"))
        assertTrue(enabled.allows("frontier", chatgpt))
        // provider not present
        assertFalse(enabled.allows("web", "claude.ai"))
        // channel explicitly disabled
        val webOff = enabled.withChannel("web", false)
        assertFalse(webOff.allows("web", chatgpt))
        assertTrue(webOff.allows("app", "com.example.app"))
        // channel explicitly enabled
        assertTrue(webOff.withChannel("web", true).allows("web", chatgpt))
        // provider explicitly disabled
        assertFalse(enabled.withProvider(chatgpt, false).allows("web", chatgpt))
    }

    @Test
    fun killSwitchDeniesEverythingAndCanBeReverted() {
        val open = ChannelSwitches()
            .withProvider(chatgpt, true)
            .withChannel("notification", true)
        assertTrue(open.allows("web", chatgpt))

        val killed = open.withKill(true)
        assertTrue(killed.killSwitch)
        assertFalse(killed.allows("web", chatgpt))
        assertFalse(killed.allows("notification", chatgpt))
        assertFalse(killed.allows("web", "unknown"))

        assertTrue(killed.withKill(false).allows("web", chatgpt))
    }

    @Test
    fun copyHelpersDoNotMutateTheReceiver() {
        val base = ChannelSwitches()
        val changed = base.withKill(true).withChannel("publish", false).withProvider(chatgpt, true)
        assertFalse(base.killSwitch)
        assertTrue(base.channels.isEmpty())
        assertTrue(base.providers.isEmpty())
        assertTrue(changed.killSwitch)
        assertEquals(false, changed.channels["publish"])
        assertEquals(true, changed.providers[chatgpt])
    }

    @Test
    fun jsonRoundTripPreservesEverything() {
        val original = ChannelSwitches(
            killSwitch = true,
            channels = mapOf("web" to true, "publish" to false, "notification" to true),
            providers = mapOf(chatgpt to true, "com.example.app" to false),
        )
        val json = original.toJson()
        assertTrue(json.getBoolean("killSwitch"))
        val restored = ChannelSwitches.fromJson(JSONObject(json.toString()))
        assertEquals(original, restored)
        assertEquals(false, restored.allows("web", chatgpt))
    }

    @Test
    fun fromJsonToleratesMissingFieldsAndYieldsDefaults() {
        assertEquals(ChannelSwitches(), ChannelSwitches.fromJson(JSONObject("{}")))
        val partial = ChannelSwitches.fromJson(JSONObject("""{"providers":{"chatgpt":true}}"""))
        assertFalse(partial.killSwitch)
        assertTrue(partial.channels.isEmpty())
        assertTrue(partial.allows("web", chatgpt))
    }
}
