package app.memo.platform.android.voice

import android.content.Context
import app.memo.core.chat.VoiceClipRefs
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultName
import app.memo.runtime.BargeInEngine
import app.memo.runtime.ListenEngine
import app.memo.runtime.LoopCapture
import app.memo.runtime.LoopPlayback
import app.memo.runtime.RecorderCapture
import app.memo.runtime.RecorderStore
import app.memo.runtime.RecorderTranscriber
import app.memo.runtime.SpeakEngine
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** [RecorderCapture] over [AudioCapture]. */
class SherpaRecorderCapture(context: Context) : RecorderCapture {

    private val capture = AudioCapture(context)

    override fun start(listener: AudioCapture.Listener): AudioCapture.Start = capture.start(listener)

    override fun stop() = capture.stop()
}

/** [RecorderTranscriber] over the pinned offline Whisper engine; executor thread only. */
class SherpaRecorderTranscriber(private val asr: SherpaOfflineAsr) : RecorderTranscriber {

    override fun transcribe(samples: FloatArray, sampleRate: Int): String =
        asr.transcribe(samples, sampleRate)
}

/**
 * [RecorderStore] through the encrypted [Vault]: every WAV is written as a vault entry under
 * a `yyyyMMdd-HHmmss-SSS` timestamp name — the same bare name the chat stores as its clip
 * reference (`voiceClipRef`), so existing refs keep resolving. A same-millisecond collision
 * appends `-1`, `-2`, … to the second save's name.
 *
 * [importLegacy] moves clips saved by the old plaintext store (`filesDir/voice-messages/`)
 * into the vault, then zeroes and deletes each plaintext file; files with invalid names are
 * left untouched. It runs once, before the recorder is built.
 */
class VoiceMessageStore(private val vault: Vault, private val clock: () -> Long = System::currentTimeMillis) :
    RecorderStore {

    override fun save(wav: ByteArray): String {
        val base = TIMESTAMP_FORMAT.format(Date(clock()))
        var name = base
        var suffix = 1
        while (vault.read(name) != null) {
            name = "$base-$suffix"
            suffix++
        }
        vault.write(name, wav)
        return name
    }

    fun importLegacy(dir: File): Int {
        val files = if (dir.isDirectory) dir.listFiles() else null
        var imported = 0
        if (files != null) {
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (!VaultName.PATTERN.matches(name) || !VoiceClipRefs.isFilenameSafe(name)) continue
                try {
                    if (vault.read(name) == null) {
                        vault.write(name, file.readBytes())
                        imported++
                    }
                    // Already in the vault: still destroy the plaintext copy.
                    file.outputStream().use { out -> out.write(ByteArray(file.length().toInt())) }
                    file.delete()
                } catch (_: Exception) {
                    // Leave this one in place; continue with the rest.
                }
            }
        }
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
        return imported
    }

    private companion object {
        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
