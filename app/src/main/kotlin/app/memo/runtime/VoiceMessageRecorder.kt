package app.memo.runtime

import app.memo.platform.android.voice.AudioCapture
import app.memo.platform.android.voice.Pcm16
import java.util.concurrent.Executor

/**
 * Hold-to-record voice-message engine, the audio half of [app.memo.ui.voice.VoiceRecorderBar].
 * The flow: [start] captures mic frames (buffering PCM for the WAV and the offline Whisper
 * transcript, and emitting throttled level/elapsed progress for the waveform); [finish] stops
 * capture, stores the WAV through [RecorderStore] and transcribes on [executor]; [cancel]
 * discards everything.
 *
 * Threading: [start]/[finish]/[cancel] are called from the UI thread; mic frames arrive on
 * `AudioCapture`'s delivery thread, which must not block — the lock is held only for buffer
 * appends and list copies, and the progress callback runs outside it. Callbacks therefore
 * arrive on background threads; a Compose caller may write `mutableStateOf` directly from
 * them (snapshot writes are thread-safe) but must not touch other UI state. Transcription
 * and clip storage run on [executor]; a recording can start while a previous transcription is
 * still finishing (the single-thread executor serialises the jobs).
 *
 * Cap: audio longer than [MAX_DURATION_MS] stops being buffered (the meter stays live); the
 * message keeps the first five minutes.
 */
class VoiceMessageRecorder(
    private val capture: RecorderCapture,
    private val transcriber: RecorderTranscriber,
    private val store: RecorderStore,
    private val executor: Executor,
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
) {

    /** Outcome of [finish]. */
    sealed interface Recorded {
        /** Audio stored and transcribed. [transcript] may be empty when nothing was said. */
        data class Message(val ref: String, val transcript: String, val durationMs: Long) : Recorded

        /**
         * Audio stored; transcription failed ([reason] says why — usually the Whisper model
         * is not installed). [ref] is null when even the WAV could not be stored. The
         * message is still playable when the clip exists.
         */
        data class Untranscribed(val ref: String?, val durationMs: Long, val reason: String) : Recorded

        /** Shorter than [MIN_DURATION_MS]; nothing stored. */
        data object Empty : Recorded
    }

    private val lock = Any()
    private val chunks = ArrayList<ShortArray>()
    private val levels = ArrayList<Float>()
    private var frameSamples = 0
    private var framesSinceProgress = 0
    private var recording = false
    @Volatile private var onProgress: ((levels: List<Float>, elapsedMs: Long) -> Unit)? = null

    /** True between a successful [start] and [finish]/[cancel]. */
    val isRecording: Boolean
        get() = synchronized(lock) { recording }

    /**
     * Starts capturing. [onProgress] receives the level history (one 0..1 value per 20 ms
     * frame) and the elapsed milliseconds, at most every [PROGRESS_EVERY_FRAMES] frames.
     * Returns the capture outcome; on failure the recorder is reset.
     */
    fun start(onProgress: (levels: List<Float>, elapsedMs: Long) -> Unit): AudioCapture.Start {
        synchronized(lock) {
            if (recording) return AudioCapture.Start.AlreadyRunning
            chunks.clear()
            levels.clear()
            frameSamples = 0
            framesSinceProgress = 0
            this.onProgress = onProgress
            recording = true
        }
        val started = capture.start(::onMicFrame)
        if (started != AudioCapture.Start.Started) reset()
        return started
    }

    /** Stops capture and discards the recording. Idempotent. */
    fun cancel() {
        capture.stop()
        reset()
    }

    /**
     * Stops capture and finalizes: stores the WAV, transcribes, then calls [onDone] on
     * [executor]'s thread with the outcome. Returns false when not recording (or already
     * finalizing); in that case [onDone] is never called.
     */
    fun finish(onDone: (Recorded) -> Unit): Boolean {
        val pcm: ShortArray
        val durationMs: Long
        synchronized(lock) {
            if (!recording) return false
            recording = false
            onProgress = null
            pcm = flattenLocked()
            durationMs = (frameSamples / (sampleRate / 1000)).toLong()
            chunks.clear()
            levels.clear()
            frameSamples = 0
        }
        capture.stop()
        if (durationMs < MIN_DURATION_MS || pcm.isEmpty()) {
            onDone(Recorded.Empty)
            return true
        }
        executor.execute {
            val recorded = try {
                val ref = store.save(Pcm16.encodeWav(pcm, sampleRate))
                try {
                    Recorded.Message(ref, transcriber.transcribe(Pcm16.toFloat(pcm), sampleRate).trim(), durationMs)
                } catch (e: Exception) {
                    Recorded.Untranscribed(ref, durationMs, e.message ?: e.javaClass.simpleName)
                }
            } catch (e: Exception) {
                Recorded.Untranscribed(null, durationMs, e.message ?: e.javaClass.simpleName)
            }
            onDone(recorded)
        }
        return true
    }

    private fun flattenLocked(): ShortArray {
        val pcm = ShortArray(frameSamples)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.size)
            offset += chunk.size
        }
        return pcm
    }

    private fun reset() {
        synchronized(lock) {
            recording = false
            onProgress = null
            chunks.clear()
            levels.clear()
            frameSamples = 0
            framesSinceProgress = 0
        }
    }

    /** Delivery thread. The frame is a copy the listener owns, so it is retained directly. */
    private fun onMicFrame(frame: ShortArray, rms: Float) {
        var notify: ((List<Float>, Long) -> Unit)? = null
        var snapshot: List<Float> = emptyList()
        var elapsedMs = 0L
        synchronized(lock) {
            if (!recording) return
            if (frameSamples < MAX_FRAME_SAMPLES) {
                chunks += frame
                frameSamples += frame.size
            }
            levels += rms
            if (++framesSinceProgress >= PROGRESS_EVERY_FRAMES) {
                framesSinceProgress = 0
                snapshot = levels.toList()
                elapsedMs = (frameSamples / (sampleRate / 1000)).toLong()
                notify = onProgress
            }
        }
        notify?.invoke(snapshot, elapsedMs)
    }

    companion object {
        /** Shorter recordings are taps, not messages — matches the bar's TAP threshold. */
        const val MIN_DURATION_MS = 350L

        /** Audio cap: five minutes. Frames past this keep metering but are not buffered. */
        const val MAX_DURATION_MS = 300_000L

        /** Progress ticks: every third 20 ms frame (~15 Hz). */
        const val PROGRESS_EVERY_FRAMES = 3

        private const val MAX_FRAME_SAMPLES = MAX_DURATION_MS.toInt() * (AudioCapture.SAMPLE_RATE / 1000)
    }
}
