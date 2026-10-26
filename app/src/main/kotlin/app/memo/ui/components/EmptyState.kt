package app.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.memo.ui.theme.MemoTheme

/**
 * A centred explanation for a screen or list with nothing in it yet.
 * The icon is decorative: the title already says what the state is.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MemoTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MemoTheme.typography.title, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MemoTheme.typography.bodySmall, color = colors.inkMuted)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
