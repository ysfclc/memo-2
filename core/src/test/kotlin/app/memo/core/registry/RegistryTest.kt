package app.memo.core.registry

import app.memo.core.domain.Person
import app.memo.core.domain.Task
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun createsThenBumpsWhenContentChanges() {
        val reg = Registry("people", Schemes.People)
        val first = reg.upsert(Person(fullName = "Ada", phone = "+49 30 123"), now = t0)
        assertIs<Registry.Put.Created<*>>(first)
        // Same name + phone digits (same natural key), but a new role:
        // real content change -> version bump, never a duplicate row.
        val second = reg.upsert(Person(fullName = "ada", phone = "+4930123", role = "Consultant"), now = t0 + 5)
        assertIs<Registry.Put.Bumped<*>>(second)
    }

    @Test
    fun reusesWhenContentSignatureMatches() {
        val reg = Registry("people", Schemes.People)
        reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0)
        val again = reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0 + 9_999)
        assertIs<Registry.Put.Reused<*>>(again)
        assertEquals(1, reg.size)
    }

    @Test
    fun bumpPreservesCreatedAtAndIncrementsVersion() {
        val reg = Registry("people", Schemes.People)
        val created = reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0).let {
            assertIs<Registry.Put.Created<Person>>(it)
            it.item
        }
        val bumped = reg.upsert(Person(fullName = "Ada", role = "Principal"), now = t0 + 5).let {
            assertIs<Registry.Put.Bumped<Person>>(it)
            it.current
        }
        assertEquals(1, created.version)
        assertEquals(2, bumped.version)
        assertEquals(created.createdAt, bumped.createdAt)
        assertEquals(t0 + 5, bumped.updatedAt)
        assertEquals("Principal", bumped.role)
    }

    @Test
    fun blanksAndUnkeyableRowsFailIntoQuarantine() {
        val reg = Registry("people", Schemes.People)
        val failed = reg.upsert(Person(fullName = "   "), now = t0)
        assertIs<Registry.Put.Failed<*>>(failed)
        assertEquals(1, reg.quarantinedRows().size)
        assertEquals(0, reg.size)
    }

    @Test
    fun naturalKeysAreStableAndDistinct() {
        val a = Schemes.People.id(Person(fullName = "Ada Lovelace", phone = "555-0100"))
        val b = Schemes.People.id(Person(fullName = "  ada lovelace ", phone = "(555)0100"))
        assertEquals(a, b) // normalization: case + separators
        val c = Schemes.People.id(Person(fullName = "Ada Lovelace", phone = "555-0199"))
        assertNotEquals(a, c)
        assertEquals(true, a.startsWith("p:"))
        assertEquals(18, a.length) // "p:" + 16 hex
    }

    @Test
    fun taskKeyBucketsByDay() {
        val dayStart = 1_700_000_000_000L
        val t1 = Schemes.Tasks.id(Task(title = "Call Hannes", dueAt = dayStart), )
        val t2 = Schemes.Tasks.id(Task(title = "Call Hannes", dueAt = dayStart + NaturalKeys.DAY_MS), )
        val t3 = Schemes.Tasks.id(Task(title = "Other errand", dueAt = dayStart), )
        assertEquals(t1, Schemes.Tasks.id(Task(title = "Call hannes", dueAt = dayStart), ))
        assertNotEquals(t1, t2)
        assertNotEquals(t1, t3)
    }

    @Test
    fun loadQuarantinesConflictingDuplicatesRatherThanOverwriting() {
        val reg = Registry("people", Schemes.People)
        reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0)
        val rows = reg.toRows()
        val clash = org.json.JSONArray()
            .put(rows.getJSONObject(0))
            .put(
                org.json.JSONObject(
                    rows.getJSONObject(0).toString().replace("\"Consultant\"", "\"Influencer\""),
                ),
            )
        val second = Registry("people", Schemes.People)
        second.load(clash)
        assertEquals(1, second.size)
        assertEquals(1, second.quarantinedRows().size)
        assertEquals("Consultant", second.items().single().role)
    }

    @Test
    fun registryCapacityIsEnforced() {
        val reg = Registry("tasks", Schemes.Tasks)
        for (i in 0 until app.memo.core.Limits.MAX_ENTRIES_PER_REGISTRY) {
            reg.upsert(Task(title = "task $i"), now = t0)
        }
        assertEquals(app.memo.core.Limits.MAX_ENTRIES_PER_REGISTRY, reg.size)
        val overflow = reg.upsert(Task(title = "one too many"), now = t0)
        assertIs<Registry.Put.Failed<*>>(overflow)
        assertTrue(overflow.reason.contains("full"))
        // Natural keys of non-ASCII names are deterministic too.
        val slug = NaturalKeys.slug("Héllo  Wörld")
        assertEquals("héllo-wörld", slug) // non-ASCII letters are kept since the second review (M0.F5)
    }

    // ---- Semantic versions ------------------------------------------------

    /** A future scheme: same entity behaviour as People, schema 2.3. */
    private object FutureSchemes : RegScheme<Person> {
        override val schemaMajor: Int get() = 2
        override val schemaMinor: Int get() = 3
        override fun id(item: Person): String = Schemes.People.id(item)
        override fun toJson(item: Person): JSONObject = item.toJson()
        override fun fromJson(raw: JSONObject): Person? = Person.fromJson(raw)
        override fun versionOf(item: Person): Int = item.version
        override fun stamped(item: Person, existing: Person?, now: Long, nextVersion: Int): Person =
            Schemes.People.stamped(item, existing, now, nextVersion)
    }

    @Test
    fun semVerFormatsAsMajorMinorPatch() {
        assertEquals("1.2.3", SemVer(1, 2, 3).toString())
        assertEquals("0.0.0", SemVer(0, 0, 0).toString())
    }

    @Test
    fun semVerParsesStrictDottedTriples() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
        assertEquals(SemVer(10, 0, 42), SemVer.parse("10.0.42"))
        assertEquals("1.2.3", SemVer.parse("1.2.3")?.toString())
    }

    @Test
    fun semVerRejectsInvalidText() {
        val bad = listOf(
            "", "1", "1.2", "1.2.3.4", "v1.2.3", "1.2.x", "1.-2.3", "+1.2.3",
            " 1.2.3", "1.2.3 ", "1..3", "a.b.c", "99999999999.0.0",
        )
        bad.forEach { text -> assertNull(SemVer.parse(text), "should reject '$text'") }
    }

    @Test
    fun semVerNegativePartsAreRefused() {
        assertFailsWith<IllegalArgumentException> { SemVer(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { SemVer(0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { SemVer(0, 0, -1) }
    }

    @Test
    fun semVerCompareOrdersMajorThenMinorThenPatch() {
        assertTrue(SemVer(1, 9, 9) < SemVer(2, 0, 0))
        assertTrue(SemVer(1, 2, 9) < SemVer(1, 3, 0))
        assertTrue(SemVer(1, 2, 3) < SemVer(1, 2, 4))
        assertEquals(0, SemVer(1, 2, 3).compareTo(SemVer(1, 2, 3)))
        assertEquals(
            listOf(SemVer(1, 0, 2), SemVer(1, 0, 10)),
            listOf(SemVer(1, 0, 10), SemVer(1, 0, 2)).sorted(),
        )
    }

    @Test
    fun contentChangeBumpsPatchByOne() {
        val reg = Registry("people", Schemes.People)
        assertIs<Registry.Put.Created<Person>>(reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0))
        val bumped = assertIs<Registry.Put.Bumped<Person>>(
            reg.upsert(Person(fullName = "Ada", role = "Principal"), now = t0 + 5),
        ).current
        assertEquals(SemVer(1, 0, 2), reg.semVerOf(bumped))
    }

    @Test
    fun identicalContentReusesSameSemVer() {
        val reg = Registry("people", Schemes.People)
        assertIs<Registry.Put.Created<Person>>(reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0))
        val reused = assertIs<Registry.Put.Reused<Person>>(
            reg.upsert(Person(fullName = "Ada", role = "Consultant"), now = t0 + 9_999),
        ).existing
        assertEquals(SemVer(1, 0, 1), reg.semVerOf(reused))
    }

    @Test
    fun schemaVersionReportsMajorDotMinor() {
        assertEquals("1.0", Registry("people", Schemes.People).schemaVersion())
        assertEquals("1.0", Registry("projects", Schemes.Projects).schemaVersion())
    }

    @Test
    fun schemeOverridesDriveSemVerAndSchemaVersion() {
        val reg = Registry("people", FutureSchemes)
        assertEquals("2.3", reg.schemaVersion())
        reg.upsert(Person(fullName = "Ada"), now = t0)
        assertEquals(SemVer(2, 3, 1), reg.semVerOf(reg.items().single()))
    }

    @Test
    fun workspaceHasSixteenRegistriesInCanonicalOrder() {
        val kinds = Workspace().all().map { it.kind }
        assertEquals(
            listOf(
                "people", "companies", "job_postings", "applications", "tasks", "drafts", "posts", "metric_samples",
                "projects", "notes", "workflow_templates", "prompt_templates", "principles",
                "content_preferences", "channel_accounts", "provider_adapters",
            ),
            kinds,
        )
        assertEquals(16, kinds.toSet().size)
    }
}
