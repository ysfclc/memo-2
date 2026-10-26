package app.memo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparklineMathTest {

    @Test
    fun normalisesToZeroToOne() {
        assertEquals(listOf(0f, 0.5f, 1f), SparklineMath.normalize(listOf(10f, 15f, 20f)))
        assertEquals(listOf(1f, 0f), SparklineMath.normalize(listOf(5f, 2f)))
    }

    @Test
    fun negativeRangesNormaliseCorrectly() {
        assertEquals(listOf(1f, 0.5f, 0f), SparklineMath.normalize(listOf(-2f, -3.5f, -5f)))
    }

    @Test
    fun emptySeriesGivesNoPoints() {
        assertTrue(SparklineMath.normalize(emptyList()).isEmpty())
    }

    @Test
    fun singlePointSitsInTheMiddle() {
        assertEquals(listOf(0.5f), SparklineMath.normalize(listOf(42f)))
    }

    @Test
    fun flatSeriesSitsInTheMiddle() {
        assertEquals(listOf(0.5f, 0.5f, 0.5f), SparklineMath.normalize(listOf(4f, 4f, 4f)))
    }

    @Test
    fun nonFiniteValuesAreDropped() {
        assertEquals(listOf(0f, 1f), SparklineMath.normalize(listOf(Float.NaN, 1f, Float.POSITIVE_INFINITY, 3f)))
        assertTrue(SparklineMath.normalize(listOf(Float.NaN)).isEmpty())
    }

    @Test
    fun xFractionSpreadsPointsAcrossTheWidth() {
        assertEquals(0f, SparklineMath.xFraction(0, 5))
        assertEquals(0.5f, SparklineMath.xFraction(2, 5))
        assertEquals(1f, SparklineMath.xFraction(4, 5))
        assertEquals(0.5f, SparklineMath.xFraction(0, 1))
    }
}
