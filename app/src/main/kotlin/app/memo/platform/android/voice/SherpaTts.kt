package app.memo.platform.android.voice

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Offline TTS over the sherpa-onnx API, loaded from the app's private dir — never from assets
 * or shared storage. Kokoro is the primary voice; Piper (VITS) is the fallback the plan names.
 *
 * Blocking; call from a background thread, never the main thread. [generate] returns the whole
 * audio and also streams chunks through [GenerateCallback] as they are synthesized, so the
 * conversation loop can start playback before the sentence finishes and stop the engine on a
 * barge-in. [close] releases the native model; the instance is unusable afterwards.
 *
 * Tunables other than the model files stay at the AAR defaults; [numThreads] pinned to 1
 * (TTS competes with ASR and the LLM for the CPU).
 *
 * @param tts the constructed engine (use [kokoro] or [piper]).
 */
class SherpaTts private constructor(
    private val tts: OfflineTts,
) : AutoCloseable {

    /** Callback receives synthesized PCM chunks; return true to stop generation (barge-in). */
    fun interface GenerateCallback {
        fun onChunk(samples: FloatArray): Boolean
    }

    /** The engine's output sample rate, for playback. */
    val sampleRate: Int
        get() = tts.sampleRate()

    /** How many voices the model offers (Kokoro en v0_19 has one). */
    val numSpeakers: Int
        get() = tts.numSpeakers()

    /**
     * Synthesizes [text] at [speed] with voice [speakerId]. With [onChunk], each chunk is
     * delivered as produced and returning true stops the rest of the generation. The returned
     * [GeneratedAudio] also carries the full audio and can [GeneratedAudio.save] a WAV.
     */
    fun generate(
        text: String,
        speakerId: Int = 0,
        speed: Float = 1.0f,
        onChunk: GenerateCallback? = null,
    ): GeneratedAudio = if (onChunk == null) {
        tts.generate(text, speakerId, speed)
    } else {
        tts.generateWithCallback(text, speakerId, speed, ChunkBridge(onChunk))
    }

    override fun close() = tts.release()

    /**
     * The callback handed to sherpa-onnx's JNI. Its native code looks the method up by the EXACT
     * signature `invoke([F)Ljava/lang/Integer;`. A Kotlin lambda here compiles to an invokedynamic
     * lambda that D8 turns into a synthetic class with only the erased `invoke(Object)Object`, so
     * the lookup failed and the app aborted (device crash 2026-10-31, D-060). A real class keeps
     * the specialised `invoke(FloatArray): Int` (boxed to Integer). Pinned by SherpaTtsCallbackTest.
     */
    internal class ChunkBridge(private val onChunk: GenerateCallback) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onChunk.onChunk(samples)) 1 else 0
    }

    companion object {

        /**
         * The pinned Kokoro en v0_19 voice (`tts-kokoro-en-v0-19`): its `model.onnx`,
         * `tokens.txt`, `voices.bin` and `espeak-ng-data/` must be installed in [modelDir].
         */
        fun kokoro(modelDir: File): SherpaTts = SherpaTts(
            OfflineTts(
                assetManager = null, // null selects file mode
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(modelDir, "model.onnx").absolutePath,
                            voices = File(modelDir, "voices.bin").absolutePath,
                            tokens = File(modelDir, "tokens.txt").absolutePath,
                            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                            lang = "en-us",
                        ),
                        numThreads = 1,
                    ),
                ),
            ),
        )

        /**
         * A Piper (VITS) voice archive: the voice's `.onnx` and `tokens.txt` must be installed
         * in [modelDir]. Fails fast when the archive layout is not the expected single model.
         */
        fun piper(modelDir: File): SherpaTts {
            val model = modelDir.listFiles { f -> f.isFile && f.extension == "onnx" }?.singleOrNull()
                ?: error("piper voice dir must contain exactly one .onnx model: $modelDir")
            return SherpaTts(
                OfflineTts(
                    assetManager = null, // null selects file mode
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = model.absolutePath,
                                tokens = File(modelDir, "tokens.txt").absolutePath,
                            ),
                            numThreads = 1,
                        ),
                    ),
                ),
            )
        }
    }
}
