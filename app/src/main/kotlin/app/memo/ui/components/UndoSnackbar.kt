package app.memo.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.memo.ui.theme.MemoTheme

/** Shows [message] with an Undo action. [onUndo] runs only if the user taps Undo before it expires. */
suspend fun SnackbarHostState.showUndo(
    message: String,
    onUndo: () -> Unit,
    actionLabel: String = "Undo",
) {
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = false,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

/** The host for [showUndo] and other snackbars, styled with the Memo palette. */
@Composable
fun UndoSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = MemoTheme.colors
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(16.dp),
            shape = MemoTheme.shapes.sm,
            containerColor = colors.ink,
            contentColor = colors.canvas,
            actionColor = colors.accentSoft,
        )
    }
}
