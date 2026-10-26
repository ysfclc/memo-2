package app.memo.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MemoMotion
import app.memo.ui.motion.ambientLoopSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/**
 * A placeholder block shown while content loads. A soft band sweeps across it once every
 * [MemoMotion.SHIMMER_MS]. With reduce-motion on, it stays as a flat block.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    shape: Shape = MemoTheme.shapes.sm,
    description: String = "Loading",
) {
    val colors = MemoTheme.colors
    val base = colors.surfaceSunken
    val layout = modifier
        .clip(shape)
        .background(base)
        .semantics { contentDescription = description }
    if (rememberReduceMotion()) {
        Box(layout)
    } else {
        ShimmerSkeleton(layout, base, colors.surfaceRaised)
    }
}

/** A row-shaped skeleton: a title line over a shorter body line. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Skeleton(Modifier.fillMaxWidth().height(18.dp))
        Spacer(Modifier.height(8.dp))
        Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
    }
}

@Composable
private fun ShimmerSkeleton(modifier: Modifier, base: Color, highlight: Color) {
    val transition = rememberInfiniteTransition(label = "memo-shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = ambientLoopSpec(MemoMotion.SHIMMER_MS, RepeatMode.Restart),
        label = "memo-shimmer-phase",
    )
    Box(
        modifier.drawBehind {
            val band = size.width * 0.6f
            val x = -band + phase * (size.width + band)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(x, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        },
    )
}
