package app.memo.platform.android.voice

import android.content.Context
import app.memo.runtime.BargeInEngine
import app.memo.runtime.ListenEngine
import app.memo.runtime.LoopCapture
import app.memo.runtime.LoopPlayback
import app.memo.runtime.SpeakEngine
import com.k2fsa.sherpa.onnx.OnlineStream

/** Device wiring of the [app.memo.runtime.LoopPorts] onto the sherpa engines and platform audio. */

/**
 * [ListenEngine] over [SherpaAsr]. The engine owns the stream (one per conversation lifetime,
 * reset per utterance at the endpoint); only the mic delivery thread touches an instance.
 */
class SherpaListenEngine(private val asr: SherpaAsr) : ListenEngine, AutoCloseable {

    private var stream: OnlineStream? = null

    override fun onFrame(samples: FloatArray): String {
        val s = stream ?: asr.newStream().also { stream = it }
        s.acceptWaveform(samples, SAMPLE_RATE)
        while (asr.isReady(s)) asr.decode(s)
        return asr.partial(s)
    }

    override fun endpoint(): Boolean {
        val s = stream ?: return false
        return asr.isEndpoint(s)
    }

    override fun finalTranscript(): String {
        val s = stream ?: return ""
        val text = asr.partial(s)
        asr.reset(s)
        return text
    }

    /** Releases the stream, then the recognizer. Full teardown — the loop never calls this. */
    override fun close() {
        runCatching { stream?.release() }
        stream = null
        asr.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** [BargeInEngine] over [SherpaVad]. Mic thread only. */
class SherpaBargeInEngine(private val vad: SherpaVad) : BargeInEngine {

    override fun onFrame(samples: FloatArray) = vad.acceptWaveform(samples)

    override val speechDetected: Boolean
        get() = vad.isSpeechDetected

    override fun reset() = vad.reset()
}

/** [SpeakEngine] over [SherpaTts]; the `Boolean`-returning chunk callback is the barge-in hook. */
class SherpaSpeakEngine(private val tts: SherpaTts) : SpeakEngine {

    override val sampleRate: Int
        get() = tts.sampleRate

    override fun generate(text: String, onChunk: (FloatArray) -> Boolean) {
        tts.generate(text, onChunk = SherpaTts.GenerateCallback(onChunk))
    }
}

/** [LoopCapture] over [AudioCapture]. */
class SherpaLoopCapture(context: Context) : LoopCapture {

    private val capture = AudioCapture(context)

    override fun start(listener: AudioCapture.Listener): AudioCapture.Start = capture.start(listener)

    override fun stop() = capture.stop()
}

/** [LoopPlayback] over [AudioPlayback]; [onFocusLost] is the platform focus-loss callback. */
class SherpaLoopPlayback(context: Context, onFocusLost: () -> Unit) : LoopPlayback {

    private val playback = AudioPlayback(context, onFocusLost = onFocusLost)

    override val isOpen: Boolean
        get() = playback.isOpen

    override fun open(sampleRate: Int): AudioPlayback.Open = playback.open(sampleRate)

    override fun write(samples: FloatArray): Int = playback.write(samples)

    override fun stop() = playback.stop()
}
