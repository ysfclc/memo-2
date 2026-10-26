package app.memo.core.route

import org.json.JSONArray
import org.json.JSONObject

/**
 * Limits for one target. [minIntervalMs] = 0 disables the interval rule; [dailyCap] = 0 disables the cap.
 * The daily cap is a ROLLING 24 h window measured in epoch milliseconds (not a calendar day), so the
 * result does not depend on a time zone and needs no ZoneId inside core.
 */
data class RateLimit(val minIntervalMs: Long, val dailyCap: Int) {
    init {
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0" }
        require(dailyCap >= 0) { "dailyCap must be >= 0" }
    }

    companion object {
        const val WINDOW_MS: Long = 24L * 60L * 60L * 1000L

        /** Default for web and app targets: 20 s between actions, at most 60 per rolling day. */
        val DEFAULT: RateLimit = RateLimit(minIntervalMs = 20_000L, dailyCap = 60)

        /** No interval and no cap (use for on-device targets). */
        val UNLIMITED: RateLimit = RateLimit(minIntervalMs = 0L, dailyCap = 0)
    }
}

sealed interface RateDecision {
    data object Allowed : RateDecision

    /** Too soon after the last action; retry at [untilMs]. */
    data class Wait(val untilMs: Long) : RateDecision

    /** Rolling daily cap reached; the next action is allowed at [resetAt]. */
    data class CapReached(val resetAt: Long) : RateDecision
}

/**
 * Per-target action limiter. Time is always supplied as [now] (epoch ms); nothing reads the clock.
 *
 * Usage: call [check] before acting, and [record] after the action happened. [check] never mutates.
 * Memory: per target it keeps the last action time plus, only when a daily cap applies, the action
 * times inside the rolling window, trimmed to at most `dailyCap` entries. Trimming drops the oldest
 * entries, so an over-recorded target reports a later reset (the conservative direction).
 *
 * Not thread-safe; the caller serializes access (core is coroutine-free).
 */
class RateLimiter(
    initialLimits: Map<String, RateLimit> = emptyMap(),
    private val defaultLimit: RateLimit = RateLimit.DEFAULT,
) {
    private val limits = HashMap<String, RateLimit>(initialLimits)
    private val lastAt = HashMap<String, Long>()
    private val windowAt = HashMap<String, MutableList<Long>>()

    fun limitFor(targetId: String): RateLimit = limits[targetId] ?: defaultLimit

    fun configure(targetId: String, limit: RateLimit) {
        limits[targetId] = limit
    }

    fun check(targetId: String, now: Long): RateDecision {
        val limit = limitFor(targetId)
        clampFuture(targetId, now)
        if (limit.dailyCap > 0) {
            val inWindow = windowAt[targetId].orEmpty().filter { it > now - RateLimit.WINDOW_MS }
            if (inWindow.size >= limit.dailyCap) {
                val oldest = inWindow[inWindow.size - limit.dailyCap]
                return RateDecision.CapReached(oldest + RateLimit.WINDOW_MS)
            }
        }
        val last = lastAt[targetId]
        if (limit.minIntervalMs > 0 && last != null && now - last < limit.minIntervalMs) {
            return RateDecision.Wait(last + limit.minIntervalMs)
        }
        return RateDecision.Allowed
    }

    /**
     * Clock moved back: a stored action time after [now] is repaired to [now], so it blocks at
     * most one interval or window from now instead of until the stored future time.
     */
    private fun clampFuture(targetId: String, now: Long) {
        lastAt[targetId]?.let { if (it > now) lastAt[targetId] = now }
        windowAt[targetId]?.replaceAll { minOf(it, now) }
    }

    fun record(targetId: String, now: Long) {
        lastAt[targetId] = now
        val limit = limitFor(targetId)
        if (limit.dailyCap <= 0) return
        val list = windowAt.getOrPut(targetId) { ArrayList() }
        list.add(now)
        list.sort()
        list.removeAll { it <= now - RateLimit.WINDOW_MS }
        while (list.size > limit.dailyCap) list.removeAt(0)
    }

    /** Serializes the state (last action times and window entries). Limits are configuration, not state. */
    fun toJson(): JSONObject {
        val lastObj = JSONObject()
        for ((id, t) in lastAt) lastObj.put(id, t)
        val windowObj = JSONObject()
        for ((id, list) in windowAt) windowObj.put(id, JSONArray(list))
        return JSONObject().put("last", lastObj).put("window", windowObj)
    }

    companion object {
        /** Restores state written by [toJson]. Limits are supplied again by the caller. */
        fun fromJson(
            raw: JSONObject,
            initialLimits: Map<String, RateLimit> = emptyMap(),
            defaultLimit: RateLimit = RateLimit.DEFAULT,
        ): RateLimiter {
            val limiter = RateLimiter(initialLimits, defaultLimit)
            val lastObj = raw.optJSONObject("last")
            if (lastObj != null) {
                for (id in lastObj.keys()) limiter.lastAt[id] = lastObj.optLong(id)
            }
            val windowObj = raw.optJSONObject("window")
            if (windowObj != null) {
                for (id in windowObj.keys()) {
                    val arr = windowObj.optJSONArray(id) ?: continue
                    val list = ArrayList<Long>()
                    for (i in 0 until arr.length()) list.add(arr.optLong(i))
                    list.sort()
                    limiter.windowAt[id] = list
                }
            }
            return limiter
        }
    }
}
