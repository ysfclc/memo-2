package app.memo.runtime

import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.LoadResult
import app.memo.core.platform.ModelFormat
import app.memo.core.platform.ModelRef
import app.memo.core.platform.RequestHandle
import app.memo.core.platform.RuntimeInfo
import app.memo.core.voice.TurnState
import app.memo.platform.android.voice.AudioCapture
import app.memo.platform.android.voice.AudioPlayback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversation loop's threading and turn-taking, pinned on the JVM with fakes for every port.
 * The real DSP (sherpa engines, platform audio) is device-verified separately; here the loop's
 * own discipline is the subject: state transitions, history accumulation, barge-in, endings.
 */
class VoiceLoopTest {

    // --- fakes -----------------------------------------------------------------------------------

    /** Emits a canned answer synchronously, per the [ChatSink] contract (calls run "on a thread"). */
    private class FakeLlm : LlmRuntime {
        val chats = mutableListOf<List<ChatTurn>>()
        var tokens: List<String> = listOf("Hello ", "there. ", "Good ", "bye.")
        var fail = false
        val cancellations = AtomicInteger(0)

        override val formats: Set<ModelFormat> = setOf(ModelFormat.GGUF)
        override fun load(ref: ModelRef): LoadResult = LoadResult.Failed(LlmError.Other("not used"))
        override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
            chats += turns
            if (fail) {
                sink.onError(LlmError.Other("boom"))
            } else {
                tokens.forEach(sink::onToken)
                sink.onComplete(GenStats(1, tokens.size, 1, "fake"))
            }
            return FakeHandle(cancellations)
        }

