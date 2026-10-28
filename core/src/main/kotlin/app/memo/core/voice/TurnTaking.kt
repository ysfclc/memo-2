package app.memo.core.voice

/**
 * The conversation turn-taking state machine. Pure: no Android types, no I/O.
 *
 * Discipline (TASKS M2.A3): IDLE → LISTENING → THINKING → SPEAKING → LISTENING; ENDED.
 *
 * - [TurnEvent.Start] opens a conversation: IDLE → LISTENING.
 * - [TurnEvent.UtteranceEnd] closes the user's turn (ASR endpoint): LISTENING → THINKING.
 * - [TurnEvent.SpeechStarted] queues the first TTS audio of an answer: THINKING → SPEAKING.
 *   Later sentence audio while already SPEAKING is a no-op.
 * - [TurnEvent.SpeechFinished] ends the assistant's turn: SPEAKING → LISTENING, so the loop
 *   runs LISTENING → THINKING → SPEAKING → LISTENING per exchange. In THINKING it means the
 *   answer ended without any speech (empty answer): THINKING → LISTENING.
 * - [TurnEvent.VadSpeechDetected] while SPEAKING is a barge-in: SPEAKING → LISTENING with the
 *   [TurnEffect.InterruptSpeech] directive (stop TTS, cancel queued sentences; the mic keeps
 *   running). Elsewhere it is a no-op — in LISTENING the VAD is the ear, in THINKING the
 *   user's new speech waits for the answer per the plan's minimal discipline.
 * - [TurnEvent.EndRequest] (user) and [TurnEvent.Error] (engine) end the conversation from any
 *   active state: → ENDED with the [TurnEffect.ReleaseAudio] directive (stop TTS, stop capture).
 *   ENDED is terminal: every later event is ignored.
 *
 * Events that make no sense in the current state are ignored (return no effects, keep the
 * state), so a mistimed callback can never crash the loop; the accepted transitions are pinned
 * by `TurnTakingTest`. Not thread-safe: the conversation loop drives it from one thread.
 */
class TurnTaking(initial: TurnState = TurnState.Idle) {

    var state: TurnState = initial
        private set

    /**
     * Applies [event] and returns the effects the loop must perform, in order. The state moves
     * only along the transitions documented on the class; anything else keeps it unchanged.
     */
    fun on(event: TurnEvent): List<TurnEffect> {
        val target = when (event) {
            TurnEvent.Start -> when (state) {
                TurnState.Idle -> TurnState.Listening
                else -> return emptyList()
            }
            TurnEvent.UtteranceEnd -> when (state) {
                TurnState.Listening -> TurnState.Thinking
                else -> return emptyList()
            }
            TurnEvent.SpeechStarted -> when (state) {
                TurnState.Thinking -> TurnState.Speaking
                else -> return emptyList()
            }
            TurnEvent.SpeechFinished -> when (state) {
                TurnState.Speaking, TurnState.Thinking -> TurnState.Listening
                else -> return emptyList()
            }
            TurnEvent.VadSpeechDetected -> when (state) {
                TurnState.Speaking -> TurnState.Listening
                else -> return emptyList()
            }
            TurnEvent.EndRequest, TurnEvent.Error -> when (state) {
                TurnState.Listening, TurnState.Thinking, TurnState.Speaking -> TurnState.Ended
                else -> return emptyList()
            }
        }
        state = target
        return when {
            event == TurnEvent.VadSpeechDetected -> listOf(TurnEffect.InterruptSpeech)
            target == TurnState.Ended -> listOf(TurnEffect.ReleaseAudio)
            else -> emptyList()
        }
    }
}

/** The conversation states, in plan order. */
enum class TurnState {
    /** No conversation. The mic is closed. */
    Idle,

    /** The user's turn: capture runs, streaming ASR listens, wake word may also fire. */
    Listening,

    /** The assistant's turn is being produced: the LLM stream runs, TTS has not started. */
    Thinking,

    /** The assistant's answer is being spoken: the TTS queue feeds playback. */
    Speaking,

    /** Terminal: the conversation ended and audio was released. */
    Ended,
}

/** Things that happen to the conversation. */
enum class TurnEvent {
    /** The user opened the conversation (button or wake word). */
    Start,

    /** The user's turn is complete (streaming ASR endpoint); the transcript goes to the LLM. */
    UtteranceEnd,

    /** The first audio of the answer is queued for playback. */
    SpeechStarted,

    /** The answer's audio finished (queue drained, or the answer had no speech at all). */
    SpeechFinished,

    /** The VAD heard speech; a barge-in while SPEAKING. */
    VadSpeechDetected,

    /** The user asked to end the conversation. */
    EndRequest,

    /** An engine failed and the conversation cannot continue. */
    Error,
}

/** Directives the loop executes alongside a transition. */
enum class TurnEffect {
    /** Barge-in: stop TTS playback and cancel queued sentences; keep capturing. */
    InterruptSpeech,

    /** Conversation over: stop TTS playback and release the mic. */
    ReleaseAudio,
}
