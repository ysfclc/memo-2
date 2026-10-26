package app.memo.core.route

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecondReviewRouteTest {

    private val now = 1_000_000_000L
    private val day = RateLimit.WINDOW_MS

    @Test
    fun futureLastActionIsClampedToNowSoItCannotBlockIndefinitely() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 10_000L, dailyCap = 0)))
        limiter.record("web", now + 365L * day) // clock was moved back afterwards
        // Blocked for at most the minimum interval from now, never until the stored future time.
        assertEquals(RateDecision.Wait(now + 10_000L), limiter.check("web", now))
        assertEquals(RateDecision.Allowed, limiter.check("web", now + 10_000L))
    }

    @Test
    fun futureWindowEntriesAreClampedToNowForTheDailyCap() {
        val limiter = RateLimiter(mapOf("web" to RateLimit(minIntervalMs = 0L, dailyCap = 1)))
        limiter.record("web", now + 365L * day)
        // The cap resets within one window of now, not after the stored future time.
        assertEquals(RateDecision.CapReached(now + day), limiter.check("web", now))
        assertEquals(RateDecision.Allowed, limiter.check("web", now + day))
    }

    @Test
    fun restoredFutureTimestampsAreAlsoClamped() {
        val raw = JSONObject().put("last", JSONObject().put("web", now + 365L * day))
        val limiter = RateLimiter.fromJson(raw, mapOf("web" to RateLimit(minIntervalMs = 5_000L, dailyCap = 0)))
        assertEquals(RateDecision.Wait(now + 5_000L), limiter.check("web", now))
    }

    @Test
    fun providerHealthRejectsNaNAndNegativeValues() {
        val raw = JSONObject().put(
            "targets",
            JSONObject().put(
                "grok",
                JSONObject()
                    .put("successes", -4)
                    .put("failures", 2)
                    .put("consecutiveFailures", -1)
                    .put("tripCount", -9)
                    .put("lastFailureAt", -100L)
                    .put("openUntil", -5L)
                    .put("avgLatencyMs", "NaN"),
            ),
        )
        val target = ProviderHealth.fromJson(raw).get("grok")
        assertEquals(0, target.successes)
        assertEquals(2, target.failures)
        assertEquals(0, target.consecutiveFailures)
        assertEquals(0, target.tripCount)
        assertEquals(0L, target.lastFailureAt)
        assertEquals(0L, target.openUntil)
        assertNull(target.avgLatencyMs)
    }

    @Test
    fun providerHealthRejectsNegativeAndInfiniteLatency() {
        val negative = JSONObject().put("targets", JSONObject().put("a", JSONObject().put("avgLatencyMs", -3.0)))
        assertNull(ProviderHealth.fromJson(negative).get("a").avgLatencyMs)
        val infinite = JSONObject().put("targets", JSONObject().put("b", JSONObject().put("avgLatencyMs", "Infinity")))
        assertNull(ProviderHealth.fromJson(infinite).get("b").avgLatencyMs)
    }

    @Test
    fun providerHealthKeepsValidLatency() {
        val raw = JSONObject().put("targets", JSONObject().put("c", JSONObject().put("avgLatencyMs", 1234.5)))
        assertEquals(1234.5, ProviderHealth.fromJson(raw).get("c").avgLatencyMs)
    }
}
