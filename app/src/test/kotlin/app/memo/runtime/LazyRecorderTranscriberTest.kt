package app.memo.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The lazy transcription engine pin, JVM-only: first use builds the delegate, a load failure
 * throws through to the recorder (which stores the audio and reports Untranscribed), and the
 * next attempt retries the load - `by lazy` must not cache the failure, so installing the
 * Whisper model later heals transcription without dropping or rebuilding the recorder.
 */
class LazyRecorderTranscriberTest {

    private class FakeDelegate : RecorderTranscriber {
        var samples: FloatArray? = null
        var sampleRate = 0
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            this.samples = samples
            this.sampleRate = sampleRate
            return "transcript"
        }
    }

    private val sharedDelegate = FakeDelegate()
    private var loads = 0
    private var failFirstLoad = false

    /** Mirrors the MemoRuntime load: the missing-model reason arrives as IllegalStateException. */
    private fun subject(): LazyRecorderTranscriber = LazyRecorderTranscriber {
        loads++
        if (failFirstLoad && loads == 1) throw IllegalStateException("voice-message model not installed")
        sharedDelegate
    }

    @Test
    fun firstTranscribeLoadsTheDelegateOnceAndPassesArguments() {
        val recorder = subject()

        val outcome = recorder.transcribe(floatArrayOf(0.5f, -0.5f), 16_000)

        assertEquals("transcript", outcome)
        assertEquals(1, loads)
    }

    @Test
    fun argumentsReachTheDelegateVerbatim() {
        val recorder = subject()

        recorder.transcribe(floatArrayOf(0.25f, -0.75f, 1f), 16_000)

        assertEquals(16_000, sharedDelegate.sampleRate)
        assertEquals(0.25f, sharedDelegate.samples?.get(0))
        assertEquals(-0.75f, sharedDelegate.samples?.get(1))
    }

    @Test
    fun loadFailureThrowsThroughOnFirstUse() {
        failFirstLoad = true
        val recorder = subject()

        assertFailsWith<IllegalStateException> { recorder.transcribe(floatArrayOf(0f), 16_000) }
        assertEquals(1, loads)
    }

    @Test
    fun loadFailureIsRetriedOnTheNextUse() {
        failFirstLoad = true
        val recorder = subject()
        runCatching { recorder.transcribe(floatArrayOf(0f), 16_000) }

        assertEquals("transcript", recorder.transcribe(floatArrayOf(1f), 16_000))
        assertEquals(2, loads)
    }
}
