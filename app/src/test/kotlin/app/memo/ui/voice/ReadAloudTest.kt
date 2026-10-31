package app.memo.ui.voice

import app.memo.core.voice.SentenceChunker
import app.memo.platform.android.voice.AudioPlayback
import app.memo.runtime.LoopPlayback
import app.memo.runtime.SpeakEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-aloud state machine pinned on the JVM with fakes for every port (fake TTS voice,
 * fake playback, in-process executors). The real sherpa engines and platform audio are device
 * matters; here the subject is the machine: the sentence plan through the shared
 * SentenceChunker, the Unavailable/Failed paths, cancel mid-speech from another thread, focus
 * loss, warm engine reuse and teardown.
 */
class ReadAloudTest {

    // --- fakes -----------------------------------------------------------------------------------

    /**
     * The loaded Kokoro voice: every generate call is recorded, and a chosen call parks until
     * the test unblocks it, so the machine can be cancelled mid-speech from another thread.
     */
    private class FakeSpeak : SpeakEngine {

        override val sampleRate: Int = 24_000

        var chunksPerSentence = 1
        var blockOnCall = -1

        /** Invoked before every delivered chunk, so a test can sample the machine state. */
        var chunkHook: (() -> Unit)? = null

        val parked = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        private val spoken = mutableListOf<String>()
        private var calls = 0

        override fun generate(text: String, onChunk: (FloatArray) -> Boolean) {
            synchronized(spoken) { spoken += text }
            if (calls++ == blockOnCall) {
                parked.countDown()
                gate.await()
            }
            repeat(chunksPerSentence) {
                chunkHook?.invoke()
                if (onChunk(FloatArray(FRAME))) return@generate // engine stops; the machine takes over
            }
        }

        fun unblock() = gate.countDown()

        fun spokenSoFar(): List<String> = synchronized(spoken) { spoken.toList() }

        private companion object {
            const val FRAME = 320
        }
    }

    /** The pinned-model front: flips for the Unavailable branch and the load-failure branch. */
    private class FakeSource(private val speak: FakeSpeak) : ReadAloudVoiceSource {

        var installed = true
        var loadFailsWith: Exception? = null
        var probes = 0
        var loads = 0
        var lastVoice: LoadedVoice? = null

        override fun installed(): Boolean = installed

        override fun load(): ReadAloudVoice {
            loads++
            loadFailsWith?.let { throw it }
            return LoadedVoice(speak).also { lastVoice = it }
        }

        class LoadedVoice(override val speak: SpeakEngine) : ReadAloudVoice {
            val closes = AtomicInteger(0)

            override fun close() {
                closes.incrementAndGet()
            }
        }
    }

    /** The speaker output; [refuseWith] makes the playback-refused failure testable. */
    private class FakePlayback : LoopPlayback {

        var refuseWith: String? = null
        val opens = AtomicInteger(0)
        val writes = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val openedAtRate = AtomicInteger(0)
        private var stopped = false

        override val isOpen: Boolean
            get() = openedAtRate.get() != 0 && !stopped

        override fun open(sampleRate: Int): AudioPlayback.Open {
            refuseWith?.let { return AudioPlayback.Open.Unavailable(it) }
            stopped = false
            openedAtRate.set(sampleRate)
            opens.incrementAndGet()
            return AudioPlayback.Open.Opened
        }

        override fun write(samples: FloatArray): Int {
            writes.incrementAndGet()
            return samples.size
        }

        override fun stop() {
            stopped = true
            stops.incrementAndGet()
        }
    }

    /** One runnable per request; the test decides when they run, for ordering assertions. */
    private class ManualExecutor : Executor {

        private val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued += command
        }

