package app.memo.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.MemoMotion
import app.memo.ui.motion.ambientLoopSpec
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/**
 * A determinate ring. [progress] must be a real measured fraction (for example downloaded bytes
 * over total bytes); it is clamped to 0..1 and never invented. The arc eases to each new value.
 */
@Composable
fun ProgressRing(
    progress: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    strokeWidth: Dp = 3.dp,
    color: Color = MemoTheme.colors.accent,
) {
    val reduced = rememberReduceMotion()
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = motionSpec(MotionFamily.SMOOTH, reduced),
        label = "memo-progress-ring",
    )
    val track = MemoTheme.colors.outline
    Canvas(
        modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f, 0)
            },
    ) {
        drawRing(track = track, color = color, strokeWidth = strokeWidth.toPx(), startAngle = -90f, sweep = 360f * animated)
    }
}

/**
 * A ring for work with no measurable fraction. It turns while running. With reduce-motion on,
 * it shows a static three-quarter arc. Use it only where no real progress exists.
 */
@Composable
fun IndeterminateProgressRing(
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    strokeWidth: Dp = 3.dp,
    color: Color = MemoTheme.colors.accent,
) {
    val track = MemoTheme.colors.outline
    if (rememberReduceMotion()) {
        Canvas(
            modifier
                .size(size)
                .semantics { this.contentDescription = contentDescription },
        ) {
            drawRing(track = track, color = color, strokeWidth = strokeWidth.toPx(), startAngle = -90f, sweep = 270f)
        }
    } else {
        SpinningRing(modifier, size, strokeWidth, color, track, contentDescription)
    }
}

@Composable
private fun SpinningRing(
    modifier: Modifier,
    size: Dp,
    strokeWidth: Dp,
    color: Color,
    track: Color,
    description: String,
) {
    val transition = rememberInfiniteTransition(label = "memo-spin")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = ambientLoopSpec(MemoMotion.SPIN_MS, RepeatMode.Restart),
        label = "memo-spin-turn",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = description },
    ) {
        drawRing(track = track, color = color, strokeWidth = strokeWidth.toPx(), startAngle = turn - 90f, sweep = 270f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRing(
    track: Color,
    color: Color,
    strokeWidth: Float,
    startAngle: Float,
    sweep: Float,
) {
    val inset = strokeWidth / 2f
    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
    val topLeft = Offset(inset, inset)
    drawArc(
        color = track,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth),
    )
    if (sweep > 0f) {
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}
