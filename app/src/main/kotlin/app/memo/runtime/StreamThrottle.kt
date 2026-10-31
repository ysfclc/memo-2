package app.memo.runtime

/**
 * Lets a stream publish at most once per [minIntervalMs]. Pure Kotlin: the caller supplies the
 * clock, so tests drive time by hand.
 *
 * Not thread-safe; callers must serialize calls (ChatService calls it under the job lock).
 */
class StreamThrottle(
    private val minIntervalMs: Long = 100,
    private val clock: () -> Long,
) {
    private var lastPublishAt: Long? = null

    /** True for the first call and whenever at least [minIntervalMs] passed since the last true. */
    fun shouldPublish(): Boolean {
        val now = clock()
        val last = lastPublishAt
        if (last != null && now - last < minIntervalMs) return false
        lastPublishAt = now
        return true
    }

    /** Forgets the last publish, so the next [shouldPublish] call returns true. */
    fun reset() {
        lastPublishAt = null
    }
}
