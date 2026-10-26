package app.memo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepStatus
import app.memo.ui.motion.MemoMotion
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.ambientLoopSpec
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/** What a step looks like on the rail. Pure, so the mapping is testable on the JVM. */
enum class StepVisual { PENDING, RUNNING, DONE, FAILED, WAITING, SKIPPED }

/**
 * Maps a step's status, within its run, to the visual state.
 * - A step waiting on a person, or needing review or interrupted, shows as WAITING.
 * - A step that was never started is SKIPPED once the run is terminal (COMPLETED, FAILED or CANCELLED).
 * - A step that was running or waiting when the run was CANCELLED is SKIPPED.
 */
fun stepVisual(status: StepStatus, runStatus: RunStatus): StepVisual {
    val base = when (status) {
        StepStatus.PENDING -> StepVisual.PENDING
        StepStatus.RUNNING -> StepVisual.RUNNING
        StepStatus.WAITING_USER, StepStatus.NEEDS_REVIEW, StepStatus.INTERRUPTED -> StepVisual.WAITING
        StepStatus.COMPLETED -> StepVisual.DONE
        StepStatus.FAILED -> StepVisual.FAILED
        StepStatus.SKIPPED -> StepVisual.SKIPPED
    }
    val terminal = runStatus == RunStatus.COMPLETED || runStatus == RunStatus.FAILED || runStatus == RunStatus.CANCELLED
    return when {
        base == StepVisual.PENDING && terminal -> StepVisual.SKIPPED
        base == StepVisual.RUNNING && runStatus == RunStatus.CANCELLED -> StepVisual.SKIPPED
        base == StepVisual.WAITING && runStatus == RunStatus.CANCELLED -> StepVisual.SKIPPED
        else -> base
    }
}

/** One row of the rail: a step title and its visual state. */
data class StepRailItem(val title: String, val visual: StepVisual)

/**
 * A vertical rail of steps. The connecting line fills when a step completes. A running node
 * pulses, a completed node morphs into a check, a failed node shakes once in amber, and a
 * waiting node breathes a halo. Under reduce-motion each of these holds still in its end state.
 */
@Composable
fun StepRail(items: List<StepRailItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            StepRailRow(item = item, isLast = index == items.lastIndex)
        }
    }
}

