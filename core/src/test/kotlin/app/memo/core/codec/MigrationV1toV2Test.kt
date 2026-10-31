package app.memo.core.codec

import app.memo.core.domain.Project
import app.memo.core.registry.Workspace
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Migration step 1 -> 2, exercised on a literal v1 envelope with rows in every v1 registry. */
class MigrationV1toV2Test {

    private val v1Fixture = """
    {
      "format": "SYN",
      "schemaVersion": 1,
      "exportedAt": 1700000100000,
      "registries": {
        "people": [
          {"fullName":"Ada Lovelace","email":"ada@example.com","phone":"+44 20 7946 0000","role":"Consultant","createdAt":1700000000000,"updatedAt":1700000000000,"version":1}
        ],
        "companies": [
          {"domain":"example.com","displayName":"Example Corp","createdAt":1700000000001,"updatedAt":1700000000001,"version":1}
        ],
        "job_postings": [
          {"companyKey":"c:0123456789abcdef","role":"Staff Engineer","createdAt":1700000000002,"updatedAt":1700000000002,"version":1}
        ],
        "applications": [
          {"jobKey":"j:0123456789abcdef","createdAt":1700000000003,"updatedAt":1700000000003,"version":1}
        ],
        "tasks": [
          {"title":"Call Hannes","state":"OPEN","dueAt":1700000000000,"createdAt":1700000000004,"updatedAt":1700000000004,"version":1}
        ],
        "drafts": [
          {"kind":"POST","title":"Intro","body":"Hello world draft","createdAt":1700000000005,"updatedAt":1700000000005,"version":1}
        ],
        "posts": [
          {"url":"https://www.linkedin.com/posts/ada-1","platform":"linkedin","excerpt":"First post","createdAt":1700000000006,"updatedAt":1700000000006,"version":1}
        ],
        "metric_samples": [
          {"postKey":"po:0123456789abcdef","metric":"views","value":12,"capturedAt":1700000000000,"version":1}
        ]
      }
    }
    """.trimIndent()

    private val v1Kinds = listOf(
        "people", "companies", "job_postings", "applications",
        "tasks", "drafts", "posts", "metric_samples",
    )

    private val v2Kinds = listOf(
        "projects", "notes", "workflow_templates", "prompt_templates",
        "principles", "content_preferences", "channel_accounts", "provider_adapters",
    )

    private fun migrated(): SynCodec.ParseOutcome.Ok =
        assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(v1Fixture))

    @Test
    fun v1FixtureParsesAsMigratedFromVersionOne() {
        val outcome = migrated()
        assertEquals(1, outcome.fromVersion)
        assertEquals(true, outcome.migrated)
        assertEquals(0, outcome.workspace.quarantineCount())
    }

    @Test
    fun allOldRowsAreIntactAfterMigration() {
        val ws = migrated().workspace
        assertEquals(1, ws.people.size)
        assertEquals("Ada Lovelace", ws.people.items().single().fullName)
        assertEquals("Consultant", ws.people.items().single().role)
        assertEquals(1, ws.companies.size)
        assertEquals("Example Corp", ws.companies.items().single().displayName)
        assertEquals(1, ws.jobPostings.size)
        assertEquals("Staff Engineer", ws.jobPostings.items().single().role)
        assertEquals(1, ws.applications.size)
        assertEquals("j:0123456789abcdef", ws.applications.items().single().jobKey)
        assertEquals(1, ws.tasks.size)
        assertEquals("Call Hannes", ws.tasks.items().single().title)
        assertEquals(1, ws.drafts.size)
        assertEquals("Hello world draft", ws.drafts.items().single().body)
        assertEquals(1, ws.posts.size)
        assertEquals("https://www.linkedin.com/posts/ada-1", ws.posts.items().single().url)
        assertEquals(1, ws.samples.size)
        assertEquals("views", ws.samples.items().single().metric)
    }

    @Test
    fun newV2RegistriesAreEmptyAfterMigration() {
        val ws = migrated().workspace
        assertEquals(18, ws.all().size)
        assertEquals(0, ws.projects.size)
        assertEquals(0, ws.notes.size)
        assertEquals(0, ws.workflowTemplates.size)
        assertEquals(0, ws.promptTemplates.size)
        assertEquals(0, ws.principles.size)
        assertEquals(0, ws.contentPreferences.size)
        assertEquals(0, ws.channelAccounts.size)
        assertEquals(0, ws.providerAdapters.size)
    }

    @Test
    fun reserializingAfterMigrationWritesV2WithSchemasMap() {
        val ws = migrated().workspace
        val envelope = JSONObject(ws.serialize(exportedAt = 1L))
        assertEquals(2, envelope.getInt("schemaVersion"))
        assertEquals("SYN", envelope.getString("format"))

        val schemas = envelope.getJSONObject("schemas")
        assertEquals(18, schemas.length())
        (v1Kinds + v2Kinds).forEach { kind -> assertEquals("1.0", schemas.getString(kind)) }

        val registries = envelope.getJSONObject("registries")
        assertEquals(18, registries.length())
        v1Kinds.forEach { kind -> assertEquals(1, registries.getJSONArray(kind).length(), kind) }
        v2Kinds.forEach { kind -> assertEquals(0, registries.getJSONArray(kind).length(), kind) }
    }

    @Test
    fun migratedWorkspaceRoundTripsAsV2WithoutMigrationAgain() {
        val first = migrated().workspace.serialize(exportedAt = 7L)
        val second = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(first))
        assertEquals(2, second.fromVersion)
        assertEquals(false, second.migrated)
        assertEquals(1, second.workspace.people.size)
        assertEquals(
            CanonicalJson.canonical(JSONObject(first)),
            CanonicalJson.canonical(JSONObject(second.workspace.serialize(exportedAt = 7L))),
        )
    }

    @Test
    fun v2RowsInNewRegistriesRoundTrip() {
        val ws = Workspace()
        ws.projects.upsert(
            Project(name = "Memo launch", summary = "Ship domain v2"),
            now = 1_700_000_000_000L,
        )
        val text = ws.serialize(exportedAt = 9L)
        val outcome = assertIs<SynCodec.ParseOutcome.Ok>(SynCodec.parse(text))
        assertEquals(2, outcome.fromVersion)
        assertEquals(false, outcome.migrated)
        assertEquals("Memo launch", outcome.workspace.projects.items().single().name)
        assertEquals("Ship domain v2", outcome.workspace.projects.items().single().summary)
    }

    @Test
    fun envelopeNewerThanV2IsRejected() {
        val v3 = JSONObject(v1Fixture).put("schemaVersion", 3).toString()
        val outcome = SynCodec.parse(v3)
        assertIs<SynCodec.ParseOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("newer version 3"))
    }
}
