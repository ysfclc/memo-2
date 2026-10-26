package app.memo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.memo.ui.theme.MemoTheme
import app.memo.ui.theme.ambientShadow
import app.memo.ui.theme.spotShadow
import app.memo.ui.theme.surfaceAt

/**
 * A raised surface for rows and cards. [elevation] is 0..3 and maps to [MemoTheme.elevation]
 * and the tonal step in [MemoTheme.colors]. Pass [onClick] to make the whole card a button.
 */
@Composable
fun MemoCard(
    modifier: Modifier = Modifier,
    elevation: Int = 1,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MemoTheme.colors
    val level = MemoTheme.elevation.of(elevation)
    val shape = MemoTheme.shapes.md
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = level.elevation,
                shape = shape,
                ambientColor = colors.ambientShadow(level),
                spotColor = colors.spotShadow(level),
            )
            .clip(shape)
            .background(colors.surfaceAt(elevation))
            .then(clickable)
            .padding(16.dp),
        content = content,
    )
}
