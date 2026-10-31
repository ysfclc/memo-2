package app.memo.runtime

import app.memo.platform.android.voice.AudioCapture

/**
 * The narrow ports [VoiceMessageRecorder] consumes. They mirror [LoopPorts]: pure fakes can
 * drive the recorder on the JVM; the device wiring is in `SherpaLoopAdapters.kt`.
 */

/** Microphone source for [VoiceMessageRecorder]. [stop] must be idempotent. */
interface RecorderCapture {
    fun start(listener: AudioCapture.Listener): AudioCapture.Start
    fun stop()
}

/** Whole-utterance transcription. Blocking; called on the recorder's executor only. */
interface RecorderTranscriber {
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}

/** Storage for finished voice messages. Returns the clip ref the WAV was stored under. */
interface RecorderStore {
    fun save(wav: ByteArray): String
}

/**
 * A [RecorderTranscriber] that builds the real engine on first use. The load runs on the
 * recorder's executor thread (the only transcribe caller), and `by lazy` does not cache
 * failures: when [load] throws - usually because the pinned Whisper model is absent - the
 * failure propagates and the next transcribe attempt reloads, so installing the model
 * heals transcription without rebuilding or dropping the recorder.
 */
class LazyRecorderTranscriber(private val load: () -> RecorderTranscriber) : RecorderTranscriber {

    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String =
        delegate.transcribe(samples, sampleRate)
}