        /** Runs every queued task, including the ones queued while earlier ones run. */
        fun runAll() {
            while (true) {
                val task = queued.removeFirstOrNull() ?: return
                task.run()
            }
        }
    }

    /** Polls until [check] holds or the deadline passes; the last check decides. */
    private fun await(check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            Thread.sleep(POLL_MS)
        }
        return check()
    }

    // --- the machine says what it speaks ---------------------------------------------------------

    @Test
    fun aMessageIsSpokenSentenceBySentenceAndFinishesDone() {
        val speak = FakeSpeak()
        val playback = FakePlayback()
        val manual = ManualExecutor()
        val readAloud = ReadAloud(FakeSource(speak), playback, manual)
        val during = mutableListOf<ReadAloud.State>()
        speak.chunkHook = { during += readAloud.state }

        readAloud.speak("First sentence. Second one!", requestId = "m1")
        manual.runAll()

        assertEquals(listOf("First sentence.", "Second one!"), speak.spokenSoFar())
        assertEquals(
            listOf<ReadAloud.State>(
                ReadAloud.State.Speaking(0, "First sentence."),
                ReadAloud.State.Speaking(1, "Second one!"),
            ),
            during,
        )
        assertEquals(ReadAloud.State.Done, readAloud.state)
        assertEquals("m1", readAloud.requestId)
        assertTrue(readAloud.available)
        assertEquals(1, playback.opens.get())
        assertEquals(24_000, playback.openedAtRate.get())
        assertEquals(2, playback.writes.get())
    }

    @Test
    fun thePlanIsTheSharedSentenceChunkerFeed() {
        val speak = FakeSpeak()
        val manual = ManualExecutor()
        val readAloud = ReadAloud(FakeSource(speak), FakePlayback(), manual)

        val long = "word ".repeat(70).trim() // 349 characters, no sentence boundary in sight
        readAloud.speak(long, requestId = "m2")
        manual.runAll()

        assertEquals(ReadAloud.State.Done, readAloud.state)
        val plan = speak.spokenSoFar()
        assertTrue(plan.size >= 2, "the chunker should cut a 349-char message, got ${plan.size} pieces")
        plan.forEach { sentence ->
            assertTrue(
                sentence.length <= SentenceChunker.DEFAULT_MAX_CHARS,
                "piece over the chunker limit: ${sentence.length}",
            )
        }
        // The feed is the shared chunker's: the pieces rejoin into its speech-ready text.
        assertEquals(SentenceChunker.speechText(long), plan.joinToString(" "))
    }

    @Test
    fun missingModelIsAnExplicitUnavailableAndSpeaksNothing() {
        val speak = FakeSpeak()
        val playback = FakePlayback()
        val source = FakeSource(speak).apply { installed = false }
        val manual = ManualExecutor()
        val readAloud = ReadAloud(source, playback, manual)

        readAloud.probe()
        manual.runAll()
        assertTrue(!readAloud.available)

        readAloud.speak("A sentence.", requestId = "m3")
        manual.runAll()

        val state = readAloud.state
        assertTrue(state is ReadAloud.State.Unavailable)
        assertEquals(ReadAloud.UNAVAILABLE_REASON, state.reason)
        assertEquals(0, speak.spokenSoFar().size)
        assertEquals(0, playback.opens.get())
        assertEquals(0, playback.writes.get())
    }

    @Test
    fun cancelWhileSpeakingStopsTheRestOfTheMessage() {
        val speak = FakeSpeak().apply { blockOnCall = 0 } // park inside the FIRST sentence
        val playback = FakePlayback()
        val readAloud = ReadAloud(FakeSource(speak), playback, Executors.newSingleThreadExecutor())

        readAloud.speak("First sentence. Second one!", requestId = "m4")
        assertTrue(speak.parked.await(5, TimeUnit.SECONDS))
        readAloud.cancel()
        speak.unblock()

        assertTrue(await { readAloud.state is ReadAloud.State.Cancelled })
        assertEquals(listOf("First sentence."), speak.spokenSoFar())
        assertEquals(1, playback.stops.get())
    }

    @Test
    fun cancelWhenNothingIsReadIsANoOp() {
        val speak = FakeSpeak()
        val readAloud = ReadAloud(FakeSource(speak), FakePlayback(), Executor { it.run() })

        readAloud.cancel()

        assertEquals(ReadAloud.State.Idle, readAloud.state)
        assertEquals(0, speak.spokenSoFar().size)
    }

    @Test
    fun aSecondReadWhileReadingIsDropped() {
        val speak = FakeSpeak().apply { blockOnCall = 0 }
        val readAloud = ReadAloud(FakeSource(speak), FakePlayback(), Executors.newSingleThreadExecutor())

        readAloud.speak("First sentence. Second one!", requestId = "m6")
        assertTrue(speak.parked.await(5, TimeUnit.SECONDS))
        readAloud.speak("Another message!", requestId = "m6b") // dropped: one read at a time
        assertEquals(ReadAloud.State.Speaking(0, "First sentence."), readAloud.state)
        speak.unblock()

        assertTrue(await { readAloud.state is ReadAloud.State.Done })
        assertEquals(listOf("First sentence.", "Second one!"), speak.spokenSoFar())
        assertEquals("m6", readAloud.requestId)
    }

    @Test
    fun focusLossCancelsTheRead() {
        val speak = FakeSpeak().apply { blockOnCall = 0 }
        val readAloud = ReadAloud(FakeSource(speak), FakePlayback(), Executors.newSingleThreadExecutor())

        readAloud.speak("First sentence. Second one!", requestId = "m7")
        assertTrue(speak.parked.await(5, TimeUnit.SECONDS))
        readAloud.onFocusLost()
        speak.unblock()

        assertTrue(await { readAloud.state is ReadAloud.State.Cancelled })
        assertEquals(listOf("First sentence."), speak.spokenSoFar())
    }

    @Test
    fun refusedPlaybackIsAFailureAndCanBeRetried() {
        val speak = FakeSpeak()
        val playback = FakePlayback().apply { refuseWith = "audio focus was refused" }
        val manual = ManualExecutor()
        val readAloud = ReadAloud(FakeSource(speak), playback, manual)

        readAloud.speak("A sentence.", requestId = "m8")
        manual.runAll()
        assertEquals(ReadAloud.State.Failed("audio focus was refused"), readAloud.state)
        assertEquals(0, playback.writes.get())

        playback.refuseWith = null // the audio came back; the same button speaks again
        readAloud.speak("A sentence.", requestId = "m8b")
        manual.runAll()
        assertEquals(ReadAloud.State.Done, readAloud.state)
        assertEquals(1, playback.opens.get()) // the refusal was not counted, the retried open is
    }

    @Test
    fun engineLoadFailureIsReportedAndCanBeRetried() {
        val speak = FakeSpeak()
        val source = FakeSource(speak).apply { loadFailsWith = IllegalStateException("native model crashed") }
        val manual = ManualExecutor()
        val readAloud = ReadAloud(source, FakePlayback(), manual)

        readAloud.speak("A sentence.", requestId = "m9")
        manual.runAll()
        assertEquals(
            ReadAloud.State.Failed("Could not load the Kokoro voice: native model crashed"),
            readAloud.state,
        )
        assertEquals(0, speak.spokenSoFar().size)

        source.loadFailsWith = null
        readAloud.speak("A sentence.", requestId = "m9b")
        manual.runAll()
        assertEquals(ReadAloud.State.Done, readAloud.state)
        assertEquals(2, source.loads)
    }

    @Test
    fun theEngineStaysWarmBetweenReadsAndIsClosedOnce() {
        val source = FakeSource(FakeSpeak())
        val manual = ManualExecutor()
        val readAloud = ReadAloud(source, FakePlayback(), manual)

        readAloud.speak("One.", requestId = "m10")
        manual.runAll()
        readAloud.speak("Two.", requestId = "m10b")
        manual.runAll()
        assertEquals(1, source.loads) // the second read reused the loaded engine
        assertEquals(0, source.lastVoice?.closes?.get())

        readAloud.close()
        manual.runAll()
        assertEquals(1, source.lastVoice?.closes?.get())
    }

    @Test
    fun theProbeReflectsThePinnedModelInstall() {
        val source = FakeSource(FakeSpeak())
        val manual = ManualExecutor()
        val readAloud = ReadAloud(source, FakePlayback(), manual)

        readAloud.probe()
        manual.runAll()
        assertTrue(readAloud.available)

        source.installed = false // the model can be uninstalled in Models while the screen is up
        readAloud.probe()
        manual.runAll()
        assertTrue(!readAloud.available)
    }

    @Test
    fun anEmojiOnlyMessageReadsAsDoneWithoutSpeaking() {
        val speak = FakeSpeak()
        val manual = ManualExecutor()
        val readAloud = ReadAloud(FakeSource(speak), FakePlayback(), manual)

        readAloud.speak("\uD83D\uDE00 \u2600\uFE0F", requestId = "m11") // cleaned away by the chunker
        manual.runAll()

        assertEquals(ReadAloud.State.Done, readAloud.state)
        assertEquals(0, speak.spokenSoFar().size)
    }
}

private const val TIMEOUT_MS = 5_000L
private const val POLL_MS = 10L
