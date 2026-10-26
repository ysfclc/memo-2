package app.memo.hub

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallSwapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var part: File
    private lateinit var target: File
    private lateinit var previous: File
    private lateinit var sidecar: File
    private lateinit var previousSidecar: File

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun setUp() {
        dir = tmp.newFolder("models")
        part = File(dir, "m.gguf.part")
        target = File(dir, "m.gguf")
        previous = File(dir, "m.gguf.previous")
        sidecar = File(dir, "m.gguf.sha256")
        previousSidecar = File(dir, "m.gguf.previous.sha256")
    }

    private fun installBytes(bytes: ByteArray, expectedBytes: ByteArray = bytes) {
        part.writeBytes(bytes)
        InstallSwap.install(
            part, target, previous, sidecar, previousSidecar,
            expectedSize = expectedBytes.size.toLong(),
            expectedSha256 = sha(expectedBytes),
            actualSize = bytes.size.toLong(),
            actualSha256 = sha(bytes),
        )
    }

    @Test
    fun successfulInstallSwapsInTheNewFileAndKeepsTheOldOneAsPrevious() {
        setUp()
        val v1 = "version one".toByteArray()
        val v2 = "version two!".toByteArray()
        installBytes(v1)
        assertEquals(sha(v1), sidecar.readText())

        installBytes(v2)
        assertTrue(target.readBytes().contentEquals(v2))
        assertTrue(previous.readBytes().contentEquals(v1))
        assertEquals(sha(v2), sidecar.readText())
        assertEquals(sha(v1), previousSidecar.readText())
        assertFalse(part.exists())
    }

    @Test
    fun hashMismatchKeepsTheOldFileAndDeletesThePart() {
        setUp()
        val good = "good model".toByteArray()
        installBytes(good)

        val corrupt = "tampered model".toByteArray() // same length as the expected bytes: a pure digest mismatch
        val expected = "expected model".toByteArray()
        assertFailsWith<IOException> { installBytes(corrupt, expectedBytes = expected) }

        assertFalse(part.exists(), ".part must be deleted on mismatch")
        assertTrue(target.readBytes().contentEquals(good), "active file must be untouched")
        assertEquals(sha(good), sidecar.readText())
        assertFalse(previous.exists(), "no previous file is created by a failed install")
    }

    @Test
    fun sizeMismatchIsRejectedBeforeAnySwap() {
        setUp()
        val good = "good".toByteArray()
        installBytes(good)
        val short = "sho".toByteArray()
        assertFailsWith<IOException> {
            installBytes(short, expectedBytes = "longer than short".toByteArray())
        }
        assertFalse(part.exists())
        assertTrue(target.readBytes().contentEquals(good))
    }

    @Test
    fun rollbackRestoresThePreviousFile() {
        setUp()
        val v1 = "version one".toByteArray()
        val v2 = "version two!".toByteArray()
        installBytes(v1)
        installBytes(v2)

        assertTrue(InstallSwap.rollback(target, previous, sidecar, previousSidecar))
        assertTrue(target.readBytes().contentEquals(v1))
        assertEquals(sha(v1), sidecar.readText())
        assertTrue(previous.readBytes().contentEquals(v2), "rolled-back file stays available")
        assertEquals(sha(v2), previousSidecar.readText())
    }

    @Test
    fun rollbackWithoutPreviousChangesNothing() {
        setUp()
        val v1 = "only version".toByteArray()
        installBytes(v1)
        assertFalse(InstallSwap.rollback(target, previous, sidecar, previousSidecar))
        assertTrue(target.readBytes().contentEquals(v1))
    }
}
