package app.memo.platform.android.voice

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero VAD (pinned `vad-silero-v5`) over the sherpa-onnx API, loaded from the app's private
 * dir — never from assets or shared storage.
 *
 * Blocking; call from a background thread, never the main thread. Feed mono PCM frames with
 * [acceptWaveform]; between frames ask [hasSegment] and take [nextSegment]. [reset] drops the
 * buffered audio for reuse after a barge-in; [flush] forces out a trailing partial segment.
 * [close] releases the native model; the instance is unusable afterwards.
 *
 * Tunables other than the model path and rate stay at the AAR's own defaults until device
 * verification (M2.A3); only [numThreads] is pinned to 1 for battery discipline.
 *
 * @param modelFile the installed `silero_vad_v5.onnx`.
 * @param sampleRate analysis rate; Silero v5 runs at 16 kHz.
 */
class SherpaVad(
    modelFile: File,
    private val sampleRate: Int = 16_000,
) : AutoCloseable {

    private val vad: Vad = Vad(
        assetManager = null, // null selects file mode (bytecode-verified in the AAR)
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = modelFile.absolutePath),
            sampleRate = sampleRate,
            numThreads = 1,
        ),
    )

    /** Feeds one frame of mono PCM samples at [sampleRate]. */
    fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    /** True while a complete speech segment waits in [nextSegment]. */
    fun hasSegment(): Boolean = !vad.empty()

    /** Pops and returns the next complete speech segment, or null. */
    fun nextSegment(): SpeechSegment? {
        if (vad.empty()) return null
        val segment = vad.front()
        vad.pop()
        return segment
    }

    /** True while the engine currently hears speech. */
    val isSpeechDetected: Boolean
        get() = vad.isSpeechDetected()

    /** Drops buffered audio and segment state; for reuse after a barge-in. */
    fun reset() = vad.reset()

    /** Forces out a trailing partial segment at the end of an utterance. */
    fun flush() = vad.flush()

    override fun close() = vad.release()
}
