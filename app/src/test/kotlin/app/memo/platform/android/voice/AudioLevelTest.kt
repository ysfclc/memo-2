package app.memo.platform.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioLevelTest {

    @Test
    fun silenceReadsZero() {
        val silence = ShortArray(320)
        assertEquals(0f, AudioLevel.rms(silence))
        assertEquals(0f, AudioLevel.peak(silence))
    }

    @Test
    fun fullScaleNegativeReadsOne() {
        val full = ShortArray(320) { Short.MIN_VALUE }
        assertEquals(1f, AudioLevel.rms(full))
        assertEquals(1f, AudioLevel.peak(full))
    }

    @Test
    fun fullScalePositiveIsJustBelowOne() {
        val full = ShortArray(320) { Short.MAX_VALUE }
        assertTrue(AudioLevel.rms(full) in 0.999f..1f)
    }

    @Test
    fun halfScaleSquareWaveReadsHalf() {
        val half = ShortArray(160) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(0.5f, AudioLevel.rms(half), 0.0001f)
        assertEquals(0.5f, AudioLevel.peak(half), 0.0001f)
    }

    @Test
    fun emptyFrameReadsZero() {
        assertEquals(0f, AudioLevel.rms(ShortArray(0)))
        assertEquals(0f, AudioLevel.peak(ShortArray(0)))
        assertEquals(0f, AudioLevel.rmsFloat(FloatArray(0)))
    }

    @Test
    fun clippedInputNeverExceedsOne() {
        val clipped = ShortArray(64) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val level = AudioLevel.rms(clipped)
        assertTrue(level <= 1f)
        assertTrue(level > 0.99f)
    }

    @Test
    fun rangeOverloadMeasuresOnlyThatSlice() {
        val frame = ShortArray(10)
        for (i in 4 until 8) frame[i] = Short.MIN_VALUE
        assertEquals(0f, AudioLevel.rms(frame, 0, 4))
        assertEquals(1f, AudioLevel.rms(frame, 4, 4))
    }

    @Test
    fun rangeOutsideBufferIsRejected() {
        assertFailsWith<IllegalArgumentException> { AudioLevel.rms(ShortArray(4), 2, 4) }
    }

    @Test
    fun floatRmsClampsAndMeasures() {
        assertEquals(1f, AudioLevel.rmsFloat(floatArrayOf(3f, -3f, 3f)), 0.0001f)
        assertEquals(0.5f, AudioLevel.rmsFloat(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)), 0.0001f)
    }
}
