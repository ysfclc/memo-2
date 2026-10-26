package app.memo.platform.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RingBufferTest {

    private fun samples(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun readReturnsWrittenSamplesInOrder() {
        val ring = RingBuffer(8)
        ring.write(samples(1, 5))
        val out = ShortArray(5)
        assertEquals(5, ring.read(out))
        assertEquals(samples(1, 5).toList(), out.toList())
        assertEquals(0, ring.available)
    }

    @Test
    fun wrapAroundKeepsOrder() {
        val ring = RingBuffer(4)
        ring.write(samples(1, 3))
        val first = ShortArray(2)
        ring.read(first)
        ring.write(samples(4, 3)) // now wraps past the end of the array
        val out = ShortArray(4)
        assertEquals(4, ring.read(out))
        assertEquals(listOf<Short>(3, 4, 5, 6), out.toList())
        assertEquals(0L, ring.droppedSamples)
    }

    @Test
    fun overrunDropsOldestAndCountsThem() {
        val ring = RingBuffer(4)
        ring.write(samples(1, 4))
        ring.write(samples(5, 2)) // drops 1 and 2
        assertEquals(2L, ring.droppedSamples)
        assertEquals(4, ring.available)
        val out = ShortArray(4)
        ring.read(out)
        assertEquals(listOf<Short>(3, 4, 5, 6), out.toList())
    }

    @Test
    fun writeLargerThanCapacityKeepsNewestSamples() {
        val ring = RingBuffer(3)
        ring.write(samples(1, 7))
        assertEquals(4L, ring.droppedSamples)
        val out = ShortArray(3)
        ring.read(out)
        assertEquals(listOf<Short>(5, 6, 7), out.toList())
    }

    @Test
    fun readNeverReturnsSampleTwice() {
        val ring = RingBuffer(8)
        ring.write(samples(10, 4))
        val a = ShortArray(3)
        val b = ShortArray(3)
        assertEquals(3, ring.read(a))
        assertEquals(1, ring.read(b))
        assertEquals(listOf<Short>(10, 11, 12), a.toList())
        assertEquals(listOf<Short>(13, 0, 0), b.toList())
        assertEquals(0, ring.read(ShortArray(2)))
    }

    @Test
    fun clearEmptiesButKeepsDropCount() {
        val ring = RingBuffer(2)
        ring.write(samples(1, 5))
        ring.clear()
        assertEquals(0, ring.available)
        assertTrue(ring.droppedSamples > 0)
    }

    @Test
    fun rangeArgumentsAreChecked() {
        val ring = RingBuffer(4)
        assertFailsWith<IllegalArgumentException> { ring.write(ShortArray(2), 1, 2) }
        assertFailsWith<IllegalArgumentException> { ring.read(ShortArray(2), 1, 2) }
        assertFailsWith<IllegalArgumentException> { RingBuffer(0) }
    }
}
