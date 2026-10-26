package app.memo.platform.android.llm.llama

/**
 * Finds stop sequences in streamed answer text. Text that could still be the start
 * of a stop sequence is held back until the next chunk decides it. When a stop
 * sequence appears, the text before it is returned, and [stopped] becomes true.
 * The stop sequence and anything after it are never returned.
 *
 * Not thread-safe: use one instance per generation.
 */
internal class StopMatcher(private val stops: List<String>) {

    private var held = ""

    /** True once a stop sequence has been found. */
    var stopped: Boolean = false
        private set

    /** Feeds one chunk and returns the text that is safe to emit now. */
    fun feed(chunk: String): String {
        if (stopped) return ""
        if (stops.isEmpty()) return chunk
        held += chunk
        val earliest = stops.mapNotNull { s -> held.indexOf(s).takeIf { it >= 0 } }.minOrNull()
        if (earliest != null) {
            stopped = true
            val out = held.substring(0, earliest)
            held = ""
            return out
        }
        val keep = longestHeldPrefix()
        val out = held.substring(0, held.length - keep)
        held = held.substring(held.length - keep)
        return out
    }

    /** Returns held-back text at the end of the stream, unless a stop sequence ended it. */
    fun finish(): String {
        val out = if (stopped) "" else held
        held = ""
        return out
    }

    /** Length of the longest suffix of [held] that is a proper prefix of some stop sequence. */
    private fun longestHeldPrefix(): Int {
        var keep = 0
        for (s in stops) {
            val max = minOf(held.length, s.length - 1)
            for (k in max downTo keep + 1) {
                if (held.endsWith(s.substring(0, k))) {
                    keep = k
                    break
                }
            }
        }
        return keep
    }
}
