package app.memo.ui.artifact

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.memo.ui.components.MemoCard
import app.memo.ui.components.Pill
import app.memo.ui.theme.MemoTheme

/** Shows a filled artifact (approval preview) as a labelled card. */
@Composable
fun ArtifactView(kind: String, json: String, modifier: Modifier = Modifier) {
    val rendered = remember(kind, json) { ArtifactFields.render(kind, json) }
    val colors = MemoTheme.colors
    val type = MemoTheme.typography
    val spacing = MemoTheme.spacing
    MemoCard(modifier) {
        Text(rendered.title, style = type.subtitle, color = colors.ink)
        if (rendered.isFallback) {
            Spacer(Modifier.height(spacing.space2))
            Pill("raw")
        }
        rendered.fields.forEach { field ->
            Spacer(Modifier.height(spacing.space3))
            Text(field.label, style = type.caption, color = colors.inkMuted)
            Text(field.value, style = type.body, color = colors.ink)
        }
    }
}
