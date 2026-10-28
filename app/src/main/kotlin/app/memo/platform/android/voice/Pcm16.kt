package app.memo.platform.android.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 mono helpers shared by the voice features. Pure maths; testable on the JVM. */
object Pcm16 {

    /**
     * Encodes [pcm] as a canonical 44-byte-header WAV file: RIFF/WAVE, fmt (PCM, mono,
     * [sampleRate] Hz, 16 bit) and a data chunk in little-endian PCM16.
     */
    fun encodeWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val out = ByteArray(HEADER_BYTES + pcm.size * 2)
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(HEADER_BYTES - 8 + pcm.size * 2)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // fmt chunk length for PCM
        header.putShort(1.toShort()) // PCM format tag
        header.putShort(1.toShort()) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate: one channel, two bytes per sample
        header.putShort(2.toShort()) // block align
        header.putShort(16.toShort()) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size * 2)
        ByteBuffer.wrap(out, HEADER_BYTES, pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(pcm)
        return out
    }

    /** PCM16 samples to floats in -1..1, the format the sherpa engines accept. */
    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    private const val HEADER_BYTES = 44
}
