package app.memo.runtime

import app.memo.core.agent.ApprovalSummary
import app.memo.core.agent.ExtractionResult
import app.memo.core.agent.KeptItem
import app.memo.core.plan.PlanModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EntityServiceTest {

    private val now = 1_700_000_000_000L

    private fun service(
        model: PlanModel?,
        apply: (ExtractionResult, Set<Int>, Set<Int>) -> ApprovalSummary = { _, _, _ -> error("approve not expected") },
    ) = EntityService(modelFor = { model }, applyOnRuntime = apply, clock = { now })

    @Test
    fun noModelIsReportedWithoutExtracting() {
        assertEquals(Extracted.NoModel, service(null).extract("Ada Lovelace spoke.", "msg-1"))
    }

    @Test
    fun throwingModelIsReportedAsFailed() {
        val model = PlanModel { _, _ -> throw IllegalStateException("boom") }
        val failed = assertIs<Extracted.Failed>(service(model).extract("Ada Lovelace spoke.", "msg-1"))
        assertContains(failed.message, "boom")
    }

    @Test
    fun realExcerptBecomesOneKeptPerson() {
        val output = """{"items":[{"kind":"person","fields":{"fullName":"Ada Lovelace"},"excerpt":"Ada Lovelace"}],"relations":[]}"""
        val ready = assertIs<Extracted.Ready>(
            service(PlanModel { _, _ -> output }).extract("I met Ada Lovelace at the summit.", "msg-1"),
        )
        assertEquals(1, ready.result.kept.size)
        val typed = assertIs<KeptItem.Typed>(ready.result.kept.single())
        assertEquals("person", typed.candidate.kind)
        assertEquals("chat_message", typed.candidate.provenance.sourceKind)
        assertEquals("msg-1", typed.candidate.provenance.sourceId)
        assertEquals("Ada Lovelace", typed.candidate.provenance.excerpt)
    }

    @Test
    fun approveDelegatesExactlyTheGivenSelection() {
        val calls = mutableListOf<Triple<ExtractionResult, Set<Int>, Set<Int>>>()
        val summary = ApprovalSummary(saved = 1, reused = 0, updated = 0, linked = 0, skipped = emptyList())
        val result = ExtractionResult(kept = emptyList(), relations = emptyList(), rejected = emptyList())
        val service = service(null) { r, selected, relations ->
            calls += Triple(r, selected, relations)
            summary
        }

        assertEquals(summary, service.approve(result, setOf(0, 2), setOf(1)))
        assertEquals(listOf(Triple(result, setOf(0, 2), setOf(1))), calls)
    }

    @Test
    fun summaryLineCountsAndSkippedReasons() {
        val line = service(null).summaryLine(
            ApprovalSummary(
                saved = 2,
                reused = 1,
                updated = 0,
                linked = 1,
                skipped = listOf("item 3: invalid person", "relation 0: endpoint not saved"),
            ),
        )
        assertEquals(
            "Saved 2, reused 1, linked 1; skipped: item 3: invalid person; relation 0: endpoint not saved",
            line,
        )
    }

    @Test
    fun summaryLineShowsUpdatesAndOmitsEmptySkipped() {
        val line = service(null).summaryLine(ApprovalSummary(saved = 0, reused = 0, updated = 3, linked = 0, skipped = emptyList()))
        assertEquals("Saved 0, reused 0, updated 3, linked 0", line)
    }
}
