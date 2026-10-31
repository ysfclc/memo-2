package app.memo.platform.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import app.memo.core.chat.VoiceClipRefs
import app.memo.core.voice.ClipPlayerEvent
import app.memo.runtime.ClipPlayerDevice
import java.io.File

/**
 * The device wiring of [ClipPlayerDevice] over [MediaPlayer]: plays stored voice messages
 * (16 kHz mono PCM16 WAV, written by [VoiceMessageStore]) straight from the file — no
 * re-decode into PCM buffers, unlike the loop's [AudioPlayback], and no streaming synthesis
 * semantics. The clip stays app-private: [start] resolves the reference inside
 * `filesDir/voice-messages/` (the [VoiceMessageStore] directory) and refuses anything that
 * would join outside it.
 *
 * Threading: construction and every port call are expected on the main thread, which is the
 * Compose caller's thread; [MediaPlayer] posts its listeners to the creating thread's looper,
 * so every [ClipPlayerEvent] also lands on main. Audio focus is taken with
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and the same assistant attributes as [AudioPlayback];
 * a focus loss pauses the clip (reported as [ClipPlayerEvent.Interrupted]), so the user can
 * resume after other audio has had its say.
 */
class MediaPlayerClipPlayer(
    context: Context,
    private val onEvent: (ClipPlayerEvent) -> Unit,
) : ClipPlayerDevice {

    private val appContext = context.applicationContext
    private val voiceDir = File(appContext.filesDir, "voice-messages")
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    override fun start(ref: String) {
        release()
        if (!VoiceClipRefs.isFilenameSafe(ref)) {
            failed("invalid clip reference")
            return
        }
        val file = File(voiceDir, ref)
        if (!file.isFile) {
            failed("the recording file is missing")
            return
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.abandonAudioFocusRequest(request)
            failed("audio focus was refused")
            return
        }
        focus = request
        val media: MediaPlayer
        try {
            media = MediaPlayer()
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.setOnErrorListener { _, what, extra ->
                failed("media error $what/$extra")
                true
            }
            media.setOnCompletionListener {
                // Completion keeps the file ready for a replay; focus is abandoned so the
                // transient claim is not held while idle.
                abandonFocus()
                onEvent(ClipPlayerEvent.Completed)
            }
            media.prepare()
            media.start()
        } catch (e: Exception) {
            release()
            failed(e.message ?: "the recording could not be played")
            return
        }
        player = media
        onEvent(ClipPlayerEvent.StartedPlaying)
    }

    override fun pause() {
        val media = playingPlayer()
        if (media == null) {
            onEvent(ClipPlayerEvent.PausedAt(null))
            return
        }
        try {
            media.pause()
        } catch (e: IllegalStateException) {
            release()
            failed("playback is not in a pausable state")
            return
        }
        onEvent(ClipPlayerEvent.PausedAt(currentPositionMs()))
    }

    override fun resume() {
        val media = playingPlayer()
        if (media == null) {
            failed("nothing is paused to resume")
            return
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            release()
            failed("audio focus was refused")
            return
        }
        focus = request
        try {
            media.start()
        } catch (e: Exception) {
            release()
            failed(e.message ?: "the recording could not be restarted")
            return
        }
        onEvent(ClipPlayerEvent.StartedPlaying)
    }

    override fun release() {
        player?.let { media ->
            try {
                if (media.isPlaying) media.stop()
            } catch (e: IllegalStateException) {
                // The player was already in a terminal state (isPlaying throws there).
            }
            media.release()
        }
        player = null
        abandonFocus()
    }

    override fun currentPositionMs(): Long? {
        val media = player ?: return null
        return try {
            // `currentPosition` is 0 in the unstarted player; the events not yet confirmed
            // mean the UI shows 0 played, which is honest for a clip that just started.
            media.currentPosition.toLong().coerceAtLeast(0L)
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun playingPlayer(): MediaPlayer? {
        val media = player ?: return null
        return try {
            if (media.isPlaying) media else null
        } catch (e: IllegalStateException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Focus loss while audio is flowing pauses the clip; the user resumes from there. */
    private fun onFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            val media = playingPlayer()
            if (media != null) {
                try {
                    media.pause()
                } catch (e: IllegalStateException) {
                    release()
                    return
                }
                abandonFocus()
                onEvent(ClipPlayerEvent.Interrupted(currentPositionMs()))
            }
        }
    }

    private fun abandonFocus() {
        val request = focus
        focus = null
        request?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun failed(reason: String) {
        onEvent(ClipPlayerEvent.Failed(reason.take(MAX_REASON_CHARS)))
    }

    private companion object {
        /** Player failure reasons, well under the [app.memo.core.chat.ChatLimits] note bound. */
        const val MAX_REASON_CHARS = 160
    }
}
