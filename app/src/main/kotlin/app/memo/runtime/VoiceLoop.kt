package app.memo.runtime

import android.content.Context
import android.util.Log
import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.RequestHandle
import app.memo.core.voice.SentenceChunker
import app.memo.core.voice.TurnEffect
import app.memo.core.voice.TurnEvent
import app.memo.core.voice.TurnState
import app.memo.core.voice.TurnTaking
import app.memo.platform.android.voice.AudioCapture
import app.memo.platform.android.voice.AudioPlayback
import app.memo.platform.android.voice.SherpaAsr
import app.memo.platform.android.voice.SherpaBargeInEngine
import app.memo.platform.android.voice.SherpaListenEngine
import app.memo.platform.android.voice.SherpaLoopCapture
import app.memo.platform.android.voice.SherpaLoopPlayback
import app.memo.platform.android.voice.SherpaSpeakEngine
import app.memo.platform.android.voice.SherpaTts
import app.memo.platform.android.voice.SherpaVad
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The hands-free conversation loop (TASKS M2.A3): one microphone, one streaming ASR, one LLM
 * request at a time, one TTS queue — disciplined by [TurnTaking].
 *
 * Threads, by contract:
 * - The **loop thread** owns the [TurnTaking] machine, the [SentenceChunker], the LLM request and
 *   the TTS synthesis + playback sequence. Every state transition happens here.
 * - The **mic delivery thread** (from [LoopCapture]) feeds the engines — the ASR decode and the
 *   speech detection are the real-time DSP this callback exists for — and never touches the
 *   machine. It signals the loop thread and, on a barge-in, stops playback directly so a blocking
 *   speaker write breaks at once.
 * - **LLM callbacks** arrive on a runtime-owned thread; they enqueue work for the loop thread and
 *   return at once, per the [ChatSink] contract.
 *
 * The barge-in path is [TurnEvent.VadSpeechDetected] while SPEAKING: playback stops, the in-flight
 * answer is cancelled, the queued sentences are dropped, and the machine returns to LISTENING.
 * Endings ([TurnEvent.EndRequest], [TurnEvent.Error]) release the audio; the engines stay open —
 * models are expensive to load and the next conversation reuses them.
 *
 * The wake word (M2.C2) and the UI (M2.B1) call [startConversation] / [requestEnd]; the loop
 * never starts the microphone on its own. The voice conversation keeps its own history — sharing
 * it with the chat store is M2.B1 integration.
 */
