package app.memo.platform.android.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Keyword spotter (pinned `kws-zipformer-gigaspeech-3-3m-mobile`) over the sherpa-onnx API,
 * loaded from the app's private dir — never from assets or shared storage. Always-on wake-word
 * front end ("Hey Memo" in M2.C2); the pinned archive's `keywords.txt` carries the keywords
 * until a custom file replaces it.
 *
 * Blocking; call from a background thread, never the main thread. Typical loop: [newStream]
 * once, feed 20 ms frames with `stream.acceptWaveform`, then repeatedly [decode] while
 * [isReady] and take [keyword] when the spotter fires. The same stream keeps listening across
 * firings; [reset] clears it after acting on a keyword.
 *
 * Tunables other than the model files stay at the AAR defaults; [numThreads] pinned to 1 (the
 * spotter runs next to the LLM and the conversation ASR).
 *
 * @param modelDir the installed `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile`
 *   folder (int8 encoder and joiner, fp32 decoder).
 * @param keywordsFile the keywords list to spot; defaults to the archive's own `keywords.txt`.
 */
class SherpaKws(
    modelDir: File,
    keywordsFile: File = File(modelDir, "keywords.txt"),
) : AutoCloseable {

    private val spotter: KeywordSpotter = KeywordSpotter(
        assetManager = null, // null selects file mode
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 1,
            ),
            keywordsFile = keywordsFile.absolutePath,
        ),
    )

    /** Opens one spotter stream; keep it for the whole listening session. */
    fun newStream(): OnlineStream = spotter.createStream()

    /** True when enough audio is buffered for a decode step. */
    fun isReady(stream: OnlineStream): Boolean = spotter.isReady(stream)

    /** Runs one decode step; call repeatedly while [isReady]. */
    fun decode(stream: OnlineStream) = spotter.decode(stream)

    /** The last spotted keyword, or null since the previous decode round. */
    fun keyword(stream: OnlineStream): String? {
        val result = spotter.getResult(stream)
        return result.keyword.ifEmpty { null }
    }

    /** Clears the stream's spotter state after a keyword was acted on. */
    fun reset(stream: OnlineStream) = spotter.reset(stream)

    override fun close() = spotter.release()
}
