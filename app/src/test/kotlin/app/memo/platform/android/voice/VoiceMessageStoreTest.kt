package app.memo.platform.android.voice

import app.memo.core.chat.VoiceClipRefs
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * JVM test for [VoiceMessageStore]: WAVs must land in the encrypted vault (never on disk)
 * under the timestamp name the chat stores as the clip's reference, and the old plaintext
 * clips must be imported once and destroyed.
 */
class VoiceMessageStoreTest {

    /** In-memory fake validating every name exactly like the real vault contract does. */
    private class FakeVault : Vault {
        val entries = HashMap<String, ByteArray>()

        override fun read(name: String): ByteArray? {
            VaultName.validate(name)
            return entries[name]?.copyOf()
        }

        override fun write(name: String, bytes: ByteArray) {
            VaultName.validate(name)
            entries[name] = bytes.copyOf()
        }

        override fun delete(name: String) {
            VaultName.validate(name)
            entries.remove(name)
        }

        override fun list(prefix: String): List<String> =
            entries.keys.filter { it.startsWith(prefix) }.sorted()
    }

    private val now = 1_798_723_300_123L
    private val expectedName =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(Date(now))
    private val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x7F, 0x10, 0x5A)

    private fun newDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "voice-store-test/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun saveStoresExactBytesInVaultUnderTimestampNameAndNothingOnDisk() {
        val vault = FakeVault()
        val diskSpot = newDir("save-clear")
        val store = VoiceMessageStore(vault, clock = { now })

        val ref = store.save(wav)

        assertEquals(expectedName, ref)
        assertContentEquals(wav, vault.read(ref))
        assertTrue(diskSpot.isDirectory, "disk spot must stay untouched")
        assertTrue(diskSpot.listFiles().isNullOrEmpty(), "nothing must be written to disk")
        diskSpot.deleteRecursively()
    }

    @Test
    fun secondSaveInSameMillisecondGetsSuffixedName() {
        val vault = FakeVault()
        val store = VoiceMessageStore(vault, clock = { now })

        val first = store.save(wav)
        val second = store.save(byteArrayOf(9, 8, 7))

        assertEquals(expectedName, first)
        assertEquals("$expectedName-1", second)
        assertContentEquals(wav, vault.read(first))
        assertContentEquals(byteArrayOf(9, 8, 7), vault.read(second))
    }

    @Test
    fun importLegacyImportsTwoValidFilesAndDeletesDir() {
        val vault = FakeVault()
        val dir = newDir("import-legacy")
        val a = File(dir, "$expectedName-f1")
        val b = File(dir, "$expectedName-f2")
        a.writeBytes(wav)
        b.writeBytes(byteArrayOf(1, 2, 3, 4))

        val imported = VoiceMessageStore(vault, clock = { now }).importLegacy(dir)

        assertEquals(2, imported)
        assertContentEquals(wav, vault.read(a.name))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), vault.read(b.name))
        assertFalse(a.exists(), "plaintext file must be gone")
        assertFalse(b.exists(), "plaintext file must be gone")
        assertFalse(dir.exists(), "imported the last clip: the legacy dir must be deleted")
    }

    @Test
    fun importLegacyLeavesInvalidNameInPlaceAndReturnsZero() {
        val vault = FakeVault()
        val dir = newDir("import-invalid")
        val bad = File(dir, "Bad Name.WAV")
        bad.writeBytes(wav)

        val imported = VoiceMessageStore(vault, clock = { now }).importLegacy(dir)

        assertEquals(0, imported)
        assertTrue(bad.exists(), "a file with an invalid name must be left in place")
        assertContentEquals(wav, bad.readBytes())
        assertTrue(dir.exists())
    }

    @Test
    fun importLegacyExistingVaultEntryKeepsBytesAndRemovesPlaintext() {
        val vault = FakeVault()
        vault.write(expectedName, byteArrayOf(0, 1, 2))
        val dir = newDir("import-existing")
        val plain = File(dir, expectedName)
        plain.writeBytes(wav)

        val imported = VoiceMessageStore(vault, clock = { now }).importLegacy(dir)

        assertEquals(0, imported)
        assertContentEquals(byteArrayOf(0, 1, 2), vault.read(expectedName), "existing entry not overwritten")
        assertFalse(plain.exists(), "the plaintext copy must still be removed")
    }
}
