package app.memo.platform.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Streaming playback of float PCM (the TTS output format) through `AudioTrack`.
 *
 * - [open] takes audio focus with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so other audio ducks while
 *   Memo speaks, and creates the track at the TTS sample rate. A refused focus request means no
 *   playback. The usage is `USAGE_ASSISTANT` with `CONTENT_TYPE_SPEECH`.
 * - [write] is blocking and must not run on the main thread. It returns the number of frames
 *   accepted, or a negative value when playback has been stopped or has failed.
 * - Losing focus (permanently or transiently) stops playback at once and calls [onFocusLost].
 *   Ducking loss (`CAN_DUCK`) keeps playing.
 * - [stop] flushes and releases the track and abandons focus. It is the barge-in path: safe to
 *   call from any thread, at any time, and repeatedly.
 */
class AudioPlayback(
    context: Context,
    private val onFocusLost: () -> Unit = {},
) {

    /** Outcome of [open]. */
    sealed interface Open {
        data object Opened : Open
        data class Unavailable(val reason: String) : Open
    }

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    @Volatile private var lastLevel: Float = 0f

    /** RMS level of the most recent chunk written, 0.0..1.0. Used by the echo guard. */
    val level: Float
        get() = lastLevel

    /** True while a track is open. */
    val isOpen: Boolean
        get() = synchronized(lock) { track != null }

    /**
     * Opens a playback session at [sampleRate] Hz (mono, float). Any session already open is
     * stopped first. Returns [Open.Unavailable] when focus is refused or the track cannot be made.
     */
    fun open(sampleRate: Int): Open {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "sample rate out of range: $sampleRate" }
        stop()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return Open.Unavailable("audio focus was refused")

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBytes <= 0) {
            audioManager.abandonAudioFocusRequest(request)
            return Open.Unavailable("playback format not supported (code $minBytes)")
        }
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, sampleRate * BYTES_PER_FLOAT / 2))
                .build()
        } catch (e: UnsupportedOperationException) {
            audioManager.abandonAudioFocusRequest(request)
            return Open.Unavailable("playback could not be opened")
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            audioManager.abandonAudioFocusRequest(request)
            return Open.Unavailable("playback could not be initialised")
        }
        created.play()
        synchronized(lock) {
            track = created
            focus = request
        }
        return Open.Opened
    }

    /**
     * Writes `samples[offset until offset + length]` to the open track and returns the number of
     * frames accepted, or -1 when there is no open track or playback was stopped mid-write.
     * Blocks until the samples are queued, so call it off the main thread.
     */
    fun write(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "write range is outside the samples" }
        val current = synchronized(lock) { track } ?: return -1
        lastLevel = AudioLevel.rmsFloat(samples, offset, length)
        var written = 0
        while (written < length) {
            val n = current.write(samples, offset + written, length - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return if (written > 0) written else -1
            written += n
        }
        return written
    }

    /**
     * Stops playback now. Pending audio is discarded, the track is released, and focus is
     * abandoned. A no-op when nothing is open.
     */
    fun stop() {
        val closing: AudioTrack?
        val closingFocus: AudioFocusRequest?
        synchronized(lock) {
            closing = track
            closingFocus = focus
            track = null
            focus = null
        }
        lastLevel = 0f
        if (closing != null) {
            try {
                closing.pause()
                closing.flush()
                closing.stop()
            } catch (e: IllegalStateException) {
                // The track was already stopped by the system.
            } finally {
                closing.release()
            }
        }
        closingFocus?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun onFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            Log.i(TAG, "playback focus lost")
            stop()
            try {
                onFocusLost()
            } catch (e: RuntimeException) {
                // The owner's callback must not break focus handling.
            }
        }
    }

    private companion object {
        const val TAG = "MemoVoice"
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 48_000
        const val BYTES_PER_FLOAT = 4
    }
}
