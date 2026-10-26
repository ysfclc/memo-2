package app.memo.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * A bottom sheet with a spring rise and a fading scrim. Scrim tap, the system back gesture and
 * the predictive back preview all dismiss it through the same exit animation. [onDismiss] runs
 * only after the exit has finished, so the caller removes the sheet from composition then.
 */
@Composable
fun MemoSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = rememberReduceMotion()
    var open by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { open = true }

    val reveal by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = motionSpec(MotionFamily.GENTLE, reduced),
        label = "memo-sheet-reveal",
        finishedListener = { value -> if (value == 0f && dismissing) onDismiss() },
    )

    fun requestDismiss() {
        dismissing = true
        open = false
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
            try {
                progress.collect { dragProgress = it.progress }
                dragProgress = 0f
                requestDismiss()
            } catch (cancelled: CancellationException) {
                dragProgress = 0f
                throw cancelled
            }
        }
        val colors = MemoTheme.colors
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal * (1f - 0.3f * dragProgress) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(role = Role.Button, onClick = ::requestDismiss),
            )
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = (1f - reveal) * 480.dp.toPx() + dragProgress * 24.dp.toPx()
                    }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.surfaceRaised)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                content = content,
            )
        }
    }
}
