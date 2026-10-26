package app.memo.platform.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Microphone capture: 16 kHz, mono, PCM16, in 20 ms frames.
 *
 * - [start] requires [MicPermission]. Without it nothing is opened and [Start.PermissionMissing]
 *   is returned. Capture only starts from an explicit call to [start].
 * - Two threads run while capturing. The capture thread reads from `AudioRecord` into a
 *   [RingBuffer] and never waits on a listener. The delivery thread cuts 20 ms frames from the
 *   ring and calls [Listener.onFrame]. A slow listener makes the ring drop its oldest audio, so
 *   the microphone reader is never blocked.
 * - Effects (echo cancellation, noise suppression, gain control) are attached when the device
 *   reports them as available. Their absence is not an error.
 * - [stop] releases the recorder, the effects and both threads. It is idempotent.
 * - Audio is never logged, and nothing is written to storage here.
 */
class AudioCapture(private val context: Context) {

    /** Receives one frame of captured audio. Runs on the delivery thread and must not block. */
    fun interface Listener {
        /**
         * [frame] is a copy the listener owns. [rms] is its level in 0.0..1.0 (see [AudioLevel]).
         */
        fun onFrame(frame: ShortArray, rms: Float)
    }

    /** Outcome of [start]. */
    sealed interface Start {
        data object Started : Start
        data object PermissionMissing : Start
        data object AlreadyRunning : Start
        data class Unavailable(val reason: String) : Start
    }

    private val effects = ArrayList<AutoCloseable>()
    private val signal = Object()

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var deliveryThread: Thread? = null
    private var ring: RingBuffer? = null

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean
        get() = running

    /**
     * Opens the microphone and starts delivering frames to [listener]. Never blocks on audio I/O.
     * Callers must hold the permission (checked here, not assumed).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(listener: Listener): Start {
        if (running) return Start.AlreadyRunning
        if (!MicPermission.isGranted(context)) return Start.PermissionMissing

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return Start.Unavailable("microphone format not supported (code $minBytes)")
        val bufferBytes = maxOf(minBytes, FRAME_SAMPLES * 2 * BUFFER_FRAMES)

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: SecurityException) {
            return Start.PermissionMissing
        } catch (e: UnsupportedOperationException) {
            return Start.Unavailable("microphone could not be opened")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Start.Unavailable("microphone could not be initialised")
        }

        attachEffects(record.audioSessionId)
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            releaseEffects()
            record.release()
            return Start.Unavailable("microphone did not start")
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.stop()
            releaseEffects()
            record.release()
            return Start.Unavailable("microphone is in use by another app")
        }

        val buffer = RingBuffer(RING_SAMPLES)
        ring = buffer
        recorder = record
        running = true

        captureThread = Thread({ captureLoop(record, buffer) }, "memo-voice-capture").apply {
            isDaemon = true
            start()
        }
        deliveryThread = Thread({ deliveryLoop(buffer, listener) }, "memo-voice-deliver").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "capture started")
        return Start.Started
    }

    /** Stops capture and releases the microphone and its effects. Does nothing when not running. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val record = recorder
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped by the system: nothing left to stop.
        }
        synchronized(signal) { signal.notifyAll() }
        captureThread?.join(JOIN_TIMEOUT_MS)
        // stop() may be called from the listener, which runs on the delivery thread. It must not join itself.
        deliveryThread?.takeIf { it !== Thread.currentThread() }?.join(JOIN_TIMEOUT_MS)
        captureThread = null
        deliveryThread = null
        record?.release()
        recorder = null
        ring = null
        releaseEffects()
        Log.i(TAG, "capture stopped")
    }

    private fun captureLoop(record: AudioRecord, buffer: RingBuffer) {
        val chunk = ShortArray(FRAME_SAMPLES)
        while (running) {
            val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                // A negative code is a recorder error (for example, the device was taken away).
                running = false
                break
            }
            if (n > 0) {
                buffer.write(chunk, 0, n)
                synchronized(signal) { signal.notifyAll() }
            }
        }
    }

    private fun deliveryLoop(buffer: RingBuffer, listener: Listener) {
        val frame = ShortArray(FRAME_SAMPLES)
        while (running || buffer.available >= FRAME_SAMPLES) {
            synchronized(signal) {
                while (running && buffer.available < FRAME_SAMPLES) {
                    signal.wait(WAIT_MS)
                }
            }
            if (buffer.available < FRAME_SAMPLES) continue
            if (buffer.read(frame, 0, FRAME_SAMPLES) != FRAME_SAMPLES) continue
            val copy = frame.copyOf()
            try {
                listener.onFrame(copy, AudioLevel.rms(copy))
            } catch (e: RuntimeException) {
                // A listener failure must not stop capture. The frame is dropped.
            }
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { fx ->
                fx.enabled = true
                effects += AutoCloseable { fx.release() }
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { fx ->
                fx.enabled = true
                effects += AutoCloseable { fx.release() }
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let { fx ->
                fx.enabled = true
                effects += AutoCloseable { fx.release() }
            }
        }
    }

    private fun releaseEffects() {
        for (effect in effects) {
            try {
                effect.close()
            } catch (e: Exception) {
                // An effect that fails to release is already detached from the session.
            }
        }
        effects.clear()
    }

    companion object {
        private const val TAG = "MemoVoice"

        /** Sample rate for capture and for the streaming recogniser. */
        const val SAMPLE_RATE = 16_000

        /** Samples in one 20 ms frame at 16 kHz. */
        const val FRAME_SAMPLES = 320

        private const val BUFFER_FRAMES = 8
        private const val RING_SAMPLES = FRAME_SAMPLES * 50 // one second of audio
        private const val WAIT_MS = 50L
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