class VoiceLoop(
    private val llm: LlmRuntime,
    private val listen: ListenEngine,
    private val talk: SpeakEngine,
    private val barge: BargeInEngine,
    private val capture: LoopCapture,
    private val playback: LoopPlayback,
    private val systemPrompt: () -> String?,
) {

    /** What the UI sees from the loop. */
    interface Listener {
        /** The machine moved. Arrives on the loop thread. */
        fun onTurnState(state: TurnState)

        /**
         * The user's transcript so far, while LISTENING. Arrives on the audio delivery thread at
         * full frame rate — the receiver hops to its own thread if it needs to.
         */
        fun onPartialTranscript(text: String)

        /** Mic level 0..1, for the presence orb. Arrives on the audio delivery thread at frame rate. */
        fun onMicLevel(rms: Float)

        /** A sentence is about to be spoken; this is the caption. Arrives on the loop thread. */
        fun onSpeakingSentence(text: String)

        /** The conversation ended (user request or error). Arrives on the loop thread. */
        fun onEnded(reason: EndReason)
    }

    /** Why [Listener.onEnded] fired. */
    enum class EndReason { Requested, Error }

    private val machine = TurnTaking()
    private val chunker = SentenceChunker()
    private val history = ArrayList<ChatTurn>()
    private val tokenQueue = ConcurrentLinkedQueue<String>()
    private val answerText = StringBuilder()
    private val work = LinkedBlockingQueue<Runnable>()
    private val loop = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, work)

    @Volatile private var listenerRef: Listener? = null
    private var request: RequestHandle? = null
    private var answerDone = false

    @Volatile private var bargeIn = false
    @Volatile private var stopPlayback = false

    /** Attaches the UI listener. Call before [startConversation]. */
    fun setListener(value: Listener?) {
        listenerRef = value
    }

    /**
     * Opens the conversation: IDLE → LISTENING and the mic starts. Returns false when the mic
     * could not start (the permission flow owns the rationale — the loop adds nothing to it).
     */
    fun startConversation(): Boolean {
        if (machine.state != TurnState.Idle) return false
        machine.on(TurnEvent.Start)
        val started = capture.start(::onMicFrame)
        if (started != AudioCapture.Start.Started) {
            machine.on(TurnEvent.Error)
            listenerRef?.onTurnState(machine.state)
            listenerRef?.onEnded(EndReason.Error)
            return false
        }
        listenerRef?.onTurnState(machine.state)
        return true
    }

    /** The user asked to end the conversation. Safe from any thread. */
    fun requestEnd() {
        stopPlayback = true
        playback.stop() // break a blocking write on the loop thread immediately
        loop.execute {
            val before = machine.state
            machine.on(TurnEvent.EndRequest)
            if (machine.state != before) releaseAudio(EndReason.Requested) // once, on the transition
        }
    }

    /** Ends and releases everything. The loop is unusable afterwards. */
    fun close() {
        loop.execute { machine.on(TurnEvent.EndRequest) }
        loop.shutdown()
        runCatching { loop.awaitTermination(2, TimeUnit.SECONDS) }
        capture.stop()
        playback.stop()
        listenerRef = null
    }

    private fun releaseAudio(reason: EndReason) {
        stopPlayback = true
        bargeIn = false
        request?.cancel()
        request = null
        chunker.reset()
        tokenQueue.clear()
        capture.stop()
        playback.stop()
        listenerRef?.onTurnState(machine.state)
        listenerRef?.onEnded(reason)
    }

    /**
     * The user tapped a speaking answer to interrupt it (M2.B1 live screen). The same barge-in
     * path as focus loss: a no-op through the machine when nothing was speaking.
     */
    fun interrupt() {
        if (machine.state == TurnState.Speaking) {
            bargeIn = true
            stopPlayback = true
        }
        loop.execute(::handleBargeIn)
    }

    /** Called by the platform playback when audio focus is lost. */
    fun onFocusLost() {
        // The answer cannot be heard any more. Treat focus loss as a barge-in, not an end.
        interrupt()
    }

    // --- mic delivery thread ----------------------------------------------------------------

    private fun onMicFrame(frame: ShortArray, rms: Float) {
        listenerRef?.onMicLevel(rms)
        val samples = FloatArray(frame.size) { frame[it] / 32768f }
        barge.onFrame(samples)
        when (machine.state) {
            TurnState.Listening -> {
                listenerRef?.onPartialTranscript(listen.onFrame(samples))
                if (listen.endpoint()) {
                    // The engine owns the stream: capture the final transcript and start the next
                    // utterance clean right here; the loop never touches the stream.
                    val transcript = listen.finalTranscript().trim()
                    if (transcript.isNotEmpty()) loop.execute { handleUtterance(transcript) }
                }
            }
            TurnState.Speaking -> if (barge.speechDetected && !bargeIn) {
                bargeIn = true
                stopPlayback = true
                playback.stop() // break a blocking write on the loop thread immediately
                loop.execute(::handleBargeIn)
            }
            else -> {}
        }
    }

    // --- loop thread -------------------------------------------------------------------------

    /** THINKING begins: the user's utterance is final. Late signals are no-ops via the machine. */
    private fun handleUtterance(transcript: String) {
        if (machine.state != TurnState.Listening) return
        machine.on(TurnEvent.UtteranceEnd)
        listenerRef?.onTurnState(machine.state)
        history += ChatTurn(ChatTurn.Role.USER, transcript)
        answerText.clear()
        answerDone = false
        bargeIn = false
        stopPlayback = false
        request = llm.chat(turnsForLlm(), GenParams.ROUTINE, VoiceSink())
    }

    private fun handleBargeIn() {
        val effects = machine.on(TurnEvent.VadSpeechDetected)
        listenerRef?.onTurnState(machine.state)
        if (TurnEffect.InterruptSpeech in effects) {
            request?.cancel()
            request = null
            chunker.reset()
            tokenQueue.clear()
            answerText.clear()
        }
        bargeIn = false
        stopPlayback = false
    }

    private fun turnsForLlm(): List<ChatTurn> {
        val turns = ArrayList<ChatTurn>()
        systemPrompt()?.let { turns += ChatTurn(ChatTurn.Role.SYSTEM, it) }
        turns += history
        return turns
    }

    private inner class VoiceSink : ChatSink {
        override fun onToken(text: String) {
            answerText.append(text)
            tokenQueue += text
            signalSpeak()
        }

        override fun onThinking(text: String) {
            // Reasoning is never spoken.
        }

        override fun onComplete(stats: GenStats) {
            loop.execute {
                history += ChatTurn(ChatTurn.Role.ASSISTANT, answerText.toString())
                answerText.clear()
                answerDone = true
                speakQueued()
            }
        }

        override fun onError(e: LlmError) {
            if (e == LlmError.Cancelled) return // barge-in or end: the machine already moved on
            Log.w(TAG, "voice answer failed: $e")
            loop.execute { answerFailed() }
        }
    }

    /**
     * A failed answer ends with a spoken apology and back to LISTENING (TASKS M2.A3: "error from
     * LLM → LISTENING with message") — the conversation survives. A hard engine failure still
     * ends the conversation through [TurnEvent.Error].
     */
    private fun answerFailed() {
        if (machine.state != TurnState.Thinking && machine.state != TurnState.Speaking) return
        request = null
        chunker.reset()
        tokenQueue.clear()
        history += ChatTurn(ChatTurn.Role.ASSISTANT, answerText.toString() + ANSWER_FAILED)
        answerText.clear()
        answerDone = false
        bargeIn = false
        stopPlayback = false
        val before = machine.state
        machine.on(TurnEvent.SpeechStarted) // THINKING → SPEAKING; no-op when already SPEAKING
        if (machine.state != before) listenerRef?.onTurnState(machine.state)
        speak(ANSWER_FAILED)
        machine.on(TurnEvent.SpeechFinished)
        listenerRef?.onTurnState(machine.state)
    }

    /** Queues a drain of the token queue onto the loop thread. */
    private fun signalSpeak() {
        loop.execute(::speakQueued)
    }

    /**
     * Moves queued tokens through the chunker and speaks the sentences one by one, blocking until
     * each is played. Barge-in and endings arrive as flags checked between sentences and writes.
     */
    private fun speakQueued() {
        if (machine.state != TurnState.Thinking && machine.state != TurnState.Speaking) return
        val sentences = ArrayList<String>()
        while (true) {
            val token = tokenQueue.poll() ?: break
            sentences += chunker.push(token)
        }
        if (answerDone) sentences += chunker.flush()

        for (sentence in sentences) {
            if (bargeIn || stopPlayback) break
            speak(sentence)
        }
        if (answerDone && !bargeIn && !stopPlayback &&
            (machine.state == TurnState.Speaking || machine.state == TurnState.Thinking)
        ) {
            machine.on(TurnEvent.SpeechFinished)
            listenerRef?.onTurnState(machine.state)
        }
    }

    /**
     * Synthesizes [sentence] and plays the chunks as they arrive; blocks until played or stopped.
     * Runs on the loop thread, so the machine transitions inside the callback cannot race.
     */
    private fun speak(sentence: String) {
        listenerRef?.onSpeakingSentence(sentence) // the caption, before the first chunk plays
        talk.generate(sentence) { samples ->
            val before = machine.state
            machine.on(TurnEvent.SpeechStarted) // first chunk: THINKING → SPEAKING; later: no-op
            if (machine.state != before) {
                barge.reset() // fresh barge-in window: only speech AFTER the answer counts
                listenerRef?.onTurnState(machine.state)
            }
            if (bargeIn || stopPlayback) return@generate true
            if (!playback.isOpen && playback.open(talk.sampleRate) != AudioPlayback.Open.Opened) {
                return@generate true // focus refused: give up on this answer's audio
            }
            playback.write(samples) >= 0
        }
    }

    internal companion object {
        const val TAG = "MemoVoice"
        const val ANSWER_FAILED = "Sorry, I could not finish that answer."

        /** Device wiring: the real sherpa engines and platform audio. */
        internal fun forDevice(
            context: Context,
            llm: LlmRuntime,
            asr: SherpaAsr,
            tts: SherpaTts,
            vad: SherpaVad,
            systemPrompt: () -> String?,
        ): VoiceLoop {
            val app = context.applicationContext
            lateinit var loop: VoiceLoop
            val capture = SherpaLoopCapture(app)
            val playback = SherpaLoopPlayback(app, onFocusLost = { loop.onFocusLost() })
            loop = VoiceLoop(
                llm,
                SherpaListenEngine(asr),
                SherpaSpeakEngine(tts),
                SherpaBargeInEngine(vad),
                capture,
                playback,
                systemPrompt,
            )
            return loop
        }
    }
}
