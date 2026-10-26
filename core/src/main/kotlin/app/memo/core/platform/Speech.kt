package app.memo.core.platform

/** Voice listing entry for [SpeechOut.voices]. [locale] is a BCP 47 tag such as `en-US`. */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val locale: String,
) {
    init {
        require(id.isNotBlank()) { "voice id must not be blank" }
        require(displayName.isNotBlank()) { "voice name must not be blank" }
        require(LOCALE_PATTERN.matches(locale)) { "locale must be a BCP 47 tag: $locale" }
    }

    private companion object {
        val LOCALE_PATTERN = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}

/**
 * Receives streaming recognition results from [SpeechIn.start].
 *
 * [onPartial] is a hypothesis that may still change. [onFinal] is the settled
 * text of one utterance. [onLevel] reports input loudness as RMS in 0.0..1.0
 * and may be called often. Callbacks run on a recogniser-owned thread and must
 * not block.
 */
interface SpeechListener {
    /** An unstable hypothesis for the current utterance. */
    fun onPartial(text: String)

    /** The final text of one utterance. */
    fun onFinal(text: String)

    /** Current input level, RMS in 0.0..1.0. */
    fun onLevel(rms: Float)

    /** Recognition stopped with an error. [reason] must not contain transcript text. */
    fun onError(reason: String)
}

/**
 * Speech-to-text.
 *
 * - [start] begins streaming recognition from the microphone and reports
 *   through [listener]. Calling [start] while already running is an
 *   [IllegalStateException].
 * - [stop] ends streaming. The listener then receives a final result for
 *   any speech still pending. It is a no-op when not running.
 * - [transcribe] runs offline on a whole utterance. It blocks the caller and
 *   must not be called on the main thread. [pcm16] is mono 16-bit PCM at
 *   [sampleRate] Hz (8000..48000). Audio is never persisted by this call.
 */
interface SpeechIn {
    /** Starts streaming recognition and reports results through [listener]. */
    fun start(listener: SpeechListener)

    /** Stops streaming recognition. No-op when not running. */
    fun stop()

    /** Recognises one complete utterance offline and returns its text. */
    fun transcribe(pcm16: ShortArray, sampleRate: Int): String
}

/**
 * Receives the lifecycle of one [SpeechOut.speak] call. Exactly one of
 * [onDone] or [onError] is called after [onStart], unless the utterance was
 * stopped with [SpeechOut.stop], in which case the listener gets [onError]
 * with reason `stopped`. Callbacks must not block.
 */
interface SpeechOutListener {
    /** Audio playback of the utterance has started. */
    fun onStart()

    /** Playback finished normally. */
    fun onDone()

    /** Playback failed or was stopped. [reason] must not contain the spoken text. */
    fun onError(reason: String)
}

/**
 * Text-to-speech.
 *
 * - [speak] queues one utterance. [speed] is a multiplier in 0.25..4.0.
 *   [voiceId] must be an id from [voices], or null for the default voice.
 * - Only one utterance plays at a time. A new [speak] stops the previous one,
 *   which gets an `onError("stopped")` callback.
 * - [stop] halts playback immediately. No-op when silent.
 */
interface SpeechOut {
    /** Speaks [text] and reports its lifecycle through [listener]. */
    fun speak(text: String, voiceId: String?, speed: Float, listener: SpeechOutListener)

    /** Stops playback immediately. */
    fun stop()

    /** Lists the voices available on this device. */
    fun voices(): List<VoiceInfo>
}

/**
 * Receives wake-word detections from [WakeWord.arm].
 *
 * [onDetected] is called once per detection with the matched [keyword]. The
 * listener runs on a detector-owned thread and must not block.
 */
interface WakeWordListener {
    /** The armed keyword was heard. */
    fun onDetected(keyword: String)

    /** Detection stopped with an error. [reason] describes the failure, not the audio. */
    fun onError(reason: String)
}

/**
 * Always-on keyword spotting. Arming is user-initiated and visible to the
 * user. Audio is processed on device only.
 *
 * - [arm] starts listening for [keyword]. Re-arming replaces the previous keyword.
 * - [disarm] stops listening and releases the microphone. No-op when disarmed.
 */
interface WakeWord {
    /** Starts listening for [keyword], replacing any previous keyword. */
    fun arm(keyword: String, listener: WakeWordListener)

    /** Stops listening and releases the microphone. */
    fun disarm()
}

/**
 * Frame-level speech detection. [isSpeech] takes one frame of mono 16-bit PCM
 * (10, 20 or 30 ms at 16 kHz, i.e. 160, 320 or 480 samples) and reports whether
 * it contains speech. It is stateless and safe to call on any thread.
 */
interface VoiceActivity {
    /** Returns true when [frame] contains speech. */
    fun isSpeech(frame: ShortArray): Boolean
}
