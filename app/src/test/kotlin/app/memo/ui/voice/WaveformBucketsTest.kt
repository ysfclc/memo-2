package app.memo.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [WaveformMath.buckets] pinned on the JVM: max-pool downsampling, the empty-input zero fill,
 * the degenerate bucket counts, clamping, and dropping non-finite meter frames before pooling.
 */
class WaveformBucketsTest {

    @Test
    fun emptyLevelsGiveBucketCountZerosAndDegenerateCountsGiveEmptyLists() {
        assertEquals(listOf(0f, 0f, 0f, 0f), WaveformMath.buckets(emptyList(), 4))
        assertEquals(emptyList(), WaveformMath.buckets(emptyList(), 0))
        assertEquals(emptyList(), WaveformMath.buckets(emptyList(), -1))
        // Degenerate counts behave the same even with input present.
        assertEquals(emptyList(), WaveformMath.buckets(listOf(0.5f), 0))
    }

    @Test
    fun eightLevelsPoolIntoFourBucketsAsTheMaxOfEachPair() {
        val buckets = WaveformMath.buckets(
            levels = listOf(0.1f, 0.5f, 0.2f, 0.3f, 0.9f, 0.0f, 0.4f, 0.4f),
            bucketCount = 4,
        )

        assertEquals(listOf(0.5f, 0.3f, 0.9f, 0.4f), buckets)
    }

    @Test
    fun fewerLevelsThanBucketsRepeatTheInputsAcrossTheSpan() {
        // 2 levels into 4 buckets: each level owns two adjacent buckets; every value is an input.
        val buckets = WaveformMath.buckets(levels = listOf(0.3f, 0.7f), bucketCount = 4)

        assertEquals(4, buckets.size)
        assertEquals(listOf(0.3f, 0.3f, 0.7f, 0.7f), buckets)
        buckets.forEach { level ->
            assertEquals(true, level == 0.3f || level == 0.7f, "unexpected bucket value $level")
        }
    }

    @Test
    fun valuesAreClampedToUnitRangeAndNonFiniteFramesAreDroppedFirst() {
        // -1 clamps to 0, 2 clamps to 1; NaN and +Inf are dropped before pooling, so the
        // two buckets each cover exactly one clamped frame: [0f, 1f].
        val buckets = WaveformMath.buckets(
            levels = listOf(-1f, 2f, Float.NaN, Float.POSITIVE_INFINITY),
            bucketCount = 2,
        )

        assertEquals(listOf(0f, 1f), buckets)
        buckets.forEach { level ->
            assertEquals(true, level in 0f..1f, "bucket value outside 0..1: $level")
        }
    }
}