@Composable
private fun StepRailRow(item: StepRailItem, isLast: Boolean) {
    val colors = MemoTheme.colors
    val reduced = rememberReduceMotion()
    val done = item.visual == StepVisual.DONE
    val lineFill = remember { Animatable(0f) }
    LaunchedEffect(done) {
        lineFill.animateTo(if (done) 1f else 0f, motionSpec(MotionFamily.LONG, reduced))
    }
    val label = visualLabel(item.visual)
    Row(
        Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { contentDescription = "${item.title}, $label" },
    ) {
        Box(Modifier.width(28.dp).fillMaxSize()) {
            StepNode(visual = item.visual, reduced = reduced, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            if (!isLast) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2f
                    val top = NODE_TOP_TRACK.dp.toPx()
                    drawLine(color = colors.outline, start = Offset(x, top), end = Offset(x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    val filledTo = top + (size.height - top) * lineFill.value
                    if (lineFill.value > 0f) {
                        drawLine(color = colors.success, start = Offset(x, top), end = Offset(x, filledTo), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
        Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
            Text(
                item.title,
                style = MemoTheme.typography.subtitle,
                color = if (item.visual == StepVisual.SKIPPED) colors.inkFaint else colors.ink,
            )
            Text(label, style = MemoTheme.typography.caption, color = colors.inkMuted)
        }
    }
}

private const val NODE_TOP_TRACK = 28

private fun visualLabel(visual: StepVisual): String = when (visual) {
    StepVisual.PENDING -> "pending"
    StepVisual.RUNNING -> "running"
    StepVisual.DONE -> "done"
    StepVisual.FAILED -> "failed"
    StepVisual.WAITING -> "waiting for you"
    StepVisual.SKIPPED -> "skipped"
}

@Composable
private fun StepNode(visual: StepVisual, reduced: Boolean, modifier: Modifier) {
    Box(modifier.size(24.dp)) {
        when (visual) {
            StepVisual.RUNNING -> if (reduced) SolidNode(MemoTheme.colors.accent) else PulsingNode()
            StepVisual.WAITING -> if (reduced) HaloNode(0f) else BreathingHalo()
            StepVisual.FAILED -> ShakingNode(reduced)
            StepVisual.DONE -> MorphingCheck()
            StepVisual.PENDING -> OutlineNode(MemoTheme.colors.outlineStrong)
            StepVisual.SKIPPED -> OutlineNode(MemoTheme.colors.inkFaint)
        }
    }
}

@Composable
private fun SolidNode(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color = color, radius = size.minDimension / 2f * NODE_SCALE)
    }
}

@Composable
private fun OutlineNode(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color = color, radius = size.minDimension / 2f * NODE_SCALE - 1.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun PulsingNode() {
    val colors = MemoTheme.colors
    val transition = rememberInfiniteTransition(label = "memo-step-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = ambientLoopSpec(MemoMotion.PULSE_MS, RepeatMode.Reverse),
        label = "memo-step-pulse-scale",
    )
    Canvas(Modifier.fillMaxSize()) {
        val base = size.minDimension / 2f * NODE_SCALE
        drawCircle(color = colors.accent.copy(alpha = 0.18f), radius = base * pulse * 1.45f)
        drawCircle(color = colors.accent, radius = base * pulse)
    }
}

@Composable
private fun BreathingHalo() {
    val transition = rememberInfiniteTransition(label = "memo-step-halo")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = ambientLoopSpec(MemoMotion.ORB_BREATH_MS, RepeatMode.Restart),
        label = "memo-step-halo-phase",
    )
    HaloNode(phase)
}

/** A solid amber node with a ring that grows and fades with [phase] (0..1). */
@Composable
private fun HaloNode(phase: Float) {
    val colors = MemoTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        val base = size.minDimension / 2f * NODE_SCALE
        val ringRadius = base * (1f + 0.9f * phase)
        drawCircle(color = colors.attention.copy(alpha = 0.35f * (1f - phase) + 0.1f), radius = ringRadius)
        drawCircle(color = colors.attention, radius = base)
    }
}

@Composable
private fun ShakingNode(reduced: Boolean) {
    val colors = MemoTheme.colors
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            for (offset in SHAKE_FRAMES) {
                shake.animateTo(offset, motionSpec(MotionFamily.MICRO, false))
            }
        }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = shake.value.dp.toPx() },
    ) {
        val base = size.minDimension / 2f * NODE_SCALE
        drawCircle(color = colors.attention, radius = base)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(color = colors.canvas, start = Offset(cx, cy - base * 0.45f), end = Offset(cx, cy + base * 0.1f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = colors.canvas, radius = 1.dp.toPx(), center = Offset(cx, cy + base * 0.5f))
    }
}

@Composable
private fun MorphingCheck() {
    val colors = MemoTheme.colors
    val reduced = rememberReduceMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, motionSpec(MotionFamily.SHORT, reduced))
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color = colors.success, radius = size.minDimension / 2f * NODE_SCALE)
        drawCheck(progress.value, colors.onAccent, 2.dp.toPx())
    }
}

/** Draws the check in two strokes; [progress] 0..1 draws it from the left leg to the tip. */
private fun DrawScope.drawCheck(progress: Float, color: Color, stroke: Float) {
    val a = Offset(size.width * 0.28f, size.height * 0.52f)
    val b = Offset(size.width * 0.44f, size.height * 0.68f)
    val c = Offset(size.width * 0.74f, size.height * 0.36f)
    val leg1 = (progress * 2f).coerceIn(0f, 1f)
    val leg2 = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
    drawLine(color = color, start = a, end = Offset(a.x + (b.x - a.x) * leg1, a.y + (b.y - a.y) * leg1), strokeWidth = stroke, cap = StrokeCap.Round)
    if (leg2 > 0f) {
        drawLine(color = color, start = b, end = Offset(b.x + (c.x - b.x) * leg2, b.y + (c.y - b.y) * leg2), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

private const val NODE_SCALE = 0.42f

/** A small left-right shake, in dp, that settles to zero. */
private val SHAKE_FRAMES = listOf(-4f, 4f, -3f, 3f, -1.5f, 0f)
