package app.memo.core.prompts

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.Person
import app.memo.core.domain.Project
import app.memo.core.domain.Task
import app.memo.core.registry.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketBuilderTest {

    private val now = 1_000L

    private fun Workspace.addPerson(name: String, notes: String = ""): String {
        val person = Person(fullName = name, notes = notes)
        people.upsert(person, now)
        return people.scheme.id(person)
    }

    private fun Workspace.addCompany(domain: String, displayName: String): String {
        val company = Company(domain = domain, displayName = displayName)
        companies.upsert(company, now)
        return companies.scheme.id(company)
    }

    @Test
    fun rankOrdersMentionThenNaturalKeyThenKeyword() {
        val ws = Workspace()
        ws.addPerson("Ana Silva", "unrelated words")
        ws.addCompany("acme.com", "Acme Corp")
        ws.addPerson("Linus Torvalds", "kernel scheduling launch")

        val query = "Draft a reply to @Ana Silva about Acme Corp kernel scheduling launch"
        val ranked = PacketBuilder.rank(query, ws)

        assertEquals(
            listOf(PacketBuilder.Match.MENTION, PacketBuilder.Match.NATURAL_KEY, PacketBuilder.Match.KEYWORD),
            ranked.map { it.match },
        )
        assertEquals(listOf("people", "companies", "people"), ranked.map { it.kind })
        assertEquals(listOf("ana silva", "acme corp", "linus torvalds"), ranked.map { it.label })
    }

    @Test
    fun naturalNameRequiresWordBoundary() {
        val ws = Workspace()
        ws.addPerson("Ana")

        assertTrue(PacketBuilder.rank("Anastasia wrote the plan", ws).isEmpty())
        assertEquals(PacketBuilder.Match.NATURAL_KEY, PacketBuilder.rank("ask Ana to review", ws).single().match)
    }

    @Test
    fun twoCharNamesNeverMatch() {
        val ws = Workspace()
        ws.addPerson("Al")

        assertTrue(PacketBuilder.rank("@Al and Al, hello", ws).isEmpty())
    }

    @Test
    fun labelFallsBackToKeyWhenRowHasNoName() {
        val ws = Workspace()
        val key = ws.addCompany("beta.io", "")

        val hit = PacketBuilder.rank("@$key", ws).single()

        assertEquals(PacketBuilder.Match.MENTION, hit.match)
        assertEquals(key, hit.label)
    }

    @Test
    fun keywordScoreOrdersByDistinctWordOverlap() {
        val ws = Workspace()
        ws.addPerson("Alan Turing", "quarterly revenue forecast")
        ws.addPerson("Barbara Liskov", "forecast only")

        val ranked = PacketBuilder.rank("quarterly revenue forecast planning", ws)

        assertEquals(2, ranked.size)
        assertEquals(PacketBuilder.Match.KEYWORD, ranked[0].match)
        assertEquals(3, ranked[0].score)
        assertEquals(1, ranked[1].score)
        assertTrue(ranked[0].text.contains("Alan Turing"))
    }

    @Test
    fun packetStaysWithinBudget() {
        val ws = Workspace()
        repeat(30) { i -> ws.addPerson("Person Number $i", "quarterly revenue forecast item $i") }

        val packet = PacketBuilder.build("quarterly revenue forecast", ws, budgetChars = 600)

        assertTrue(packet.approximateChars <= 600, "approximateChars=${packet.approximateChars}")
        assertTrue(packet.fieldCount in 1 until 30, "fieldCount=${packet.fieldCount}")
    }

    @Test
    fun recentChatReserveKeepsPacketWithinBudget() {
        val ws = Workspace()
        repeat(30) { i -> ws.addPerson("Person Number $i", "quarterly revenue forecast item $i") }
        val chat = List(8) { "chat line number ${it + 1} with a fairly long message body to spend budget" }

        val packet = PacketBuilder.build("quarterly revenue forecast", ws, chat, budgetChars = 600)

        assertTrue(packet.approximateChars <= 600, "approximateChars=${packet.approximateChars}")
        assertTrue(packet.fieldsSnapshot().containsKey(PacketBuilder.RECENT_CHAT_FIELD))
    }

    @Test
    fun secretsAreRedactedFromPacketValues() {
        val ws = Workspace()
        val secret = "sk-abcdefghijklmnopqrstuv1234"
        ws.addPerson("Secret Holder", "api key $secret for the demo")

        val packet = PacketBuilder.build("holder demo", ws)
        val values = packet.fieldsSnapshot().values.joinToString("\n")

        assertFalse(values.contains(secret), "raw secret must not reach the packet")
        assertTrue(values.contains("[REDACTED_OPENAI_KEY]"))
    }

    @Test
    fun recentChatContainsLastSixLinesOnly() {
        val ws = Workspace()
        val chat = (1..8).map { "chat-%02d".format(it) }

        val packet = PacketBuilder.build("", ws, chat)
        val value = packet.fieldsSnapshot().getValue(PacketBuilder.RECENT_CHAT_FIELD)

        for (n in 3..8) assertTrue(value.contains("chat-%02d".format(n)), "missing chat-%02d".format(n))
        assertFalse(value.contains("chat-01"))
        assertFalse(value.contains("chat-02"))
    }

    @Test
    fun summaryHasCountsAndNewestTitlesOnly() {
        val ws = Workspace()
        ws.addPerson("Ada Lovelace")
        ws.addPerson("Grace Hopper")
        repeat(6) { i -> ws.tasks.upsert(Task(title = "task ${i + 1}"), now + i) }
        listOf("alpha", "beta", "gamma", "delta").forEachIndexed { i, name ->
            ws.projects.upsert(Project(name = name), now + i)
        }
        ws.drafts.upsert(ContentDraft(title = "draft one", body = "text"), now)

        val fields = PacketBuilder.summary(ws).fieldsSnapshot()

        assertEquals(
            mapOf(
                "people_count" to "2",
                "task_count" to "6",
                "draft_count" to "1",
                "latest_tasks" to "task 6; task 5; task 4; task 3; task 2",
                "latest_projects" to "delta; gamma; beta",
            ),
            fields,
        )
    }

    @Test
    fun summaryOfEmptyWorkspaceKeepsOnlyZeroCounts() {
        val fields = PacketBuilder.summary(Workspace()).fieldsSnapshot()

        assertEquals(mapOf("people_count" to "0", "task_count" to "0", "draft_count" to "0"), fields)
    }

    @Test
    fun emptyQueryAndEmptyChatGiveEmptyPacket() {
        val ws = Workspace()
        ws.addPerson("Ada Lovelace", "analytical engine")

        assertTrue(PacketBuilder.build("", ws).isEmpty())
        assertTrue(PacketBuilder.build("   ", ws, emptyList()).isEmpty())
    }
}
