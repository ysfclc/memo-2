package app.memo.runtime

import app.memo.platform.android.voice.AudioCapture
import app.memo.platform.android.voice.AudioPlayback

/**
 * The narrow ports [VoiceLoop] consumes. They exist so the loop's threading and turn-taking can
 * be tested on the JVM with fakes; the device wiring is [VoiceLoop.Companion.forDevice].
 */

/** Microphone audio source for [VoiceLoop]. */
interface LoopCapture {
    fun start(listener: AudioCapture.Listener): AudioCapture.Start
    fun stop()
}

/** Speaker output for [VoiceLoop]: streaming float PCM at the TTS sample rate. */
interface LoopPlayback {
    val isOpen: Boolean
    fun open(sampleRate: Int): AudioPlayback.Open

    /** Blocking write; returns the frames accepted, negative when stopped or failed. */
    fun write(samples: FloatArray): Int
    fun stop()
}

/**
 * Streaming ASR the loop feeds; the implementation owns its stream. Only the mic thread touches
 * an instance (sherpa native state has no locking).
 */
interface ListenEngine {
    /** Feeds one frame, decodes while ready, returns the current partial transcript. */
    fun onFrame(samples: FloatArray): String

    /** True when the endpoint rules fire (the user's turn is complete). */
    fun endpoint(): Boolean

    /** Captures the final transcript and resets for the next utterance. */
    fun finalTranscript(): String
}

/** Speech detector for barge-in. Only the mic thread touches an instance. */
interface BargeInEngine {
    fun onFrame(samples: FloatArray)
    val speechDetected: Boolean
    fun reset()
}

/** Text-to-speech; [onChunk] returns true to stop the rest of the synthesis (barge-in). */
interface SpeakEngine {
    val sampleRate: Int
    fun generate(text: String, onChunk: (FloatArray) -> Boolean)
}
