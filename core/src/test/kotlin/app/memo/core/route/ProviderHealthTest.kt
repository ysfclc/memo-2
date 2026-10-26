package app.memo.core.route

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHealthTest {

    private val t0 = 1_000_000_000L
    private val min = 60_000L

    @Test
    fun threeConsecutiveFailuresOpenTheCircuit() {
        val health = ProviderHealth()
        health.record("chatgpt", false, -1L, t0)
        health.record("chatgpt", false, -1L, t0 + 1L)
        assertEquals(CircuitState.CLOSED, health.circuitState("chatgpt", t0 + 2L))
        health.record("chatgpt", false, -1L, t0 + 2L)
        assertEquals(CircuitState.OPEN, health.circuitState("chatgpt", t0 + 3L))
        assertEquals(0.0, health.score("chatgpt", t0 + 3L))
    }

    @Test
    fun openCircuitHalfOpensAfterFiveMinutes() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, t0) }
        val until = t0 + 5 * min
        assertEquals(until, health.get("chatgpt").openUntil)
        assertEquals(CircuitState.OPEN, health.circuitState("chatgpt", until - 1L))
        assertEquals(CircuitState.HALF_OPEN, health.circuitState("chatgpt", until))
    }

    @Test
    fun successInHalfOpenClosesTheCircuitAndResetsTheTripCount() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, t0) }
        val until = t0 + 5 * min
        health.record("chatgpt", true, 800L, until)
        assertEquals(CircuitState.CLOSED, health.circuitState("chatgpt", until))
        val h = health.get("chatgpt")
        assertEquals(0, h.consecutiveFailures)
        assertEquals(0, h.tripCount)
        assertNull(h.openUntil)
    }

    @Test
    fun failureInHalfOpenReopensWithDoubledCooldown() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, t0) }
        val until = t0 + 5 * min
        health.record("chatgpt", false, -1L, until)
        assertEquals(until + 10 * min, health.get("chatgpt").openUntil)
        assertEquals(CircuitState.OPEN, health.circuitState("chatgpt", until + 1L))
    }

    @Test
    fun cooldownDoublesFromFiveMinutesToTheSixtyMinuteCap() {
        val health = ProviderHealth()
        val expectedMinutes = listOf(5L, 10L, 20L, 40L, 60L, 60L)
        assertEquals(expectedMinutes.map { it * min }, (1..6).map { health.cooldownMs(it) })
    }

    @Test
    fun repeatedHalfOpenFailuresFollowTheCooldownSequence() {
        val health = ProviderHealth()
        repeat(3) { health.record("p", false, -1L, t0) }
        var now = t0
        for (minutes in listOf(5L, 10L, 20L, 40L, 60L, 60L)) {
            val until = health.get("p").openUntil!!
            assertEquals(now + minutes * min, until)
            now = until
            health.record("p", false, -1L, now)
        }
    }

    @Test
    fun successBetweenFailuresResetsTheConsecutiveCount() {
        val health = ProviderHealth()
        health.record("p", false, -1L, t0)
        health.record("p", false, -1L, t0 + 1L)
        health.record("p", true, 100L, t0 + 2L)
        health.record("p", false, -1L, t0 + 3L)
        health.record("p", false, -1L, t0 + 4L)
        assertEquals(CircuitState.CLOSED, health.circuitState("p", t0 + 5L))
        assertEquals(2, health.get("p").consecutiveFailures)
    }

    @Test
    fun latencyIsAnEwmaWithAlphaPointTwo() {
        val health = ProviderHealth()
        health.record("p", true, 1_000L, t0)
        assertEquals(1_000.0, health.get("p").avgLatencyMs!!, 1e-9)
        health.record("p", true, 2_000L, t0 + 1L)
        assertEquals(1_200.0, health.get("p").avgLatencyMs!!, 1e-9)
        health.record("p", true, -1L, t0 + 2L)
        assertEquals(1_200.0, health.get("p").avgLatencyMs!!, 1e-9)
    }

    @Test
    fun scoreStaysInRangeAndFailuresLowerIt() {
        val health = ProviderHealth()
        val unknown = health.score("p", t0)
        assertTrue(unknown > 0.6 && unknown < 0.7, "unknown target score was $unknown")
        health.record("p", false, -1L, t0)
        health.record("p", false, -1L, t0 + 1L)
        val failing = health.score("p", t0 + 2L)
        assertTrue(failing in 0.0..1.0 && failing < unknown)
        repeat(50) { health.record("q", true, 100L, t0 + it.toLong()) }
        val healthy = health.score("q", t0 + 60L)
        assertTrue(healthy in 0.0..1.0 && healthy > unknown)
    }

    @Test
    fun jsonRoundTripKeepsStateAndScores() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, t0) }
        health.record("claude", true, 1_234L, t0)
        health.record("claude", true, 2_345L, t0 + 1L)
        val restored = ProviderHealth.fromJson(JSONObject(health.toJson().toString()))
        for (id in listOf("chatgpt", "claude", "never-seen")) {
            assertEquals(health.get(id), restored.get(id))
            assertEquals(health.circuitState(id, t0 + 2L), restored.circuitState(id, t0 + 2L))
            assertEquals(health.score(id, t0 + 2L), restored.score(id, t0 + 2L))
        }
    }
}
