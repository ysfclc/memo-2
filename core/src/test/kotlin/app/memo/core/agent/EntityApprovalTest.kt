package app.memo.core.agent

import app.memo.core.domain.Entity
import app.memo.core.domain.Provenance
import app.memo.core.domain.ReviewStatus
import app.memo.core.registry.ArtifactStore
import app.memo.core.registry.Schemes
import app.memo.core.registry.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityApprovalTest {

    private val personPayload = """{"fullName":"Ana Lopez","role":"CTO"}"""
    private val companyPayload = """{"domain":"acme.com","displayName":"Acme Corp"}"""

    private fun prov(excerpt: String, source: String = "msg-1") =
        Provenance("chat_message", source, excerpt, 1L)

    private fun personAndCompany(): ExtractionResult = ExtractionResult(
        kept = listOf(
            KeptItem.Typed(TypedCandidate("person", personPayload, prov("Ana Lopez, CTO"))),
            KeptItem.Typed(TypedCandidate("company", companyPayload, prov("Acme Corp"))),
        ),
        relations = listOf(RelationCandidate(0, 1, "works_at", prov("CTO at Acme Corp"))),
        rejected = emptyList(),
    )

    private fun placeEntity(excerpt: String, source: String) = Entity(
        type = "place",
        title = "Web Summit",
        provenance = listOf(prov(excerpt, source)),
    )

    @Test
    fun selectedPersonCompanyAndRelationAreSavedAndLinkedByNaturalKeys() {
        val workspace = Workspace()
        val summary = EntityApproval.apply(workspace, personAndCompany(), setOf(0, 1), setOf(0), now = 10L)

        assertEquals(2, summary.saved)
        assertEquals(1, summary.linked)
        assertEquals(emptyList(), summary.skipped)
        val person = workspace.people.items().single()
        val company = workspace.companies.items().single()
        val relation = workspace.relations.items().single()
        assertEquals(Schemes.People.id(person), relation.fromKey)
        assertEquals(Schemes.Companies.id(company), relation.toKey)
        assertEquals(ArtifactStore.keyOf("person", personPayload), relation.fromKey)
        assertEquals("works_at", relation.relationType)
        assertEquals(ReviewStatus.APPROVED, relation.reviewStatus)
    }

    @Test
    fun unselectedItemsAreNotSavedAndRelationWithUnselectedEndpointIsSkipped() {
        val workspace = Workspace()
        val summary = EntityApproval.apply(workspace, personAndCompany(), setOf(0), setOf(0), now = 10L)

        assertEquals(1, summary.saved)
        assertEquals(0, summary.linked)
        assertEquals(listOf("relation 0: endpoint not saved"), summary.skipped)
        assertEquals(1, workspace.people.size)
        assertEquals(0, workspace.companies.size)
        assertEquals(0, workspace.relations.size)
    }

    @Test
    fun reapplyingTheSameSelectionReusesEverything() {
        val workspace = Workspace()
        EntityApproval.apply(workspace, personAndCompany(), setOf(0, 1), setOf(0), now = 10L)
        val again = EntityApproval.apply(workspace, personAndCompany(), setOf(0, 1), setOf(0), now = 10L)

        assertEquals(2, again.reused)
        assertEquals(0, again.saved)
        assertEquals(emptyList(), again.skipped)
        assertEquals(1, workspace.people.size)
        assertEquals(1, workspace.companies.size)
        assertEquals(1, workspace.relations.size)
    }

    @Test
    fun genericPlaceSavedTwiceKeepsOneEntityWithBothExcerpts() {
        val workspace = Workspace()
        val first = ExtractionResult(listOf(KeptItem.Proposed(placeEntity("at Web Summit", "msg-1"))), emptyList(), emptyList())
        val second = ExtractionResult(listOf(KeptItem.Proposed(placeEntity("Web Summit in May", "msg-2"))), emptyList(), emptyList())

        val firstSummary = EntityApproval.apply(workspace, first, setOf(0), emptySet(), now = 10L)
        val secondSummary = EntityApproval.apply(workspace, second, setOf(0), emptySet(), now = 20L)

        assertEquals(1, firstSummary.saved)
        assertEquals(1, secondSummary.updated)
        val entity = workspace.entities.items().single()
        assertEquals(ReviewStatus.APPROVED, entity.reviewStatus)
        assertEquals(
            listOf("at Web Summit", "Web Summit in May"),
            entity.provenance.map { it.excerpt },
        )
    }

    @Test
    fun reApprovalMergesPropertiesAndNewerValuesWin() {
        val workspace = Workspace()
        val first = placeEntity("at Web Summit", "msg-1").copy(properties = mapOf("city" to "Lisbon", "month" to "May"))
        val second = placeEntity("Web Summit in May", "msg-2").copy(properties = mapOf("month" to "November"))

        EntityApproval.apply(workspace, ExtractionResult(listOf(KeptItem.Proposed(first)), emptyList(), emptyList()), setOf(0), emptySet(), now = 10L)
        EntityApproval.apply(workspace, ExtractionResult(listOf(KeptItem.Proposed(second)), emptyList(), emptyList()), setOf(0), emptySet(), now = 20L)

        val entity = workspace.entities.items().single()
        assertEquals("Lisbon", entity.properties["city"], "an earlier approved fact must survive re-approval")
        assertEquals("November", entity.properties["month"], "the newer value wins on the same key")
    }

    @Test
    fun invalidTypedPayloadIsSkippedWithReasonAndOthersStillSaved() {
        val workspace = Workspace()
        val result = ExtractionResult(
            kept = listOf(
                KeptItem.Typed(TypedCandidate("person", """{"role":"CTO"}""", prov("CTO"))),
                KeptItem.Proposed(placeEntity("at Web Summit", "msg-1")),
            ),
            relations = emptyList(),
            rejected = emptyList(),
        )
        val summary = EntityApproval.apply(workspace, result, setOf(0, 1), emptySet(), now = 10L)

        assertEquals(1, summary.saved)
        assertEquals(1, summary.skipped.size)
        assertTrue(summary.skipped.single().contains("invalid person"), "${summary.skipped}")
        assertEquals(0, workspace.people.size)
        assertEquals(1, workspace.entities.size)
    }

    @Test
    fun unknownIndexesAreSkippedWithoutThrowing() {
        val workspace = Workspace()
        val summary = EntityApproval.apply(workspace, personAndCompany(), setOf(7), setOf(3), now = 10L)

        assertEquals(0, summary.saved)
        assertEquals(listOf("item 7: no such item", "relation 3: no such relation"), summary.skipped)
        assertNull(ArtifactStore.keyOf("person", "not json"))
    }
}
