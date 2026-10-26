package app.memo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/** A static label chip. [emphasis] uses the mint soft fill for completed or active states. */
@Composable
fun Pill(text: String, emphasis: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MemoTheme.colors
    Box(
        modifier
            .clip(MemoTheme.shapes.pill)
            .background(if (emphasis) colors.accentSoft else colors.outline)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MemoTheme.typography.caption, color = colors.ink)
    }
}

/** A selectable chip. The fill fades on a snappy spring when the selection changes. */
@Composable
fun MemoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MemoTheme.colors
    val reduced = rememberReduceMotion()
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.outline,
        animationSpec = motionSpec(MotionFamily.SNAPPY, reduced),
        label = "memo-chip-fill",
    )
    Box(
        modifier
            .clip(MemoTheme.shapes.pill)
            .background(fill)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MemoTheme.typography.label, color = colors.ink)
    }
}
