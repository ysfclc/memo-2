package app.memo.core.route

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterTest {

    private val t0 = 1_000_000_000L
    private val day = RateLimit.WINDOW_MS

    @Test
    fun minimumIntervalWaitsThenAllows() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 10_000L, dailyCap = 0)))
        assertEquals(RateDecision.Allowed, limiter.check("web", t0))
        limiter.record("web", t0)
        assertEquals(RateDecision.Wait(t0 + 10_000L), limiter.check("web", t0 + 2_000L))
        assertEquals(RateDecision.Allowed, limiter.check("web", t0 + 10_000L))
    }

    @Test
    fun dailyCapIsRollingAndResetsWhenTheOldestEntryLeavesTheWindow() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 0L, dailyCap = 2)))
        limiter.record("web", t0)
        limiter.record("web", t0 + 1_000L)
        assertEquals(RateDecision.CapReached(t0 + day), limiter.check("web", t0 + 2_000L))
        assertEquals(RateDecision.CapReached(t0 + day), limiter.check("web", t0 + day - 1L))
        // the entry at t0 leaves the window exactly at t0 + day
        assertEquals(RateDecision.Allowed, limiter.check("web", t0 + day))
    }

    @Test
    fun capReachedTakesPriorityOverInterval() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 60_000L, dailyCap = 1)))
        limiter.record("web", t0)
        assertEquals(RateDecision.CapReached(t0 + day), limiter.check("web", t0 + 1_000L))
    }

    @Test
    fun unlimitedTargetNeverWaitsOrCaps() {
        val limiter = RateLimiter(mapOf("local:m" to RateLimit.UNLIMITED))
        repeat(500) { i -> limiter.record("local:m", t0 + i.toLong()) }
        assertEquals(RateDecision.Allowed, limiter.check("local:m", t0 + 500L))
    }

    @Test
    fun unknownTargetUsesTheDefaultLimit() {
        assertEquals(RateLimit.DEFAULT, RateLimiter().limitFor("anything"))
        val limiter = RateLimiter(defaultLimit = RateLimit(minIntervalMs = 5_000L, dailyCap = 0))
        limiter.record("x", t0)
        assertEquals(RateDecision.Wait(t0 + 5_000L), limiter.check("x", t0 + 1L))
    }

    @Test
    fun configureOverridesOneTarget() {
        val limiter = RateLimiter()
        limiter.configure("web", RateLimit(minIntervalMs = 0L, dailyCap = 1))
        limiter.record("web", t0)
        assertEquals(RateDecision.CapReached(t0 + day), limiter.check("web", t0 + 1L))
        assertEquals(RateLimit.DEFAULT, limiter.limitFor("other"))
    }

    @Test
    fun memoryIsBoundedToTheDailyCap() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 0L, dailyCap = 5)))
        repeat(100) { i -> limiter.record("web", t0 + i.toLong()) }
        val stored = limiter.toJson().getJSONObject("window").getJSONArray("web").length()
        assertTrue(stored <= 5, "stored $stored window entries, cap is 5")
    }

    @Test
    fun entriesOlderThanTheWindowArePruned() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 0L, dailyCap = 10)))
        limiter.record("web", t0)
        limiter.record("web", t0 + day + 1L)
        val stored = limiter.toJson().getJSONObject("window").getJSONArray("web").length()
        assertEquals(1, stored)
    }

    @Test
    fun jsonRoundTripPreservesDecisions() {
        val limits = mapOf("web" to RateLimit(minIntervalMs = 10_000L, dailyCap = 3))
        val limiter = RateLimiter(limits)
        limiter.record("web", t0)
        limiter.record("web", t0 + 4_000L)
        limiter.record("local", t0 + 1L)
        val restored = RateLimiter.fromJson(JSONObject(limiter.toJson().toString()), limits)
        for (at in listOf(t0 + 1_000L, t0 + 10_000L, t0 + day - 5L, t0 + day + 1L)) {
            assertEquals(limiter.check("web", at), restored.check("web", at))
            assertEquals(limiter.check("local", at), restored.check("local", at))
        }
    }
}
