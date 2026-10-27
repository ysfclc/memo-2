package app.memo.platform.android.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Offline Whisper ASR (pinned `asr-whisper-base`) for voice messages, loaded from the app's
 * private dir — never from assets or shared storage.
 *
 * Blocking; call from a background thread, never the main thread. [transcribe] takes one whole
 * utterance: the recorder stops before transcription starts, so there is no streaming here
 * (streaming conversation ASR is [SherpaAsr]).
 *
 * Tunables other than the model files stay at the AAR defaults; [numThreads] is pinned to 2 for
 * a voice-message-sized job.
 *
 * @param modelDir the installed `sherpa-onnx-whisper-base` folder.
 * @param language ISO code to force, or "" for auto-detect (the pinned model is multilingual).
 */
class SherpaOfflineAsr(
    modelDir: File,
    language: String = "",
) : AutoCloseable {

    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        assetManager = null, // null selects file mode
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "base-encoder.onnx").absolutePath,
                    decoder = File(modelDir, "base-decoder.onnx").absolutePath,
                    language = language,
                ),
                tokens = File(modelDir, "base-tokens.txt").absolutePath,
                numThreads = 2,
            ),
        ),
    )

    /**
     * Transcribes one utterance and returns the recognized text. The engine result's language,
     * emotion and event fields are deliberately not surfaced here.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
