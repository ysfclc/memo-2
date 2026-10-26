package app.memo.core.codec

import app.memo.core.domain.Person
import app.memo.core.registry.Registry
import app.memo.core.registry.Schemes
import app.memo.core.registry.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class QuarantinePreservationTest {

    private val t0 = 1_700_000_000_000L

    private fun envelope(people: JSONArray, quarantine: JSONObject? = null): String {
        val doc = JSONObject()
            .put("format", "SYN")
            .put("schemaVersion", 2)
            .put("registries", JSONObject().put("people", people))
        if (quarantine != null) doc.put("quarantine", quarantine)
        return doc.toString()
    }

    private fun parsed(text: String): Workspace =
        assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(text)).workspace

    /** A row the scheme rejects: blank full name means no natural key. */
    private fun badRow(): JSONObject = JSONObject().put("fullName", "")

    /** Two rows with the same natural key and different content: the second is quarantined on load. */
    private fun conflictingPair(): Pair<JSONObject, JSONObject> {
        val base = Person(fullName = "Ada", phone = "+49 30 123").toJson()
        val conflicting = JSONObject(base.toString()).put("role", "Consultant")
        return base to conflicting
    }

    @Test
    fun quarantinedRowSurvivesASave() {
        val first = parsed(envelope(JSONArray().put(badRow())))
        assertEquals(1, first.people.quarantinedRows().size)

        val second = parsed(first.serialize(exportedAt = 1L))
        assertEquals(0, second.people.size)
        val kept = second.people.quarantinedRows().single()
        assertEquals(badRow().toString(), kept.raw)
        assertTrue(kept.reason.isNotBlank())
    }

    @Test
    fun quarantineDoesNotGrowOrShrinkAcrossRepeatedSaves() {
        var text = envelope(JSONArray().put(badRow()))
        repeat(3) {
            text = parsed(text).serialize(exportedAt = 1L)
        }
        assertEquals(1, parsed(text).people.quarantinedRows().size)
    }

    @Test
    fun validRowsAndQuarantinedRowsCoexistAcrossSaves() {
        val valid = Person(fullName = "Ada Lovelace").toJson()
        val loaded = parsed(envelope(JSONArray().put(valid).put(badRow())))
        assertEquals(1, loaded.people.size)
        assertEquals(1, loaded.people.quarantinedRows().size)

        val again = parsed(loaded.serialize(exportedAt = 2L))
        assertEquals("Ada Lovelace", again.people.items().single().fullName)
        assertEquals(1, again.people.quarantinedRows().size)
    }

    @Test
    fun restoredRowsAreNeverPromotedAutomatically() {
        // A quarantine entry whose raw text is a perfectly valid person must stay quarantined on parse.
        val valid = Person(fullName = "Grace Hopper").toJson()
        val quarantine = JSONObject().put(
            "people",
            JSONArray().put(JSONObject().put("reason", "earlier failure").put("raw", valid.toString())),
        )
        val ws = parsed(envelope(JSONArray(), quarantine))
        assertEquals(0, ws.people.size)
        assertEquals(1, ws.people.quarantinedRows().size)
        assertEquals("earlier failure", ws.people.quarantinedRows().single().reason)
    }

    @Test
    fun envelopeWithoutQuarantineKeyStillParses() {
        val ws = parsed(envelope(JSONArray().put(Person(fullName = "Ada").toJson())))
        assertEquals(1, ws.people.size)
        assertEquals(0, ws.quarantineCount())
    }

    @Test
    fun serializeOmitsQuarantineWhenEmpty() {
        val text = Workspace().serialize(exportedAt = 1L)
        assertEquals(false, JSONObject(text).has("quarantine"))
    }

    @Test
    fun serializeWritesReasonAndRawPerKind() {
        val ws = parsed(envelope(JSONArray().put(badRow())))
        val doc = JSONObject(ws.serialize(exportedAt = 1L))
        val entries = doc.getJSONObject("quarantine").getJSONArray("people")
        assertEquals(1, entries.length())
        assertEquals(badRow().toString(), entries.getJSONObject(0).getString("raw"))
        assertTrue(entries.getJSONObject(0).getString("reason").isNotBlank())
        assertEquals(false, doc.getJSONObject("quarantine").has("companies"))
    }

    @Test
    fun releaseFixesAConflictingRowAndRemovesItFromQuarantine() {
        val reg = Registry("people", Schemes.People)
        val (base, conflicting) = conflictingPair()
        reg.load(JSONArray().put(base).put(conflicting))
        assertEquals(1, reg.size)
        assertEquals(1, reg.quarantinedRows().size)

        val put = reg.releaseQuarantined(0, now = t0)
        assertIs<Registry.Put.Bumped<*>>(put)
        assertEquals(0, reg.quarantinedRows().size)
        assertEquals("Consultant", reg.items().single().role)
    }

    @Test
    fun releasedRowSurvivesTheSaveRoundTrip() {
        val (base, conflicting) = conflictingPair()
        val loaded = parsed(envelope(JSONArray().put(base).put(conflicting)))
        assertIs<Registry.Put.Bumped<*>>(loaded.people.releaseQuarantined(0, now = t0))

        val reparsed = parsed(loaded.serialize(exportedAt = 3L))
        assertEquals(1, reparsed.people.size)
        assertEquals("Consultant", reparsed.people.items().single().role)
        assertEquals(0, reparsed.people.quarantinedRows().size)
    }

    @Test
    fun releaseOfAStillInvalidRowFailsAndKeepsIt() {
        val reg = Registry("people", Schemes.People)
        reg.load(JSONArray().put(badRow()))
        val put = reg.releaseQuarantined(0, now = t0)
        assertIs<Registry.Put.Failed<*>>(put)
        assertEquals(1, reg.quarantinedRows().size)
        assertEquals(0, reg.size)
    }

    @Test
    fun releaseWithAnOutOfRangeIndexFails() {
        val reg = Registry("people", Schemes.People)
        assertIs<Registry.Put.Failed<*>>(reg.releaseQuarantined(0, now = t0))
    }
}
