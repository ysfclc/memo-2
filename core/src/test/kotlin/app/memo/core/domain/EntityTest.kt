package app.memo.core.domain

import app.memo.core.codec.SynCodec
import app.memo.core.registry.NaturalKeys
import app.memo.core.registry.Registry
import app.memo.core.registry.Schemes
import app.memo.core.registry.Workspace
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityTest {

    private val t0 = 1_700_000_000_000L

    private val excerpt = "We met at Web Summit in Lisbon, “great booth”"

    private val prov = Provenance(
        sourceKind = "chat_message",
        sourceId = "msg-42",
        excerpt = excerpt,
        capturedAt = t0,
    )

    private fun entity(
        type: String = "place",
        title: String = "Web Summit",
        properties: Map<String, String> = mapOf("city" to "Lisbon"),
    ) = Entity(
        type = type,
        title = title,
        properties = properties,
        reviewStatus = ReviewStatus.APPROVED,
        provenance = listOf(prov),
        createdAt = t0,
        updatedAt = t0 + 1,
        version = 2,
    )

    private fun relation(
        fromKey: String = "en:aaaa",
        toKey: String = "en:bbbb",
        relationType: String = "met_at",
    ) = Relation(
        fromKey = fromKey,
        toKey = toKey,
        relationType = relationType,
        reviewStatus = ReviewStatus.PROPOSED,
        provenance = listOf(prov),
        createdAt = t0,
        updatedAt = t0 + 2,
        version = 3,
    )

    // ---- 1. JSON round-trip --------------------------------------------

    @Test
    fun entityRoundTripsExactly() {
        val e = entity()
        assertEquals(e, Entity.fromJson(e.toJson()))
    }

    @Test
    fun relationRoundTripsExactly() {
        val r = relation()
        assertEquals(r, Relation.fromJson(r.toJson()))
    }

    @Test
    fun provenanceExcerptSurvivesRoundTripVerbatim() {
        assertEquals(prov, Provenance.fromJson(prov.toJson()))
    }

    // ---- 2. Natural keys -----------------------------------------------

    @Test
    fun sameTypeAndTitleIgnoringCaseAndSpacingShareAKey() {
        val a = NaturalKeys.entity("place", "Web Summit")
        val b = NaturalKeys.entity("Place", "  web   summit ")
        assertTrue(a.startsWith("en:"))
        assertEquals(a, b)
    }

    @Test
    fun differentTypeWithSameTitleGetsDifferentKey() {
        assertNotEquals(
            NaturalKeys.entity("place", "Web Summit"),
            NaturalKeys.entity("event", "Web Summit"),
        )
    }

    @Test
    fun blankTypeOrTitleHasNoKey() {
        assertEquals("", NaturalKeys.entity("", "Web Summit"))
        assertEquals("", NaturalKeys.entity("place", "   "))
    }

    @Test
    fun relationDedupesByFromToAndType() {
        val k = NaturalKeys.relation("en:aaaa", "en:bbbb", "met_at")
        assertTrue(k.startsWith("rl:"))
        assertEquals(k, NaturalKeys.relation("en:aaaa", "en:bbbb", " MET_AT "))
        assertNotEquals(k, NaturalKeys.relation("en:bbbb", "en:aaaa", "met_at"))
        assertNotEquals(k, NaturalKeys.relation("en:aaaa", "en:bbbb", "works_at"))
    }

    // ---- 3. Registry: reuse, then versioned bump ------------------------

    @Test
    fun upsertSameEntityTwiceCreatesThenReuses() {
        val reg = Registry("entities", Schemes.Entities)
        assertIs<Registry.Put.Created<Entity>>(reg.upsert(entity(), now = t0))
        assertIs<Registry.Put.Reused<Entity>>(reg.upsert(entity(), now = t0 + 10))
        assertEquals(1, reg.size)
    }

    @Test
    fun changedPropertiesBumpVersionToTwo() {
        val reg = Registry("entities", Schemes.Entities)
        reg.upsert(entity(), now = t0)
        val bumped = reg.upsert(entity(properties = mapOf("city" to "Porto")), now = t0 + 10)
        val current = assertIs<Registry.Put.Bumped<Entity>>(bumped).current
        assertEquals(2, current.version)
        assertEquals("Porto", current.properties["city"])
        assertEquals(t0, current.createdAt)
        assertEquals(t0 + 10, current.updatedAt)
    }

    @Test
    fun relationUpsertDedupesAndBumps() {
        val reg = Registry("relations", Schemes.Relations)
        assertIs<Registry.Put.Created<Relation>>(reg.upsert(relation(), now = t0))
        assertIs<Registry.Put.Reused<Relation>>(reg.upsert(relation(), now = t0 + 1))
        val bumped = reg.upsert(relation().copy(reviewStatus = ReviewStatus.APPROVED), now = t0 + 2)
        assertEquals(2, assertIs<Registry.Put.Bumped<Relation>>(bumped).current.version)
        assertEquals(1, reg.size)
    }

    // ---- 4. Invalid records --------------------------------------------

    @Test
    fun badTypeShapeIsInvalid() {
        assertTrue(entity(type = "Place").isInvalid())
        assertTrue(entity(type = "9place").isInvalid())
        assertTrue(entity(type = "place-x").isInvalid())
        assertTrue(entity(type = "a".repeat(33)).isInvalid())
        assertNull(Entity.fromJson(entity(type = "Place").toJson()))
    }

    @Test
    fun blankOrOversizeTitleIsInvalid() {
        assertTrue(entity(title = "  ").isInvalid())
        assertTrue(entity(title = "x".repeat(201)).isInvalid())
        assertNull(Entity.fromJson(entity(title = "").toJson()))
    }

    @Test
    fun twentyOneOrBadPropertiesAreInvalid() {
        val twentyOne = (1..21).associate { "k$it" to "v" }
        assertTrue(entity(properties = twentyOne).isInvalid())
        val twenty = (1..20).associate { "k$it" to "v" }
        assertTrue(!entity(properties = twenty).isInvalid())
        assertTrue(entity(properties = mapOf("Bad Key" to "v")).isInvalid())
        assertTrue(entity(properties = mapOf("city" to "x".repeat(501))).isInvalid())
    }

    @Test
    fun oversizeExcerptIsInvalidAndDropsTheEntityOnParse() {
        val big = prov.copy(excerpt = "x".repeat(501))
        assertTrue(big.isInvalid())
        val e = entity().copy(provenance = listOf(big))
        assertTrue(e.isInvalid())
        assertNull(Entity.fromJson(e.toJson()))
    }

    @Test
    fun tooManyProvenanceEntriesAreInvalid() {
        assertTrue(entity().copy(provenance = List(11) { prov }).isInvalid())
        assertTrue(!entity().copy(provenance = List(10) { prov }).isInvalid())
    }

    @Test
    fun relationWithSameEndpointsOrBadTypeIsInvalid() {
        val same = relation(fromKey = "en:aaaa", toKey = "en:aaaa")
        assertTrue(same.isInvalid())
        assertNull(Relation.fromJson(same.toJson()))
        assertTrue(relation(relationType = "Works At").isInvalid())
        assertTrue(relation(fromKey = "").isInvalid())
        assertTrue(relation(toKey = "k".repeat(129)).isInvalid())
    }

    @Test
    fun garbageDecodesToNull() {
        val garbage = JSONObject("{\"unrelated\":true}")
        assertNull(Entity.fromJson(garbage))
        assertNull(Relation.fromJson(garbage))
    }

    // ---- 5. Workspace ---------------------------------------------------

    @Test
    fun workspaceHasEighteenRegistriesEndingWithEntityRegistries() {
        val kinds = Workspace().all().map { it.kind }
        assertEquals(18, kinds.size)
        assertEquals(18, kinds.toSet().size)
        assertEquals(listOf("entities", "relations"), kinds.takeLast(2))
    }

    @Test
    fun envelopeWithoutEntityArraysStillParsesWithEmptyRegistries() {
        val ws = Workspace()
        ws.entities.upsert(entity(), now = t0)
        val envelope = JSONObject(ws.serialize(exportedAt = t0))
        envelope.getJSONObject("registries").remove("entities")
        envelope.getJSONObject("registries").remove("relations")
        envelope.getJSONObject("schemas").remove("entities")
        envelope.getJSONObject("schemas").remove("relations")

        val parsed = assertIs<SynCodec.ParseOutcome.Ok>(Workspace.parse(envelope.toString()))
        assertEquals(0, parsed.workspace.entities.size)
        assertEquals(0, parsed.workspace.relations.size)
    }
}
