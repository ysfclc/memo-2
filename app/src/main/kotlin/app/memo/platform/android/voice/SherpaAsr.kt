package app.memo.platform.android.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Streaming Zipformer ASR (pinned `asr-zipformer-en-20m-mobile`) over the sherpa-onnx API,
 * loaded from the app's private dir — never from assets or shared storage. Delivers partial
 * transcripts while the user speaks and endpoint detection to close a turn.
 *
 * Blocking; call from a background thread, never the main thread. Typical loop: [newStream]
 * once per conversation, feed 20 ms frames with `stream.acceptWaveform`, then repeatedly
 * [decode] while [isReady], read [partial] for the running transcript, and on [isEndpoint]
 * take the final text and [reset] the stream for the next turn.
 *
 * Endpoints use the AAR's own three default rules. Tunables other than the model files stay at
 * the AAR defaults; [numThreads] pinned to 2 (streaming ASR shares the CPU with the LLM).
 *
 * @param modelDir the installed `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile`
 *   folder (int8 encoder and joiner, fp32 decoder).
 */
class SherpaAsr(
    modelDir: File,
) : AutoCloseable {

    private val recognizer: OnlineRecognizer = OnlineRecognizer(
        assetManager = null, // null selects file mode
        config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(),
        ),
    )

    /** Opens one decode stream. One per conversation turn; [close] it when the turn ends. */
    fun newStream(): OnlineStream = recognizer.createStream()

    /** True when enough audio is buffered for a decode step. */
    fun isReady(stream: OnlineStream): Boolean = recognizer.isReady(stream)

    /** Runs one decode step; call repeatedly while [isReady]. */
    fun decode(stream: OnlineStream) = recognizer.decode(stream)

    /** The transcript so far, thinking text excluded by the model itself. */
    fun partial(stream: OnlineStream): String = recognizer.getResult(stream).text

    /** True when a silence/length endpoint rule fired for this stream. */
    fun isEndpoint(stream: OnlineStream): Boolean = recognizer.isEndpoint(stream)

    /** Clears the stream for the next turn after an endpoint (partial text resets too). */
    fun reset(stream: OnlineStream) = recognizer.reset(stream)

    /** Signals no more audio will arrive, so trailing text is flushed into the partial. */
    fun inputFinished(stream: OnlineStream) = stream.inputFinished()

    override fun close() = recognizer.release()
}
