package app.memo.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.ui.theme.MemoTheme

/** Pure maths for [Waveform]. No Compose types, so it is testable on the JVM. */
object WaveformMath {

    /**
     * Max-pools [levels] into [bucketCount] buckets, oldest left, newest right. Max keeps
     * speech peaks visible when many meter frames land in one bar. Values are clamped to
     * 0..1 and non-finite values are dropped first. Empty input gives [bucketCount] zeros.
     */
    fun buckets(levels: List<Float>, bucketCount: Int): List<Float> {
        if (bucketCount <= 0) return emptyList()
        val finite = levels.filter { it.isFinite() }.map { it.coerceIn(0f, 1f) }
        if (finite.isEmpty()) return List(bucketCount) { 0f }
        val count = finite.size
        return List(bucketCount) { bucket ->
            val start = (bucket.toLong() * count / bucketCount).toInt()
            val endExclusive = ((bucket + 1).toLong() * count / bucketCount).toInt()
                .coerceAtLeast(start + 1)
                .coerceAtMost(count)
            var max = 0f
            for (index in start until endExclusive) {
                val value = finite[index]
                if (value > max) max = value
            }
            max
        }
    }
}

/**
 * Mirrored level bars of a recording, centred on a horizontal axis. [levels] are expected
 * in capture order, one entry per meter frame, each already in 0..1. Empty input draws a
 * flat idle line.
 */
@Composable
fun Waveform(
    levels: List<Float>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    barCount: Int = 28,
    color: Color = MemoTheme.colors.accent,
) {
    val bucketed = remember(levels, barCount) { WaveformMath.buckets(levels, barCount) }
    Canvas(
        modifier.semantics { this.contentDescription = contentDescription },
    ) {
        val slot = size.width / barCount
        if (slot <= 0f) return@Canvas
        val barWidth = slot * 0.5f
        val minBarHeight = 2.dp.toPx()
        val mid = size.height / 2f
        val radius = CornerRadius(barWidth / 2f)
        bucketed.forEachIndexed { index, level ->
            val height = (size.height * level).coerceAtLeast(minBarHeight)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, mid - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}
