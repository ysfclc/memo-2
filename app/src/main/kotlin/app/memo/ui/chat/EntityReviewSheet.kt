package app.memo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.memo.core.agent.ExtractionResult
import app.memo.core.agent.KeptItem
import app.memo.core.domain.Entity
import app.memo.ui.artifact.ArtifactFields
import app.memo.ui.artifact.ArtifactView
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.MemoCard
import app.memo.ui.components.MemoSheet
import app.memo.ui.components.Pill
import app.memo.ui.theme.MemoTheme

private val MAX_LIST_HEIGHT = 420.dp

/**
 * Review sheet for the candidates found in one chat message (ENT.4b, D-059). Every kept item and
 * every link starts ticked; nothing is saved until the user presses Save selected, which hands the
 * ticked indexes to [onSave]. Dismissing the sheet saves nothing.
 */
@Composable
fun EntityReviewSheet(
    result: ExtractionResult,
    saving: Boolean,
    onSave: (selected: Set<Int>, relations: Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val type = MemoTheme.typography
    val colors = MemoTheme.colors
    val spacing = MemoTheme.spacing
    var selected by remember(result) { mutableStateOf(result.kept.indices.toSet()) }
    var relations by remember(result) { mutableStateOf(result.relations.indices.toSet()) }
    val titles = remember(result) { result.kept.map(::titleOf) }

    MemoSheet(onDismiss = onDismiss) {
        Text("Found in this message", style = type.title, color = colors.ink)
        Text(
            "Tick what to save. Nothing is saved until you press Save selected.",
            style = type.caption,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(spacing.space3))
        Column(
            Modifier.fillMaxWidth().heightIn(max = MAX_LIST_HEIGHT).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            if (result.kept.isEmpty()) {
                Text("Nothing worth saving was found in this message.", style = type.body, color = colors.inkMuted)
            }
            result.kept.forEachIndexed { index, item ->
                ReviewRow(
                    checked = index in selected,
                    enabled = !saving,
                    onCheckedChange = { selected = selected.toggled(index, it) },
                ) {
                    when (item) {
                        is KeptItem.Typed -> ArtifactView(item.candidate.kind, item.candidate.payloadJson)
                        is KeptItem.Proposed -> EntityCard(item.entity)
                    }
                    Text("“${excerptOf(item)}”", style = type.caption, color = colors.inkMuted)
                }
            }
            if (result.relations.isNotEmpty()) {
                Text("Links", style = type.subtitle, color = colors.ink)
            }
            result.relations.forEachIndexed { index, relation ->
                ReviewRow(
                    checked = index in relations,
                    enabled = !saving,
                    onCheckedChange = { relations = relations.toggled(index, it) },
                ) {
                    Text(
                        "${titles.getOrElse(relation.fromIndex) { "" }} → ${relation.relationType} → " +
                            titles.getOrElse(relation.toIndex) { "" },
                        style = type.body,
                        color = colors.ink,
                    )
                    Text("“${relation.provenance.excerpt}”", style = type.caption, color = colors.inkMuted)
                }
            }
            if (result.rejected.isNotEmpty()) {
                Text(
                    "${result.rejected.size} dropped as unsupported or invalid.",
                    style = type.caption,
                    color = colors.inkMuted,
                )
            }
        }
        Spacer(Modifier.height(spacing.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            MemoButton(
                onClick = { onSave(selected, relations) },
                enabled = !saving && selected.isNotEmpty(),
            ) {
                Text(if (saving) "Saving…" else "Save selected")
            }
            MemoButton(onClick = onDismiss, enabled = !saving, style = MemoButtonStyle.QUIET) {
                Text("Cancel")
            }
        }
    }
}

/** One ticked row: a checkbox beside its content. */
@Composable
private fun ReviewRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = MemoTheme.colors.accent),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2),
            content = content,
        )
    }
}

/** An open-type entity: its type, title and every stated property. */
@Composable
private fun EntityCard(entity: Entity) {
    val type = MemoTheme.typography
    val colors = MemoTheme.colors
    MemoCard {
        Pill(entity.type)
        Text(entity.title, style = type.subtitle, color = colors.ink)
        entity.properties.forEach { (key, value) ->
            Text("$key: $value", style = type.bodySmall, color = colors.ink)
        }
    }
}

private fun Set<Int>.toggled(index: Int, on: Boolean): Set<Int> = if (on) this + index else this - index

private fun titleOf(item: KeptItem): String = when (item) {
    is KeptItem.Typed -> ArtifactFields.render(item.candidate.kind, item.candidate.payloadJson).title
    is KeptItem.Proposed -> item.entity.title
}

private fun excerptOf(item: KeptItem): String = when (item) {
    is KeptItem.Typed -> item.candidate.provenance.excerpt
    is KeptItem.Proposed -> item.entity.provenance.firstOrNull()?.excerpt.orEmpty()
}
