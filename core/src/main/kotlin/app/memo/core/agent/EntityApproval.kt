package app.memo.core.agent

import app.memo.core.domain.Entity
import app.memo.core.domain.Provenance
import app.memo.core.domain.Relation
import app.memo.core.domain.ReviewStatus
import app.memo.core.registry.ArtifactStore
import app.memo.core.registry.Registry
import app.memo.core.registry.Schemes
import app.memo.core.registry.Workspace

/**
 * What one approval did. [saved] created new records, [reused] found identical
 * ones, [updated] bumped existing ones with new evidence, [linked] wrote
 * relations. [skipped] holds one plain reason per selected item or relation
 * that could not be saved.
 */
data class ApprovalSummary(
    val saved: Int,
    val reused: Int,
    val updated: Int,
    val linked: Int,
    val skipped: List<String>,
)

/**
 * Saves exactly the items the user ticked in an [ExtractionResult] (ENT.4a,
 * pure core, D-059). Typed records go through [ArtifactStore]; generic entities
 * and relations go to the entity registries and are stored APPROVED. Reuse
 * follows the registries' natural keys, and repeated evidence accumulates in
 * provenance (capped at [MAX_PROVENANCE]). Never throws.
 *
 * Typed records keep no provenance excerpt: their schemas have no field for it,
 * so only the typed record itself is saved. Relations are saved only when both
 * endpoints were saved or reused in this approval.
 */
object EntityApproval {

    /** Same cap as [Entity] and [Relation] provenance. */
    const val MAX_PROVENANCE: Int = 10

    /**
     * [selected] holds indexes into [ExtractionResult.kept]; [selectedRelations]
     * holds indexes into [ExtractionResult.relations]. A relation is saved only
     * when it is selected and both endpoints were saved or reused here.
     */
    fun apply(
        workspace: Workspace,
        result: ExtractionResult,
        selected: Set<Int>,
        selectedRelations: Set<Int>,
        now: Long,
    ): ApprovalSummary {
        val counts = Counts()
        val skipped = mutableListOf<String>()

        selected.sorted().filter { it !in result.kept.indices }.forEach { skipped += "item $it: no such item" }
        val keys = arrayOfNulls<String>(result.kept.size)
        result.kept.forEachIndexed { index, item ->
            if (index !in selected) return@forEachIndexed
            keys[index] = when (item) {
                is KeptItem.Typed -> saveTyped(workspace, item.candidate, index, now, counts, skipped)
                is KeptItem.Proposed -> saveEntity(workspace, item.entity, index, now, counts, skipped)
            }
        }

        selectedRelations.sorted().forEach { j ->
            val relation = result.relations.getOrNull(j)
            if (relation == null) {
                skipped += "relation $j: no such relation"
                return@forEach
            }
            val fromKey = keys.getOrNull(relation.fromIndex)
            val toKey = keys.getOrNull(relation.toIndex)
            if (fromKey == null || toKey == null) {
                skipped += "relation $j: endpoint not saved"
                return@forEach
            }
            saveRelation(workspace, relation, fromKey, toKey, j, now, counts, skipped)
        }

        return ApprovalSummary(
            saved = counts.saved,
            reused = counts.reused,
            updated = counts.updated,
            linked = counts.linked,
            skipped = skipped,
        )
    }

    private fun saveTyped(
        workspace: Workspace,
        candidate: TypedCandidate,
        index: Int,
        now: Long,
        counts: Counts,
        skipped: MutableList<String>,
    ): String? {
        val key = ArtifactStore.keyOf(candidate.kind, candidate.payloadJson)
        if (key == null) {
            skipped += "item $index: invalid ${candidate.kind}"
            return null
        }
        when (val outcome = ArtifactStore.persistResult(workspace, candidate.kind, candidate.payloadJson, now)) {
            is ArtifactStore.Result.Saved -> counts.record(outcome.outcome)
            is ArtifactStore.Result.Rejected -> {
                skipped += "item $index: ${outcome.reason}"
                return null
            }
        }
        return key
    }

    private fun saveEntity(
        workspace: Workspace,
        entity: Entity,
        index: Int,
        now: Long,
        counts: Counts,
        skipped: MutableList<String>,
    ): String? {
        val key = Schemes.Entities.id(entity)
        if (key.isBlank()) {
            skipped += "item $index: invalid entity '${entity.title}'"
            return null
        }
        val existing = workspace.entities.byId(key)
        val merged = mergeProvenance(existing?.provenance.orEmpty(), entity.provenance)
        // Reuse-first (AGENTS.md rule 2): earlier approved facts survive; a newer value wins per key.
        val properties = existing?.properties.orEmpty() + entity.properties
        val put = workspace.entities.upsert(
            entity.copy(reviewStatus = ReviewStatus.APPROVED, provenance = merged, properties = properties),
            now,
        )
        when (put) {
            is Registry.Put.Created -> counts.record("created")
            is Registry.Put.Reused -> counts.record("reused")
            is Registry.Put.Bumped -> counts.record("bumped")
            is Registry.Put.Failed -> {
                skipped += "item $index: ${put.reason}"
                return null
            }
        }
        return key
    }

    private fun saveRelation(
        workspace: Workspace,
        relation: RelationCandidate,
        fromKey: String,
        toKey: String,
        index: Int,
        now: Long,
        counts: Counts,
        skipped: MutableList<String>,
    ) {
        if (fromKey == toKey) {
            skipped += "relation $index: links an item to itself"
            return
        }
        val base = Relation(
            fromKey = fromKey,
            toKey = toKey,
            relationType = relation.relationType,
            reviewStatus = ReviewStatus.APPROVED,
        )
        val key = Schemes.Relations.id(base)
        val merged = mergeProvenance(workspace.relations.byId(key)?.provenance.orEmpty(), listOf(relation.provenance))
        val candidate = base.copy(provenance = merged)
        if (candidate.isInvalid()) {
            skipped += "relation $index: invalid ${relation.relationType} relation"
            return
        }
        when (workspace.relations.upsert(candidate, now)) {
            is Registry.Put.Created -> counts.linked++
            is Registry.Put.Reused -> counts.linked++
            is Registry.Put.Bumped -> counts.linked++
            is Registry.Put.Failed -> skipped += "relation $index: could not save"
        }
    }

    /** Existing evidence first, then new evidence; duplicates by source and excerpt; capped. */
    private fun mergeProvenance(existing: List<Provenance>, incoming: List<Provenance>): List<Provenance> {
        val merged = LinkedHashMap<String, Provenance>()
        (existing + incoming).forEach { p ->
            merged.putIfAbsent("${p.sourceKind}\u0000${p.sourceId}\u0000${p.excerpt}", p)
        }
        return merged.values.take(MAX_PROVENANCE)
    }

    private class Counts {
        var saved = 0
        var reused = 0
        var updated = 0
        var linked = 0

        fun record(outcome: String) {
            when (outcome) {
                "created" -> saved++
                "reused" -> reused++
                "bumped" -> updated++
            }
        }
    }
}
