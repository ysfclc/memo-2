package app.memo.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnTakingTest {

    @Test
    fun happyPathCyclesListeningThinkingSpeakingListening() {
        val t = TurnTaking()
        assertEquals(TurnState.Idle, t.state)
        assertEquals(emptyList(), t.on(TurnEvent.Start))
        assertEquals(TurnState.Listening, t.state)
        assertEquals(emptyList(), t.on(TurnEvent.UtteranceEnd))
        assertEquals(TurnState.Thinking, t.state)
        assertEquals(emptyList(), t.on(TurnEvent.SpeechStarted))
        assertEquals(TurnState.Speaking, t.state)
        assertEquals(emptyList(), t.on(TurnEvent.SpeechFinished))
        assertEquals(TurnState.Listening, t.state)
    }

    @Test
    fun secondExchangeRunsOnTheSameMachine() {
        val t = TurnTaking(TurnState.Listening)
        t.on(TurnEvent.UtteranceEnd)
        t.on(TurnEvent.SpeechStarted)
        t.on(TurnEvent.SpeechFinished)
        assertEquals(TurnState.Listening, t.state)
        t.on(TurnEvent.UtteranceEnd)
        assertEquals(TurnState.Thinking, t.state)
    }

    @Test
    fun bargeInReturnsToListeningAndDirectsInterrupt() {
        val t = TurnTaking(TurnState.Listening)
        t.on(TurnEvent.UtteranceEnd)
        t.on(TurnEvent.SpeechStarted)
        val effects = t.on(TurnEvent.VadSpeechDetected)
        assertEquals(TurnState.Listening, t.state)
        assertEquals(listOf(TurnEffect.InterruptSpeech), effects)
        // the conversation continues after the interruption
        assertEquals(emptyList(), t.on(TurnEvent.UtteranceEnd))
        assertEquals(TurnState.Thinking, t.state)
    }

    @Test
    fun bargeInIsIgnoredOutsideSpeaking() {
        for (state in listOf(TurnState.Idle, TurnState.Listening, TurnState.Thinking)) {
            val t = TurnTaking(state)
            assertEquals(emptyList(), t.on(TurnEvent.VadSpeechDetected), "from $state")
            assertEquals(state, t.state)
        }
        val ended = TurnTaking(TurnState.Ended)
        assertEquals(emptyList(), ended.on(TurnEvent.VadSpeechDetected))
        assertEquals(TurnState.Ended, ended.state)
    }

    @Test
    fun emptyAnswerReturnsToListeningWithoutSpeech() {
        val t = TurnTaking(TurnState.Listening)
        t.on(TurnEvent.UtteranceEnd)
        assertEquals(emptyList(), t.on(TurnEvent.SpeechFinished))
        assertEquals(TurnState.Listening, t.state)
    }

    @Test
    fun endRequestEndsEveryActiveStateWithReleaseAudio() {
        for (state in listOf(TurnState.Listening, TurnState.Thinking, TurnState.Speaking)) {
            val t = TurnTaking(state)
            assertEquals(listOf(TurnEffect.ReleaseAudio), t.on(TurnEvent.EndRequest), "from $state")
            assertEquals(TurnState.Ended, t.state)
        }
    }

    @Test
    fun errorEndsEveryActiveStateWithReleaseAudio() {
        for (state in listOf(TurnState.Listening, TurnState.Thinking, TurnState.Speaking)) {
            val t = TurnTaking(state)
            assertEquals(listOf(TurnEffect.ReleaseAudio), t.on(TurnEvent.Error), "from $state")
            assertEquals(TurnState.Ended, t.state)
        }
    }

    @Test
    fun endRequestAndErrorAreNoOpsFromIdle() {
        val t = TurnTaking()
        assertEquals(emptyList(), t.on(TurnEvent.EndRequest))
        assertEquals(TurnState.Idle, t.state)
        assertEquals(emptyList(), t.on(TurnEvent.Error))
        assertEquals(TurnState.Idle, t.state)
    }

    @Test
    fun endedIsTerminal() {
        val t = TurnTaking(TurnState.Listening)
        t.on(TurnEvent.EndRequest)
        for (event in TurnEvent.entries) {
            assertEquals(emptyList(), t.on(event), "event $event after ENDED")
            assertEquals(TurnState.Ended, t.state)
        }
    }

    @Test
    fun mistimedEventsAreNoOps() {
        // Start only from IDLE
        val listening = TurnTaking(TurnState.Listening)
        assertEquals(emptyList(), listening.on(TurnEvent.Start))
        assertEquals(TurnState.Listening, listening.state)
        // UtteranceEnd only from LISTENING
        val idle = TurnTaking()
        assertEquals(emptyList(), idle.on(TurnEvent.UtteranceEnd))
        assertEquals(TurnState.Idle, idle.state)
        // SpeechStarted only from THINKING
        assertEquals(emptyList(), idle.on(TurnEvent.SpeechStarted))
        assertEquals(TurnState.Idle, idle.state)
        // SpeechFinished only from SPEAKING or THINKING
        val speaking = TurnTaking(TurnState.Speaking)
        speaking.on(TurnEvent.EndRequest)
        assertEquals(emptyList(), speaking.on(TurnEvent.SpeechFinished))
        assertEquals(TurnState.Ended, speaking.state)
    }
}
