package app.memo.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamThrottleTest {

    private var now = 0L
    private val throttle = StreamThrottle(clock = { now })

    @Test
    fun firstCallPublishes() {
        assertTrue(throttle.shouldPublish())
    }

    @Test
    fun callsWithinIntervalDoNotPublish() {
        assertTrue(throttle.shouldPublish())
        now = 1
        assertFalse(throttle.shouldPublish())
        now = 99
        assertFalse(throttle.shouldPublish())
    }

    @Test
    fun publishesAgainOnceIntervalPassed() {
        assertTrue(throttle.shouldPublish())
        now = 100
        assertTrue(throttle.shouldPublish())
        now = 150
        assertFalse(throttle.shouldPublish())
        now = 200
        assertTrue(throttle.shouldPublish())
    }

    @Test
    fun resetMakesNextCallPublish() {
        assertTrue(throttle.shouldPublish())
        now = 10
        assertFalse(throttle.shouldPublish())
        throttle.reset()
        assertTrue(throttle.shouldPublish())
    }
}
