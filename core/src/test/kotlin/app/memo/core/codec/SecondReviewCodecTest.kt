package app.memo.core.codec

import app.memo.core.registry.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class SecondReviewCodecTest {

    @Test
    fun invalidJsonRejectionNeverEchoesThePayload() {
        val secret = "hunter2-TOP-SECRET-PASSPHRASE"
        val text = """{"format":"SYN","note":"$secret", broken"""
        val outcome = assertIs<SynCodec.ParseOutcome.Rejected>(SynCodec.parse(text))
        assertEquals("invalid JSON", outcome.reason)
        assertFalse(outcome.reason.contains(secret))
        assertFalse(outcome.reason.contains("hunter2"))
    }

    @Test
    fun safeMessageKeepsClassNameAndCutsParserEchoes() {
        assertEquals("IllegalStateException: abc", SynCodec.safeMessage(IllegalStateException("abc of type X")))
        assertEquals(
            "IllegalStateException: Unexpected token",
            SynCodec.safeMessage(IllegalStateException("Unexpected token at character 5 in secret")),
        )
        assertEquals("IllegalStateException", SynCodec.safeMessage(IllegalStateException()))
    }

    @Test
    fun safeMessageCapsLengthAtRequestedMaximum() {
        val long = SynCodec.safeMessage(IllegalStateException("a".repeat(300)))
        assertEquals("IllegalStateException: " + "a".repeat(120), long)
        val shorter = SynCodec.safeMessage(IllegalStateException("b".repeat(300)), maxChars = 160)
        assertEquals("IllegalStateException: " + "b".repeat(160), shorter)
    }

    @Test
    fun unknownRegistryArrayAndMalformedQuarantineSurviveARoundTrip() {
        val base = JSONObject(SynCodec.serialize(Workspace(), exportedAt = 1L))
        base.getJSONObject("registries").put(
            "future_registry",
            JSONArray().put(JSONObject().put("id", "from-a-newer-build").put("payload", 42)),
        )
        base.put(
            "quarantine",
            JSONObject()
                .put("people", JSONArray().put(JSONObject().put("reason", "bad row without raw")))
                .put("future_registry", JSONArray().put(JSONObject().put("reason", "x").put("raw", "{}"))),
        )

        val first = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(base.toString())).workspace
        val saved = JSONObject(SynCodec.serialize(first, exportedAt = 2L))
        val second = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(saved.toString())).workspace
        val again = JSONObject(SynCodec.serialize(second, exportedAt = 3L))

        val futureRows = again.getJSONObject("registries").getJSONArray("future_registry")
        assertEquals("from-a-newer-build", futureRows.getJSONObject(0).getString("id"))
        assertEquals(42, futureRows.getJSONObject(0).getInt("payload"))

        val quarantine = again.getJSONObject("quarantine")
        assertEquals("bad row without raw", quarantine.getJSONArray("people").getJSONObject(0).getString("reason"))
        assertEquals("x", quarantine.getJSONArray("future_registry").getJSONObject(0).getString("reason"))
        assertTrue(second.quarantineCount() == 0) // malformed entries are carried, not promoted to quarantined rows
    }

    @Test
    fun migratedEnvelopeDoesNotCarryUnknownRegistries() {
        val v1 = JSONObject()
            .put("format", "SYN")
            .put("schemaVersion", 1)
            .put("registries", JSONObject().put("ghost_registry", JSONArray().put(JSONObject().put("id", "g"))))
        val ok = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(v1.toString()))
        assertTrue(ok.migrated)
        val saved = JSONObject(SynCodec.serialize(ok.workspace, exportedAt = 1L))
        assertFalse(saved.getJSONObject("registries").has("ghost_registry"))
    }
}
