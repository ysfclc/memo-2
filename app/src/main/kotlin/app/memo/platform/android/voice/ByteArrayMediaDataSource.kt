package app.memo.platform.android.voice

import android.media.MediaDataSource

/**
 * An in-memory [MediaDataSource]: lets [MediaPlayer] read a voice-message clip from the
 * decrypted bytes handed out by the encrypted [app.memo.core.platform.Vault], with no
 * plaintext file ever landing on disk. `readAt` returns -1 at or after the end, as the
 * platform contract requires; `close` holds no native resources and does nothing.
 */
class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {

    override fun getSize(): Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        copyAt(bytes, position, buffer, offset, size)

    override fun close() {
        // No resources to release: the bytes are owned by the caller.
    }

    internal companion object {
        /**
         * The pure form of [readAt]'s copy: how many bytes of [src] from [position] fit into
         * [dst] at [offset] given the requested [size]. Returns -1 when [position] is at or
         * past the end of [src], per the [MediaDataSource] contract.
         */
        internal fun copyAt(src: ByteArray, position: Long, dst: ByteArray, offset: Int, size: Int): Int {
            if (position >= src.size) return -1
            val count = minOf(size, src.size - position.toInt())
            System.arraycopy(src, position.toInt(), dst, offset, count)
            return count
        }
    }
}
