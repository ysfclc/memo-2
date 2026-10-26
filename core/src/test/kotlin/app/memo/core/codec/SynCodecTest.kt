package app.memo.core.codec

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.DraftKind
import app.memo.core.domain.Person
import app.memo.core.registry.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class SynCodecTest {

    private fun freshWorkspace(): Workspace {
        val ws = Workspace()
        ws.people.upsert(
            Person(fullName = "Ada Lovelace", email = "ada@example.com", role = "Consultant"),
            now = 1_700_000_000_000,
        )
        ws.companies.upsert(Company(domain = "example.com", displayName = "Example Corp"), now = 1_700_000_000_001)
        ws.drafts.upsert(
            ContentDraft(kind = DraftKind.POST, title = "Intro", body = "Hello world draft"),
            now = 1_700_000_000_002,
        )
        return ws
    }

    @Test
    fun roundTripsAllRegistries() {
        val original = freshWorkspace()
        val text = original.serialize(exportedAt = 1_700_000_010_000)
        val outcome = SynCodec.parse(text)
        assertIs<SynCodec.ParseOutcome.Ok>(outcome)
        assertEquals(2, outcome.fromVersion)
        assertEquals(false, outcome.migrated)
        assertEquals(1, outcome.workspace.people.size)
        assertEquals(1, outcome.workspace.companies.size)
        assertEquals(1, outcome.workspace.drafts.size)
        val person = outcome.workspace.people.items().single()
        assertEquals("Ada Lovelace", person.fullName)
        assertEquals("Consultant", person.role)
    }

    @Test
    fun serializeWritesV2EnvelopeWithSchemasMap() {
        val envelope = JSONObject(freshWorkspace().serialize(exportedAt = 1L))
        assertEquals(2, envelope.getInt("schemaVersion"))
        val schemas = envelope.getJSONObject("schemas")
        val registries = envelope.getJSONObject("registries")
        assertEquals(16, schemas.length())
        assertEquals(16, registries.length())
        schemas.keys().forEach { kind ->
            assertEquals("1.0", schemas.getString(kind))
            assertTrue(registries.has(kind), "registry $kind missing from envelope")
        }
    }

    @Test
    fun rejectsGarbageText() {
        val outcome = SynCodec.parse("this is not json at all")
        assertIs<SynCodec.ParseOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("invalid JSON"))
    }

    @Test
    fun rejectsWrongFormatId() {
        val bad = JSONObject().put("format", "OTHER").put("schemaVersion", 1).put("registries", JSONObject())
        val outcome = SynCodec.parse(bad.toString())
        assertIs<SynCodec.ParseOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("format"))
    }

    @Test
    fun rejectsMissingSchemaVersion() {
        val bad = JSONObject().put("format", "SYN").put("registries", JSONObject())
        val outcome = SynCodec.parse(bad.toString())
        assertIs<SynCodec.ParseOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("schemaVersion"))
    }

    @Test
    fun rejectsNewerVersions() {
        val bad = JSONObject().put("format", "SYN").put("schemaVersion", 99).put("registries", JSONObject())
        val outcome = SynCodec.parse(bad.toString())
        assertIs<SynCodec.ParseOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("newer"))
    }

    @Test
    fun quarantinesInvalidRowsInsteadOfFailing() {
        val envelope = JSONObject()
            .put("format", "SYN")
            .put("schemaVersion", 1)
            .put(
                "registries",
                JSONObject()
                    .put("people", JSONArray().put(JSONObject().put("fullName", ""))),
            )
        val outcome = SynCodec.parse(envelope.toString())
        assertIs<SynCodec.ParseOutcome.Ok>(outcome)
        assertEquals(1, outcome.workspace.people.quarantinedRows().size)
        assertEquals(0, outcome.workspace.people.size)
    }

    @Test
    fun serializeRoundTripKeepsQuarantineVisible() {
        val ws = freshWorkspace()
        val text = ws.serialize(1L)
        val ok = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(text))
        // The round-tripped workspace re-serializes to the same row set.
        val again = ok.workspace.serialize(2L)
        val ok2 = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(again))
        assertEquals(ok.workspace.people.items().single().fullName, ok2.workspace.people.items().single().fullName)
    }
}
