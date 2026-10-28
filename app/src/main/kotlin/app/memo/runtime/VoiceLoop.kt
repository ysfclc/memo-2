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
import app.memo.platform.android.voice.SherpaTts
import app.memo.platform.android.voice.SherpaVad
import com.k2fsa.sherpa.onnx.OnlineStream
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
 * - The **mic delivery thread** (from [AudioCapture]) feeds the engines — the ASR decode and the
 *   VAD checks are the real-time DSP this callback exists for — and never touches the machine. It
 *   signals the loop thread and, on a barge-in, stops playback directly so a blocking
 *   `AudioTrack.write` breaks at once.
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
    context: Context,
    private val llm: LlmRuntime,
    private val asr: SherpaAsr,
    private val tts: SherpaTts,
    private val vad: SherpaVad,
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

        /** The conversation ended (user request or error). Arrives on the loop thread. */
        fun onEnded(reason: EndReason)
    }

    /** Why [Listener.onEnded] fired. */
    enum class EndReason { Requested, Error }

    private val appContext = context.applicationContext
    private val capture = AudioCapture(appContext)
    private val playback = AudioPlayback(appContext, onFocusLost = { onFocusLost() })

    private val machine = TurnTaking()
    private val chunker = SentenceChunker()
    private val history = ArrayList<ChatTurn>()
    private val tokenQueue = ConcurrentLinkedQueue<String>()
    private val answerText = StringBuilder()
    private val work = LinkedBlockingQueue<Runnable>()
    private val loop = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, work)

    @Volatile private var listenerRef: Listener? = null
    private var asrStream: OnlineStream? = null
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
            machine.on(TurnEvent.EndRequest)
            if (machine.state == TurnState.Ended) releaseAudio(EndReason.Requested)
        }
    }

    /** Ends and releases everything. The loop is unusable afterwards. */
    fun close() {
        loop.execute { machine.on(TurnEvent.EndRequest) }
        loop.shutdown()
        runCatching { loop.awaitTermination(2, TimeUnit.SECONDS) }
        capture.stop()
        playback.stop()
        // The ASR stream is owned by the recognizer; SherpaAsr.close() releases it with the engine.
        asrStream = null
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

    private fun onFocusLost() {
        // The answer cannot be heard any more. Treat focus loss as a barge-in, not an end.
        if (machine.state == TurnState.Speaking) {
            bargeIn = true
            stopPlayback = true
        }
        loop.execute(::handleBargeIn) // no-op through the machine when nothing was speaking
    }

    // --- mic delivery thread ----------------------------------------------------------------

    private fun onMicFrame(frame: ShortArray, @Suppress("UNUSED_PARAMETER") rms: Float) {
        val samples = FloatArray(frame.size) { frame[it] / 32768f }
        vad.acceptWaveform(samples)
        when (machine.state) {
            TurnState.Listening -> {
                val stream = asrStream ?: asr.newStream().also { asrStream = it }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (asr.isReady(stream)) asr.decode(stream)
                listenerRef?.onPartialTranscript(asr.partial(stream))
                if (asr.isEndpoint(stream)) {
                    // This thread owns the stream: capture the final transcript and start the next
                    // utterance clean right here; the loop never touches the stream.
                    val transcript = asr.partial(stream).trim()
                    asr.reset(stream)
                    if (transcript.isNotEmpty()) loop.execute { handleUtterance(transcript) }
                }
            }
            TurnState.Speaking -> if (vad.isSpeechDetected && !bargeIn) {
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
            loop.execute {
                machine.on(TurnEvent.Error)
                releaseAudio(EndReason.Error)
            }
        }
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
        tts.generate(
            text = sentence,
            onChunk = { samples ->
                val before = machine.state
                machine.on(TurnEvent.SpeechStarted) // first chunk: THINKING → SPEAKING; later: no-op
                if (machine.state != before) {
                    vad.reset() // fresh barge-in window: only speech AFTER the answer counts
                    listenerRef?.onTurnState(machine.state)
                }
                if (bargeIn || stopPlayback) return@generate true
                if (!playback.isOpen && playback.open(tts.sampleRate) != AudioPlayback.Open.Opened) {
                    return@generate true // focus refused: give up on this answer's audio
                }
                playback.write(samples) >= 0
            },
        )
    }

    private companion object {
        const val TAG = "MemoVoice"
        const val SAMPLE_RATE = 16_000
    }
}
