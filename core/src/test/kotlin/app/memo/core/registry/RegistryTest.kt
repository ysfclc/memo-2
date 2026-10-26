package app.memo.core.registry

import app.memo.core.domain.Person
import app.memo.core.domain.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
        assertEquals("hllo-wrld", slug)
    }
}
