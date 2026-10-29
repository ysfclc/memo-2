package app.memo.platform.android.voice

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loudness of 16-bit PCM frames, normalised to 0.0..1.0. Pure: no Android types.
 *
 * Full scale is 32768 (the magnitude of the most negative sample), so a frame of full-scale
 * samples reads 1.0 and nothing reads above it. An empty frame reads 0.0.
 */
object AudioLevel {

    private const val FULL_SCALE = 32768.0

    /** Root-mean-square level of `frame[offset until offset + length]`, in 0.0..1.0. */
    fun rms(frame: ShortArray, offset: Int = 0, length: Int = frame.size - offset): Float {
        requireRange(frame, offset, length)
        if (length == 0) return 0f
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            val s = frame[i].toDouble()
            sumSquares += s * s
        }
        val normalised = sqrt(sumSquares / length) / FULL_SCALE
        return normalised.coerceIn(0.0, 1.0).toFloat()
    }

    /** Largest absolute sample of `frame[offset until offset + length]`, in 0.0..1.0. */
    fun peak(frame: ShortArray, offset: Int = 0, length: Int = frame.size - offset): Float {
        requireRange(frame, offset, length)
        var max = 0
        for (i in offset until offset + length) {
            val magnitude = abs(frame[i].toInt())
            if (magnitude > max) max = magnitude
        }
        return (max / FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
    }

    /** RMS of float samples in -1.0..1.0 (the TTS output format). Values outside are clamped. */
    fun rmsFloat(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): Float {
        requireRange(samples, offset, length)
        if (length == 0) return 0f
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].coerceIn(-1f, 1f).toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / length).coerceIn(0.0, 1.0).toFloat()
    }

    private fun requireRange(size: Int, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= size) {
            "range $offset+$length is outside a buffer of $size"
        }
    }

    private fun requireRange(frame: ShortArray, offset: Int, length: Int) = requireRange(frame.size, offset, length)

    private fun requireRange(frame: FloatArray, offset: Int, length: Int) = requireRange(frame.size, offset, length)
}
