package app.memo.core.agent

import app.memo.core.domain.ReviewStatus
import app.memo.core.plan.PlanModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityExtractionTest {

    private fun run(reply: String, text: String = TEXT): ExtractionResult =
        EntityExtraction(PlanModel { _, _ -> reply }, { 1234L }).extract(text, "chat_message", "msg-1")

    @Test
    fun personCompanyAndWorksAtRelationAreKeptWithRemappedIndexes() {
        val result = run(
            """
            {"items":[
              {"kind":"person","fields":{"fullName":"Ana Lopez","role":"CTO"},"excerpt":"Ana Lopez, CTO"},
              {"kind":"company","fields":{"domain":"acme.com","displayName":"Acme Corp"},"excerpt":"Acme Corp"}
            ],"relations":[{"from":0,"to":1,"type":"works_at","excerpt":"CTO at Acme Corp"}]}
            """.trimIndent(),
        )
        assertEquals(listOf("person", "company"), result.typed.map { it.kind })
        assertEquals(emptyList(), result.rejected)
        val relation = result.relations.single()
        assertEquals(0, relation.fromIndex)
        assertEquals(1, relation.toIndex)
        assertEquals("works_at", relation.relationType)
        assertEquals("CTO at Acme Corp", relation.provenance.excerpt)
    }

    @Test
    fun relationIndexesAreRemappedAfterAnItemIsDropped() {
        val result = run(
            """
            {"items":[
              {"kind":"company","fields":{"domain":"ghost.io"},"excerpt":"Ghost Labs"},
              {"kind":"person","fields":{"fullName":"Ana Lopez"},"excerpt":"Ana Lopez"},
              {"kind":"company","fields":{"domain":"acme.com"},"excerpt":"Acme Corp"}
            ],"relations":[{"from":1,"to":2,"type":"works_at","excerpt":"Ana Lopez, CTO at Acme Corp"}]}
            """.trimIndent(),
        )
        assertEquals(listOf("person", "company"), result.typed.map { it.kind })
        val relation = result.relations.single()
        assertEquals(0, relation.fromIndex)
        assertEquals(1, relation.toIndex)
    }

    @Test
    fun unsupportedClaimIsRejectedAndTheRestSurvives() {
        val result = run(
            """
            {"items":[
              {"kind":"person","fields":{"fullName":"Bob Stone"},"excerpt":"Bob Stone, CFO"},
              {"kind":"company","fields":{"domain":"acme.com"},"excerpt":"Acme Corp"}
            ],"relations":[]}
            """.trimIndent(),
        )
        assertEquals(listOf("company"), result.typed.map { it.kind })
        assertEquals(
            listOf("unsupported claim: person 'Bob Stone' (excerpt not found in the source)"),
            result.rejected,
        )
    }

    @Test
    fun fabricatedRelationExcerptDropsOnlyTheRelation() {
        val result = run(
            """
            {"items":[
              {"kind":"person","fields":{"fullName":"Ana Lopez"},"excerpt":"Ana Lopez"},
              {"kind":"company","fields":{"domain":"acme.com"},"excerpt":"Acme Corp"}
            ],"relations":[{"from":0,"to":1,"type":"works_at","excerpt":"Ana founded Acme"}]}
            """.trimIndent(),
        )
        assertEquals(2, result.typed.size)
        assertEquals(emptyList(), result.relations)
        assertEquals(
            listOf("unsupported claim: relation 'works_at' (excerpt not found in the source)"),
            result.rejected,
        )
    }

    @Test
    fun excerptMatchIgnoresCaseAndWhitespace() {
        val result = run(
            """{"items":[{"kind":"person","fields":{"fullName":"Ana Lopez"},"excerpt":"ANA   lopez,  cto"}],"relations":[]}""",
        )
        assertEquals(listOf("person"), result.typed.map { it.kind })
        assertEquals(emptyList(), result.rejected)
    }

    @Test
    fun genericEntityIsProposedWithProvenance() {
        val result = run(
            """{"items":[{"kind":"entity","type":"place","fields":{"title":"Web Summit"},"excerpt":"at Web Summit"}],"relations":[]}""",
        )
        val entity = result.entities.single()
        assertEquals("place", entity.type)
        assertEquals("Web Summit", entity.title)
        assertEquals(ReviewStatus.PROPOSED, entity.reviewStatus)
        val provenance = entity.provenance.single()
        assertEquals("chat_message", provenance.sourceKind)
        assertEquals("msg-1", provenance.sourceId)
        assertEquals("at Web Summit", provenance.excerpt)
        assertEquals(1234L, provenance.capturedAt)
        assertEquals(emptyList(), result.rejected)
    }

    @Test
    fun duplicatePersonIsKeptOnceAndLaterCopyRejected() {
        val person = """{"kind":"person","fields":{"fullName":"Ana Lopez"},"excerpt":"Ana Lopez"}"""
        val result = run("""{"items":[$person,$person],"relations":[]}""")
        assertEquals(1, result.typed.size)
        assertEquals(listOf("duplicate: person 'Ana Lopez'"), result.rejected)
    }

    @Test
    fun relationToDroppedItemIsRejectedWithReason() {
        val result = run(
            """
            {"items":[
              {"kind":"company","fields":{"domain":"ghost.io"},"excerpt":"Ghost Labs"},
              {"kind":"person","fields":{"fullName":"Ana Lopez"},"excerpt":"Ana Lopez"}
            ],"relations":[{"from":0,"to":1,"type":"works_at","excerpt":"Ana Lopez"}]}
            """.trimIndent(),
        )
        assertEquals(listOf("person"), result.typed.map { it.kind })
        assertEquals(emptyList(), result.relations)
        assertTrue(result.rejected.contains("relation works_at refers to a dropped item"), "${result.rejected}")
    }

    @Test
    fun invalidTypedPayloadIsRejected() {
        val result = run("""{"items":[{"kind":"person","fields":{"role":"CTO"},"excerpt":"CTO at Acme Corp"}],"relations":[]}""")
        assertEquals(emptyList(), result.typed)
        assertEquals(listOf("invalid person"), result.rejected)
    }

    @Test
    fun modelErrorYieldsEmptyResultWithOneReason() {
        val failing = EntityExtraction(PlanModel { _, _ -> throw IllegalStateException("offline") }, { 1L })
        val result = failing.extract(TEXT, "chat_message", "msg-1")
        assertEquals(emptyList(), result.typed)
        assertEquals(emptyList(), result.entities)
        assertEquals(emptyList(), result.relations)
        assertEquals(listOf("model error: offline"), result.rejected)
    }

    @Test
    fun proseWithoutJsonYieldsEmptyResultWithOneReason() {
        val result = run("I found Ana Lopez, CTO at Acme Corp.")
        assertEquals(emptyList(), result.typed)
        assertEquals(listOf("model returned no JSON object"), result.rejected)
    }

    @Test
    fun unbalancedJsonYieldsEmptyResultWithOneReason() {
        val result = run("""{"items":[{"kind":"person",""")
        assertEquals(emptyList(), result.typed)
        assertEquals(listOf("model returned no JSON object"), result.rejected)
    }

    @Test
    fun itemsBeyondTheCapAreRejectedAsTooMany() {
        val items = (1..31).joinToString(",") {
            """{"kind":"entity","type":"place","fields":{"title":"Place $it"},"excerpt":"Web Summit"}"""
        }
        val result = run("""{"items":[$items],"relations":[]}""")
        assertEquals(30, result.entities.size)
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().startsWith("too many items"), "${result.rejected}")
    }

    private companion object {
        const val TEXT = "Ana Lopez, CTO at Acme Corp, presented at Web Summit."
    }
}
