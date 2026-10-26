package app.memo.platform.android.vault

import app.memo.core.platform.VaultException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultFormatTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val plain = "workspace: the quick brown fox".toByteArray(Charsets.UTF_8)

    @Test
    fun sealOpenRoundTrip() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "workspace", plain)
        assertContentEquals(plain, VaultFormat.open(key, "workspace", sealed))
    }

    @Test
    fun sealedFramingHasMagicIvLengthAndTag() {
        val sealed = VaultFormat.seal(newKey(), "runs", plain)
        assertEquals("MV1", String(sealed, 0, 3, Charsets.US_ASCII))
        assertEquals(12, sealed[3].toInt())
        assertEquals(4 + 12 + plain.size + 16, sealed.size)
    }

    @Test
    fun differentIvOnEverySeal() {
        val key = newKey()
        val first = VaultFormat.seal(key, "runs", plain)
        val second = VaultFormat.seal(key, "runs", plain)
        assertNotEquals(first.copyOfRange(4, 16).toList(), second.copyOfRange(4, 16).toList())
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "audit", plain)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed) }
    }

    @Test
    fun wrongNameFailsAuthentication() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "audit", plain)
        assertFailsWith<VaultException> { VaultFormat.open(key, "settings", sealed) }
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val sealed = VaultFormat.seal(newKey(), "audit", plain)
        assertFailsWith<VaultException> { VaultFormat.open(newKey(), "audit", sealed) }
    }

    @Test
    fun badMagicThrows() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "audit", plain)
        sealed[0] = 'X'.code.toByte()
        val error = assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed) }
        assertTrue(error.message!!.contains("bad magic"))
    }

    @Test
    fun badIvLengthThrows() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "audit", plain)
        sealed[3] = 16
        assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed) }
    }

    @Test
    fun truncatedEntryThrows() {
        val key = newKey()
        val sealed = VaultFormat.seal(key, "audit", plain)
        assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed.copyOf(2)) }
        assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed.copyOf(10)) }
        assertFailsWith<VaultException> { VaultFormat.open(key, "audit", sealed.copyOf(4 + 12 + 15)) }
    }

    @Test
    fun backupNamesAreOrderedBakOneToThree() {
        assertEquals(listOf("workspace.bak1", "workspace.bak2", "workspace.bak3"), VaultFormat.backupNames("workspace"))
    }

    @Test
    fun selectReadablePrefersPrimary() {
        val key = newKey()
        val good = VaultFormat.seal(key, "runs", plain)
        val older = VaultFormat.seal(key, "runs", "older".toByteArray())
        val result = VaultFormat.selectReadable(
            listOf("runs" to good, "runs.bak1" to older, "runs.bak2" to null, "runs.bak3" to null),
        ) { VaultFormat.open(key, "runs", it) }
        assertEquals("runs", result!!.first)
        assertContentEquals(plain, result.second)
    }

    @Test
    fun selectReadableFallsBackToBak1WhenPrimaryCorrupt() {
        val key = newKey()
        val corrupt = VaultFormat.seal(key, "runs", plain).also { it[it.size - 1] = 0 }
        val older = VaultFormat.seal(key, "runs", "older".toByteArray())
        val result = VaultFormat.selectReadable(
            listOf("runs" to corrupt, "runs.bak1" to older, "runs.bak2" to null, "runs.bak3" to null),
        ) { VaultFormat.open(key, "runs", it) }
        assertEquals("runs.bak1", result!!.first)
        assertContentEquals("older".toByteArray(), result.second)
    }

    @Test
    fun selectReadableReturnsNullWhenAllCorrupt() {
        val key = newKey()
        val corrupt = VaultFormat.seal(key, "runs", plain).also { it[it.size - 1] = 0 }
        val result = VaultFormat.selectReadable(
            listOf("runs" to corrupt, "runs.bak1" to corrupt.copyOf(), "runs.bak2" to null, "runs.bak3" to null),
        ) { VaultFormat.open(key, "runs", it) }
        assertNull(result)
    }

    @Test
    fun selectReadableUsesBak2WhenPrimaryMissing() {
        val key = newKey()
        val oldest = VaultFormat.seal(key, "runs", "oldest".toByteArray())
        val result = VaultFormat.selectReadable(
            listOf("runs" to null, "runs.bak1" to null, "runs.bak2" to oldest, "runs.bak3" to null),
        ) { VaultFormat.open(key, "runs", it) }
        assertEquals("runs.bak2", result!!.first)
        assertContentEquals("oldest".toByteArray(), result.second)
    }
}
