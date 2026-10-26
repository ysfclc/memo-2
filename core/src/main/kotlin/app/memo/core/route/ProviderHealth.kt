package app.memo.core.route

import org.json.JSONObject

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/**
 * Health counters for one target.
 * - [tripCount]: circuit trips since the last recovery; drives the cool-down doubling.
 * - [openUntil]: epoch ms until which the circuit is open; null when closed.
 * - [avgLatencyMs]: EWMA (alpha 0.2) of observed latencies; null before the first sample.
 */
data class TargetHealth(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastFailureAt: Long? = null,
    val avgLatencyMs: Double? = null,
    val tripCount: Int = 0,
    val openUntil: Long? = null,
)

/**
 * Per-target health with a circuit breaker. Time is supplied as [now] (epoch ms).
 *
 * Circuit: 3 consecutive failures open it for a cool-down of 5 min, doubling on each re-trip up to a
 * 60 min cap (5, 10, 20, 40, 60, 60...). After the cool-down the circuit is HALF_OPEN: the target is
 * eligible again, and the next recorded result decides. A success closes the circuit and resets the
 * trip count. A failure while HALF_OPEN re-opens it with the next, longer cool-down.
 *
 * Score in 0..1: a Laplace-smoothed success rate, reduced for consecutive failures, a recent failure,
 * high latency and HALF_OPEN; 0 while OPEN. Unknown targets score about 0.67.
 *
 * Not thread-safe; the caller serializes access.
 */
class ProviderHealth {
    private val byTarget = HashMap<String, TargetHealth>()

    fun get(targetId: String): TargetHealth = byTarget[targetId] ?: TargetHealth()

    fun circuitState(targetId: String, now: Long): CircuitState {
        val until = get(targetId).openUntil ?: return CircuitState.CLOSED
        return if (now < until) CircuitState.OPEN else CircuitState.HALF_OPEN
    }

    fun record(targetId: String, success: Boolean, latencyMs: Long, now: Long) {
        val h = get(targetId)
        val prevAvg = h.avgLatencyMs
        val avg: Double? = when {
            latencyMs < 0 -> prevAvg
            prevAvg == null -> latencyMs.toDouble()
            else -> prevAvg + EWMA_ALPHA * (latencyMs - prevAvg)
        }
        val next = if (success) {
            h.copy(
                successes = h.successes + 1,
                consecutiveFailures = 0,
                avgLatencyMs = avg,
                tripCount = 0,
                openUntil = null,
            )
        } else {
            val consecutive = h.consecutiveFailures + 1
            val until = h.openUntil
            val halfOpen = until != null && now >= until
            val tripsNow = until == null && consecutive >= FAILURE_THRESHOLD
            val reopen = halfOpen || tripsNow
            val trips = if (reopen) h.tripCount + 1 else h.tripCount
            h.copy(
                failures = h.failures + 1,
                consecutiveFailures = consecutive,
                lastFailureAt = now,
                avgLatencyMs = avg,
                tripCount = trips,
                openUntil = if (reopen) now + cooldownMs(trips) else until,
            )
        }
        byTarget[targetId] = next
    }

    fun score(targetId: String, now: Long): Double {
        val h = get(targetId)
        val state = circuitState(targetId, now)
        if (state == CircuitState.OPEN) return 0.0
        val total = h.successes + h.failures
        var s = (h.successes + 2.0) / (total + 3.0)
        if (h.consecutiveFailures > 0) s *= 1.0 - 0.25 * minOf(h.consecutiveFailures, 2)
        val lastFailure = h.lastFailureAt
        if (lastFailure != null && now - lastFailure < RECENT_FAILURE_MS) s *= 0.9
        val avg = h.avgLatencyMs
        if (avg != null && avg > SLOW_LATENCY_MS) s *= 0.8
        if (state == CircuitState.HALF_OPEN) s *= 0.5
        return s.coerceIn(0.0, 1.0)
    }

    /** Cool-down after the [trips]-th trip: 5 min doubling to a 60 min cap. */
    fun cooldownMs(trips: Int): Long {
        var ms = COOLDOWN_BASE_MS
        repeat(trips - 1) { ms = minOf(ms * 2, COOLDOWN_CAP_MS) }
        return minOf(ms, COOLDOWN_CAP_MS)
    }

    fun toJson(): JSONObject {
        val targets = JSONObject()
        for ((id, h) in byTarget) {
            val o = JSONObject()
                .put("successes", h.successes)
                .put("failures", h.failures)
                .put("consecutiveFailures", h.consecutiveFailures)
                .put("tripCount", h.tripCount)
            h.lastFailureAt?.let { o.put("lastFailureAt", it) }
            h.avgLatencyMs?.let { o.put("avgLatencyMs", it) }
            h.openUntil?.let { o.put("openUntil", it) }
            targets.put(id, o)
        }
        return JSONObject().put("targets", targets)
    }

    companion object {
        const val FAILURE_THRESHOLD: Int = 3
        const val COOLDOWN_BASE_MS: Long = 5L * 60L * 1000L
        const val COOLDOWN_CAP_MS: Long = 60L * 60L * 1000L
        const val EWMA_ALPHA: Double = 0.2
        const val RECENT_FAILURE_MS: Long = 10L * 60L * 1000L
        const val SLOW_LATENCY_MS: Double = 30_000.0

        /**
         * Restores persisted health. Counters and timestamps are clamped to >= 0. A
         * latency that is NaN, infinite or negative is treated as "no sample yet"
         * (null), so a damaged file can never poison the score.
         */
        fun fromJson(raw: JSONObject): ProviderHealth {
            val health = ProviderHealth()
            val targets = raw.optJSONObject("targets") ?: return health
            for (id in targets.keys()) {
                val o = targets.optJSONObject(id) ?: continue
                val latency = if (o.has("avgLatencyMs")) o.optDouble("avgLatencyMs") else Double.NaN
                health.byTarget[id] = TargetHealth(
                    successes = o.optInt("successes", 0).coerceAtLeast(0),
                    failures = o.optInt("failures", 0).coerceAtLeast(0),
                    consecutiveFailures = o.optInt("consecutiveFailures", 0).coerceAtLeast(0),
                    lastFailureAt = if (o.has("lastFailureAt")) o.optLong("lastFailureAt").coerceAtLeast(0L) else null,
                    avgLatencyMs = latency.takeIf { it.isFinite() && it >= 0.0 },
                    tripCount = o.optInt("tripCount", 0).coerceAtLeast(0),
                    openUntil = if (o.has("openUntil")) o.optLong("openUntil").coerceAtLeast(0L) else null,
                )
            }
            return health
        }
    }
}
