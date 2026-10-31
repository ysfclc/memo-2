package app.memo.ui.voice

import app.memo.ui.voice.RecorderGestureState.Outcome
import app.memo.ui.voice.RecorderGestureState.Phase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RecorderGestureState] pinned on the JVM: press-to-record, the short-hold tap, slide-to-cancel
 * with its disarm path, drag clamping, and the mm:ss timer formatting. Every release resets the
 * machine to IDLE, ready for the next gesture.
 */
class RecorderGestureStateTest {

    @Test
    fun pressStartsRecordingAndASufficientHoldSendsAndResetsToIdle() {
        val gesture = RecorderGestureState()

        gesture.press()

        assertEquals(Phase.RECORDING, gesture.phase)
        assertEquals(0f, gesture.slide)

        val outcome = gesture.release(durationMs = 1000)

        assertEquals(Outcome.SENT, outcome)
        assertEquals(Phase.IDLE, gesture.phase)
        assertEquals(0f, gesture.slide)
    }

    @Test
    fun aHoldShorterThanTheMinimumIsATap() {
        val gesture = RecorderGestureState()

        gesture.press()
        val outcome = gesture.release(durationMs = 100) // below the 350 ms minimum

        assertEquals(Outcome.TAP, outcome)
        assertEquals(Phase.IDLE, gesture.phase)
    }

    @Test
    fun slidingPastTheCancelFractionArmsCancelAndReleasingCancels() {
        val gesture = RecorderGestureState()

        gesture.press()
        gesture.drag(fraction = 0.6f) // past the 0.5 cancel fraction

        assertEquals(Phase.CANCELED, gesture.phase)
        assertEquals(Outcome.CANCELED, gesture.release(durationMs = 2000))
    }

    @Test
    fun droppingBackBelowTheCancelFractionDisarmsCancelAndTheHoldSends() {
        val gesture = RecorderGestureState()

        gesture.press()
        gesture.drag(fraction = 0.6f)
        assertEquals(Phase.CANCELED, gesture.phase)
        gesture.drag(fraction = 0.2f) // the finger came back down: cancel is disarmed

        assertEquals(Phase.RECORDING, gesture.phase)
        assertEquals(Outcome.SENT, gesture.release(durationMs = 2000))
    }

    @Test
    fun dragFractionsAreClampedToTheUnitRange() {
        val gesture = RecorderGestureState()

        gesture.press()
        gesture.drag(fraction = 5f)
        assertEquals(1f, gesture.slide)

        gesture.drag(fraction = -3f)
        assertEquals(0f, gesture.slide)
        assertEquals(Phase.RECORDING, gesture.phase)
    }

    @Test
    fun theRecorderTimerFormatsAsClampedMmSs() {
        assertEquals("00:00", formatRecorderElapsed(0L))
        assertEquals("01:01", formatRecorderElapsed(61_000L))
        assertEquals("00:00", formatRecorderElapsed(-5L)) // negative time clamps to zero
        assertEquals("59:59", formatRecorderElapsed(3_599_000L))
    }
}
