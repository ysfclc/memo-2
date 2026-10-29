package app.memo.platform.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.io.IOException

/** `../` and absolute entries must be rejected; sane relative paths must resolve inside [root]. */
class ArchiveGuardTest {

    @Test
    fun dotDotEntriesAreRejected() {
        assertNull(ArchiveGuard.safeEntryPath("../model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("good/../../model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("good/..\\model.onnx"))
    }

    @Test
    fun absoluteAndDriveEntriesAreRejected() {
        assertNull(ArchiveGuard.safeEntryPath("/model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("\\model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("C:model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("C:/model.onnx"))
    }

    @Test
    fun nullByteAndEmptyEntriesAreRejected() {
        assertNull(ArchiveGuard.safeEntryPath("\u0000model.onnx"))
        assertNull(ArchiveGuard.safeEntryPath("model.onnx\u0000"))
        assertNull(ArchiveGuard.safeEntryPath(""))
    }

    @Test
    fun normalRelativePathsAreAccepted() {
        assertEquals("model.onnx", ArchiveGuard.safeEntryPath("./model.onnx"))
        assertEquals("model.onnx", ArchiveGuard.safeEntryPath("model.onnx"))
        assertEquals("kokoro/model.onnx", ArchiveGuard.safeEntryPath("kokoro/model.onnx"))
        assertEquals("kokoro/model.onnx", ArchiveGuard.safeEntryPath("kokoro\\model.onnx"))
        assertEquals("kokoro/model.onnx", ArchiveGuard.safeEntryPath("./kokoro/./model.onnx"))
    }

    @Test
    fun escapeEntryIsRefusedOnResolution() {
        val root = File(".", "voice-root")
        assertFailsWith<IOException> { ArchiveGuard.resolveInside(root, "../model.onnx") }
    }

    @Test
    fun entryResolvesInsideRoot() {
        val root = File(".", "voice-root")
        val target = ArchiveGuard.resolveInside(root, "kokoro/model.onnx")
        assertTrue(target.path.startsWith(root.canonicalFile.path + File.separator), target.path)
        assertFalse(File(root, "kokoro/model.onnx").canonicalFile != target)
    }
}