        override fun unload() {}
        override fun info(): RuntimeInfo = RuntimeInfo("fake", null, 0)
        override fun close() {}
    }

    private class FakeHandle(private val cancellations: AtomicInteger) : RequestHandle {
        override fun cancel() {
            cancellations.incrementAndGet()
        }
    }

    /** Partial and endpoint are test-set; the transcript is captured at the endpoint. */
    private class FakeListen : ListenEngine {
        var transcript = ""
        var endpointAt = false
        override fun onFrame(samples: FloatArray): String = transcript
        override fun endpoint(): Boolean = endpointAt
        override fun finalTranscript(): String {
            endpointAt = false
            return transcript
        }
    }

    private class FakeBarge : BargeInEngine {
        var speech = false
        var resets = 0
        override fun onFrame(samples: FloatArray) {}
        override val speechDetected: Boolean
            get() = speech
        override fun reset() {
            resets++
            speech = false
        }
    }

    /** Blocks a chosen generate call until the test unblocks it, to hold the loop mid-speech. */
    private class FakeTalk : SpeakEngine {
        override val sampleRate: Int = 24_000
        val spoken = mutableListOf<String>()
        var blockOnCall = -1
        private var calls = 0
        private val entered = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        override fun generate(text: String, onChunk: (FloatArray) -> Boolean) {
            if (calls++ == blockOnCall) {
                entered.countDown()
                gate.await()
            }
            spoken += text
            onChunk(FloatArray(320))
        }

        fun awaitBlocked(): Boolean = entered.await(5, TimeUnit.SECONDS)
        fun unblock() = gate.countDown()
    }

    private class FakePlayback : LoopPlayback {
        var openedNow = false
        var stoppedNow = false
        var writes = 0
        override val isOpen: Boolean
            get() = openedNow && !stoppedNow
        override fun open(sampleRate: Int): AudioPlayback.Open {
            openedNow = true
            stoppedNow = false
            return AudioPlayback.Open.Opened
        }
        override fun write(samples: FloatArray): Int {
            if (stoppedNow) return -1
            writes++
            return samples.size
        }
        override fun stop() {
            stoppedNow = true
        }
    }

    private class FakeCapture : LoopCapture {
        var listener: AudioCapture.Listener? = null
        var result: AudioCapture.Start = AudioCapture.Start.Started
        var stops = 0
        override fun start(listener: AudioCapture.Listener): AudioCapture.Start {
            this.listener = listener
            return result
        }
        override fun stop() {
            stops++
        }
    }

    private class Recorder : VoiceLoop.Listener {
        val states = CopyOnWriteArrayList<TurnState>()
        val ended = CopyOnWriteArrayList<VoiceLoop.EndReason>()
        val partials = CopyOnWriteArrayList<String>()
        val captions = CopyOnWriteArrayList<String>()
        override fun onTurnState(state: TurnState) {
            states += state
        }
        override fun onPartialTranscript(text: String) {
            partials += text
        }
        override fun onMicLevel(rms: Float) {
        }
        override fun onSpeakingSentence(text: String) {
            captions += text
        }
        override fun onEnded(reason: VoiceLoop.EndReason) {
            ended += reason
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun newLoop(
        llm: FakeLlm = FakeLlm(),
        listen: FakeListen = FakeListen(),
        talk: FakeTalk = FakeTalk(),
        barge: FakeBarge = FakeBarge(),
        capture: FakeCapture = FakeCapture(),
        playback: FakePlayback = FakePlayback(),
        now: () -> Long = System::currentTimeMillis,
    ): VoiceLoop = VoiceLoop(llm, listen, talk, barge, capture, playback, systemPrompt = { "You are Memo." }, now = now)

    /** One 20 ms mic frame, delivered as the capture delivery thread would. Voice-level rms. */
    private fun deliver(capture: FakeCapture, rms: Float = 0.5f) {
        capture.listener!!.onFrame(ShortArray(320), rms)
    }

    private fun awaitTrue(seconds: Long = 5, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun Recorder.awaitState(state: TurnState, count: Int = 1): Boolean =
        awaitTrue { states.count { it == state } >= count }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun happyExchange_cyclesAndAccumulatesHistory() {
        val llm = FakeLlm()
        val listen = FakeListen()
        val capture = FakeCapture()
        val talk = FakeTalk()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, talk, capture = capture).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        assertEquals(listOf(TurnState.Listening), recorder.states.toList())

        listen.transcript = "hello memo"
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Listening, 2), "exchange 1 should finish")
        assertEquals(
            listOf(TurnState.Listening, TurnState.Thinking, TurnState.Speaking, TurnState.Listening),
            recorder.states.toList(),
        )
        assertEquals(listOf("Hello there.", "Good bye."), talk.spoken.toList())
        assertTrue("hello memo" in recorder.partials)

        listen.transcript = "and more"
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Listening, 3), "exchange 2 should finish")
        assertEquals(2, llm.chats.size)
        val second = llm.chats[1]
        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.SYSTEM, "You are Memo."),
                ChatTurn(ChatTurn.Role.USER, "hello memo"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "Hello there. Good bye."),
                ChatTurn(ChatTurn.Role.USER, "and more"),
            ),
            second,
        )
        loop.close()
    }

    @Test
    fun bargeInDuringSpeaking_stopsPlaybackCancelsAnswer_returnsToListening() {
        val llm = FakeLlm()
        val listen = FakeListen().also { it.transcript = "hello memo" }
        val talk = FakeTalk().also { it.blockOnCall = 1 } // hold the SECOND sentence mid-speech
        val barge = FakeBarge()
        val capture = FakeCapture()
        val playback = FakePlayback()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, talk, barge, capture, playback).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        listen.endpointAt = true
        deliver(capture) // sentence 1 speaks; sentence 2 blocks the loop mid-SPEAKING
        assertTrue(recorder.awaitState(TurnState.Speaking))
        assertTrue(talk.awaitBlocked(), "the second sentence should hold the loop")

        barge.speech = true
        deliver(capture) // the user speaks while Memo speaks: the mic thread breaks the write

        talk.unblock() // the chunk callback now sees the flags and stops the sentence
        assertTrue(recorder.awaitState(TurnState.Listening, 2), "barge-in returns to LISTENING")
        assertEquals(1, llm.cancellations.get())
        assertTrue(playback.stoppedNow)
        assertEquals(1, playback.writes) // only sentence 1's chunk was played
        assertTrue(barge.resets >= 1) // a fresh detection window opened when the answer began
        loop.close()
    }

    @Test
    fun llmError_speaksApologyBackToListening_andSurvives() {
        val llm = FakeLlm().also { it.fail = true }
        val listen = FakeListen()
        val talk = FakeTalk()
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, talk, capture = capture).also { it.setListener(recorder) }
        val apology = "Sorry, I could not finish that answer."

        assertTrue(loop.startConversation())
        listen.transcript = "hello memo"
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Listening, 2), "the apology should end with LISTENING")
        assertEquals(listOf(apology), talk.spoken.toList())
        assertEquals(listOf(TurnState.Listening, TurnState.Thinking, TurnState.Speaking, TurnState.Listening), recorder.states.toList())

        // The conversation survives: a second exchange works normally.
        llm.fail = false
        listen.transcript = "again please"
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Listening, 3))
        val second = llm.chats[1]
        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.SYSTEM, "You are Memo."),
                ChatTurn(ChatTurn.Role.USER, "hello memo"),
                ChatTurn(ChatTurn.Role.ASSISTANT, apology),
                ChatTurn(ChatTurn.Role.USER, "again please"),
            ),
            second,
        )
        loop.close()
    }

    @Test
    fun requestEnd_releasesAudio_firesEndedOnce() {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val recorder = Recorder()
        val loop = newLoop(capture = capture, playback = playback).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        loop.requestEnd()
        assertTrue(recorder.awaitState(TurnState.Ended))
        assertEquals(listOf(VoiceLoop.EndReason.Requested), recorder.ended.toList())
        assertTrue(capture.stops >= 1)
        assertTrue(playback.stoppedNow)

        loop.requestEnd() // double end is idempotent
        Thread.sleep(150)
        assertEquals(listOf(VoiceLoop.EndReason.Requested), recorder.ended.toList())
        loop.close()
    }

    @Test
    fun startTwice_isRefused() {
        val loop = newLoop()
        assertTrue(loop.startConversation())
        assertEquals(false, loop.startConversation())
        loop.close()
    }

    @Test
    fun micStartFailure_endsConversationWithError() {
        val capture = FakeCapture().also { it.result = AudioCapture.Start.PermissionMissing }
        val recorder = Recorder()
        val loop = newLoop(capture = capture).also { it.setListener(recorder) }

        assertEquals(false, loop.startConversation())
        assertEquals(listOf(TurnState.Ended), recorder.states.toList())
        assertEquals(listOf(VoiceLoop.EndReason.Error), recorder.ended.toList())
        loop.close()
    }

    @Test
    fun emptyTranscript_endpointIgnored() {
        val llm = FakeLlm()
        val listen = FakeListen().also { it.endpointAt = true; it.transcript = "" }
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, capture = capture).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        deliver(capture)
        Thread.sleep(300)
        assertEquals(0, llm.chats.size)
        assertEquals(listOf(TurnState.Listening), recorder.states.toList())
        loop.close()
    }

    // --- silence timeout, cancel words, echo guard (TASKS M2.A3 mini-task 4) --------------------

    @Test
    fun silenceTimeout_endsTheConversation_afterQuietListening() {
        val clock = AtomicLong(1_000_000L)
        val barge = FakeBarge()
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(barge = barge, capture = capture, now = { clock.get() }).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        clock.addAndGet(9_000L) // quiet in LISTENING far past the window
        deliver(capture, rms = 0f)
        assertTrue(recorder.awaitState(TurnState.Ended), "silence should end the conversation")
        assertEquals(listOf(VoiceLoop.EndReason.Requested), recorder.ended.toList())
        assertTrue(capture.stops >= 1)
        loop.close()
    }

    @Test
    fun silenceTimeout_doesNotFire_whileTheUserTalks() {
        val clock = AtomicLong(1_000_000L)
        val barge = FakeBarge().also { it.speech = true }
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(barge = barge, capture = capture, now = { clock.get() }).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        clock.addAndGet(9_000L)
        deliver(capture) // voice activity resets the window: no timeout may queue
        clock.addAndGet(1_000L)
        deliver(capture)
        Thread.sleep(200)
        assertEquals(listOf(TurnState.Listening), recorder.states.toList())
        loop.close()
    }

    @Test
    fun cancelPhraseUtterance_endsTheConversation_withoutCallingTheLlm() {
        val llm = FakeLlm()
        val listen = FakeListen().also { it.transcript = "Stop." }
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, capture = capture).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Ended), "a bare cancel phrase should end the talk")
        assertEquals(listOf(VoiceLoop.EndReason.Requested), recorder.ended.toList())
        assertEquals(0, llm.chats.size)
        assertTrue(capture.stops >= 1)
        loop.close()
    }

    @Test
    fun contentUtterance_containingACancelWord_goesToTheLlm() {
        val llm = FakeLlm()
        val listen = FakeListen().also { it.transcript = "how do I stop a service" }
        val capture = FakeCapture()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, capture = capture).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        listen.endpointAt = true
        deliver(capture)
        assertTrue(recorder.awaitState(TurnState.Listening, 2), "the exchange completes normally")
        assertEquals(1, llm.chats.size)
        loop.close()
    }

    @Test
    fun cancelPhrases_matchAcrossLanguages_bareUtterancesOnly() {
        for (phrase in listOf("stop", "Stop.", "STOP IT", "cancel", "przerwij", "PRZERWIJ!", "anuluj", "İptal et", "dur", "kes")) {
            assertTrue(VoiceLoop.isCancelPhrase(phrase), "should match: $phrase")
        }
        for (content in listOf("stop the music", "how do I cancel a plan", "zatrzymaj się za rok", "durstig", "")) {
            assertTrue(!VoiceLoop.isCancelPhrase(content), "should NOT match: $content")
        }
    }

    @Test
    fun echoGuard_belowEnergyFloor_doesNotBargeIn() {
        val llm = FakeLlm()
        val listen = FakeListen().also { it.transcript = "hello memo" }
        val talk = FakeTalk().also { it.blockOnCall = 1 }
        val barge = FakeBarge()
        val capture = FakeCapture()
        val playback = FakePlayback()
        val recorder = Recorder()
        val loop = newLoop(llm, listen, talk, barge, capture, playback).also { it.setListener(recorder) }

        assertTrue(loop.startConversation())
        listen.endpointAt = true
        deliver(capture) // sentence 1 speaks; sentence 2 holds the loop mid-SPEAKING
        assertTrue(recorder.awaitState(TurnState.Speaking))
        assertTrue(talk.awaitBlocked())

        barge.speech = true
        deliver(capture, rms = VoiceLoop.ECHO_RMS_FLOOR / 2f) // VAD fires but the energy is echo-level
        talk.unblock()
        assertTrue(recorder.awaitState(TurnState.Listening, 2), "the answer finishes normally")
        assertEquals(0, llm.cancellations.get(), "no barge-in may cancel the answer")
        assertEquals(2, playback.writes, "both sentences play out")
        loop.close()
    }
}
