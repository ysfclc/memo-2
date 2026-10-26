package app.memo.ui.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReduceMotionTest {

    @Test
    fun animatorScaleZeroOrUserFlagReducesMotion() {
        assertTrue(reducedMotion(animatorScale = 0f, userFlag = false))
        assertTrue(reducedMotion(animatorScale = 1f, userFlag = true))
        assertTrue(reducedMotion(animatorScale = 0f, userFlag = true))
        assertFalse(reducedMotion(animatorScale = 1f, userFlag = false))
        assertFalse(reducedMotion(animatorScale = 0.5f, userFlag = false))
    }

    @Test
    fun everySpecCollapsesToSnapWhenReduced() {
        MotionFamily.entries.filter { it != MotionFamily.AMBIENT }.forEach { family ->
            assertEquals(ResolvedMotion.SNAP, resolveMotion(family, reduced = true), "family $family")
        }
    }

    @Test
    fun ambientLoopsStopWhenReduced() {
        assertEquals(ResolvedMotion.NONE, resolveMotion(MotionFamily.AMBIENT, reduced = true))
    }

    @Test
    fun liveSpecsKeepTheirPhysics() {
        listOf(MotionFamily.SNAPPY, MotionFamily.SMOOTH, MotionFamily.GENTLE, MotionFamily.BOUNCY).forEach {
            assertEquals(ResolvedMotion.SPRING, resolveMotion(it, reduced = false), "family $it")
        }
        listOf(
            MotionFamily.MICRO, MotionFamily.SHORT, MotionFamily.MEDIUM, MotionFamily.LONG,
            MotionFamily.ENTRANCE, MotionFamily.AMBIENT,
        ).forEach {
            assertEquals(ResolvedMotion.TWEEN, resolveMotion(it, reduced = false), "family $it")
        }
    }

    @Test
    fun staggerIndexIsCappedAtNine() {
        assertEquals(9, staggerIndex(42))
        assertEquals(9, staggerIndex(9))
        assertEquals(8, staggerIndex(8))
        assertEquals(0, staggerIndex(-3))
    }

    @Test
    fun staggerDelayIsIndexTimes28() {
        assertEquals(0, staggerDelayMs(0))
        assertEquals(84, staggerDelayMs(3))
        assertEquals(9 * 28, staggerDelayMs(9))
        assertEquals(9 * 28, staggerDelayMs(42))
    }

    @Test
    fun staggerDelayIsZeroWhenReduced() {
        assertEquals(0, staggerDelayMs(5, reduced = true))
    }

    @Test
    fun motionTokensMatchSection2i() {
        assertEquals(90, MemoMotion.MICRO_MS)
        assertEquals(180, MemoMotion.SHORT_MS)
        assertEquals(260, MemoMotion.MEDIUM_MS)
        assertEquals(360, MemoMotion.LONG_MS)
        assertEquals(230, MemoMotion.ENTRANCE_MS)
        assertEquals(1650, MemoMotion.ORB_BREATH_MS)
        assertEquals(1150, MemoMotion.SHIMMER_MS)
        assertEquals(0.965f, MemoMotion.PRESS_SCALE)
        assertEquals(SpringToken(0.85f, 700f), MemoMotion.snappySpring)
        assertEquals(SpringToken(0.9f, 380f), MemoMotion.smoothSpring)
        assertEquals(SpringToken(1.0f, 200f), MemoMotion.gentleSpring)
        assertEquals(SpringToken(0.6f, 500f), MemoMotion.bouncySpring)
    }

    @Test
    fun hapticCuesHaveDistinctPlatformConstants() {
        val constants = HapticCue.entries.map { hapticFeedbackConstant(it) }
        assertEquals(HapticCue.entries.size, constants.toSet().size)
    }
}
