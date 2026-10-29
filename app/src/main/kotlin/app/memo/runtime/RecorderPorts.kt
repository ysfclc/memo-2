package app.memo.runtime

import app.memo.platform.android.voice.AudioCapture
import java.io.File

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

/** Storage for finished voice messages. Returns the file the WAV was written to. */
interface RecorderStore {
    fun save(wav: ByteArray): File
}
