package app.memo.platform.android.voice

/**
 * Fixed-capacity FIFO of PCM16 samples. Pure: no Android types, so it is unit-tested on the JVM.
 *
 * - [write] never blocks. When the buffer is full, the oldest samples are dropped to make room,
 *   and [droppedSamples] counts them. A capture thread can therefore keep running while a slow
 *   consumer falls behind.
 * - [read] returns samples in the order they were written and never returns a sample twice.
 * - Thread-safe for one writer and one reader (both methods lock briefly, nothing else).
 *
 * @param capacity maximum number of samples held at once; must be positive.
 */
class RingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = ShortArray(capacity)
    private var head = 0 // index of the oldest sample
    private var size = 0
    private var dropped = 0L
    private val lock = Any()

    /** Samples currently held. */
    val available: Int
        get() = synchronized(lock) { size }

    /** Total samples discarded because the buffer was full when they were written. */
    val droppedSamples: Long
        get() = synchronized(lock) { dropped }

    /**
     * Appends `src[offset until offset + length]`. If that does not fit, the oldest samples are
     * dropped first. A write longer than the capacity keeps only its newest [capacity] samples.
     */
    fun write(src: ShortArray, offset: Int = 0, length: Int = src.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "write range is outside the source" }
        synchronized(lock) {
            var start = offset
            var count = length
            if (count > capacity) {
                // Only the newest samples can survive; everything before them is dropped.
                dropped += (count - capacity).toLong()
                start += count - capacity
                count = capacity
            }
            val overflow = size + count - capacity
            if (overflow > 0) {
                head = (head + overflow) % capacity
                size -= overflow
                dropped += overflow.toLong()
            }
            var tail = (head + size) % capacity
            for (i in 0 until count) {
                data[tail] = src[start + i]
                tail = if (tail + 1 == capacity) 0 else tail + 1
            }
            size += count
        }
    }

    /**
     * Removes up to `dst.size - offset` oldest samples into `dst` starting at [offset], and
     * returns how many were read (0 when empty).
     */
    fun read(dst: ShortArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) { "read range is outside the destination" }
        synchronized(lock) {
            val n = minOf(length, size)
            for (i in 0 until n) {
                dst[offset + i] = data[head]
                head = if (head + 1 == capacity) 0 else head + 1
            }
            size -= n
            return n
        }
    }

    /** Discards everything held. The dropped counter is kept. */
    fun clear() {
        synchronized(lock) {
            head = 0
            size = 0
        }
    }
}
