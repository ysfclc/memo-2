package app.memo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/** Pure maths for [Sparkline]. No Compose types, so it is testable on the JVM. */
object SparklineMath {

    /**
     * Scales [values] to 0..1 by min and max. Non-finite values are dropped first.
     * An empty series gives an empty list. A single point gives [0.5]. A flat series gives 0.5 for every point.
     */
    fun normalize(values: List<Float>): List<Float> {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return emptyList()
        if (finite.size == 1) return listOf(0.5f)
        val min = finite.min()
        val max = finite.max()
        val range = max - min
        if (range <= 0f) return finite.map { 0.5f }
        return finite.map { (it - min) / range }
    }

    /** Horizontal position of point [index] in a series of [count] points, from 0 to 1. */
    fun xFraction(index: Int, count: Int): Float =
        if (count <= 1) 0.5f else index.toFloat() / (count - 1)
}

/**
 * A small line of a series. It draws in from left to right over [MemoMotion] LONG when the
 * values change. Pass real measured values only. Empty input draws nothing.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = MemoTheme.colors.link,
) {
    val reduced = rememberReduceMotion()
    val drawn = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(values) {
        drawn.snapTo(0f)
        drawn.animateTo(1f, motionSpec(MotionFamily.LONG, reduced))
    }
    val points = remember(values) { SparklineMath.normalize(values) }
    Canvas(
        modifier
            .size(width = 96.dp, height = 32.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val count = points.size
        if (count == 0) return@Canvas
        val stroke = 2.dp.toPx()
        if (count == 1) {
            drawCircle(color = color, radius = stroke, center = Offset(size.width / 2f, size.height / 2f))
            return@Canvas
        }
        val pad = stroke
        fun at(i: Int): Offset = Offset(
            x = pad + SparklineMath.xFraction(i, count) * (size.width - 2 * pad),
            y = pad + (1f - points[i]) * (size.height - 2 * pad),
        )
        val reach = drawn.value * (count - 1)
        val whole = reach.toInt()
        val path = Path()
        path.moveTo(at(0).x, at(0).y)
        for (i in 1..whole.coerceAtMost(count - 1)) {
            path.lineTo(at(i).x, at(i).y)
        }
        if (whole < count - 1) {
            val from = at(whole)
            val to = at(whole + 1)
            val t = reach - whole
            path.lineTo(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }
        drawPath(path = path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}
