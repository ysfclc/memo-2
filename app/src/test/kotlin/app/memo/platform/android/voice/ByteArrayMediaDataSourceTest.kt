package app.memo.platform.android.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * JVM test for [ByteArrayMediaDataSource]'s copy logic: full reads, reads into an offset of
 * the caller's buffer, short reads crossing the end, and the -1 at/after-end contract.
 */
class ByteArrayMediaDataSourceTest {

    private val src = byteArrayOf(10, 20, 30, 40, 50)

    @Test
    fun `full read from zero copies the whole source`() {
        val dst = ByteArray(5)
        val read = ByteArrayMediaDataSource.copyAt(src, 0L, dst, 0, 5)
        assertEquals(5, read)
        assertContentEquals(src, dst)
    }

    @Test
    fun `read lands at the requested offset inside the destination`() {
        val dst = ByteArray(4)
        val read = ByteArrayMediaDataSource.copyAt(src, 3L, dst, 1, 2)
        assertEquals(2, read)
        assertEquals(byteArrayOf(40, 50).toList(), dst.drop(1).take(2))
        assertEquals(0, dst[0])
        assertEquals(0, dst[3])
    }

    @Test
    fun `read crossing the end returns only the remaining bytes`() {
        val dst = ByteArray(10)
        val read = ByteArrayMediaDataSource.copyAt(src, 2L, dst, 0, 10)
        assertEquals(3, read)
        assertContentEquals(byteArrayOf(30, 40, 50), dst.copyOf(3))
    }

    @Test
    fun `position at exact end returns -1`() {
        assertEquals(-1, ByteArrayMediaDataSource.copyAt(src, src.size.toLong(), ByteArray(5), 0, 5))
    }

    @Test
    fun `position past end returns -1`() {
        assertEquals(-1, ByteArrayMediaDataSource.copyAt(src, (src.size + 7).toLong(), ByteArray(5), 0, 5))
    }

    @Test
    fun `requested size of zero returns zero bytes`() {
        val dst = ByteArray(4)
        assertEquals(0, ByteArrayMediaDataSource.copyAt(src, 0L, dst, 0, 0))
        assertContentEquals(ByteArray(4), dst)
    }
}
