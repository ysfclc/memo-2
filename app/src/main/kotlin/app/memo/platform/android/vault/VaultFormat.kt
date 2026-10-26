package app.memo.platform.android.vault

import app.memo.core.platform.VaultException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure (no Android imports) framing, sealing and backup-selection logic for
 * vault entries. Kept JVM-only so it can be unit-tested without a device.
 *
 * Wire format of one sealed entry:
 * ```
 * MAGIC "MV1" (3 bytes) | ivLength (1 byte, always 12) | iv (12 bytes) | ciphertext || GCM tag (16 bytes)
 * ```
 * The AAD is `memo:<name>:v1`, so an entry cannot be moved or renamed onto
 * another name without failing authentication.
 */
object VaultFormat {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val IV_LENGTH = 12
    const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val HEADER_BYTES = 3 + 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Encrypts [plain] under [key] for entry [name]. The provider generates a
     * fresh random 12-byte IV on every call; it is read back from the cipher.
     *
     * @throws VaultException if the cipher cannot be initialised or fails.
     */
    fun seal(key: SecretKey, name: String, plain: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(name))
            val iv = cipher.iv
            if (iv.size != IV_LENGTH) {
                throw VaultException("unexpected iv length ${iv.size} while sealing '$name'")
            }
            val ciphertext = cipher.doFinal(plain)
            val out = ByteArray(HEADER_BYTES + iv.size + ciphertext.size)
            MAGIC.copyInto(out, 0)
            out[MAGIC.size] = iv.size.toByte()
            iv.copyInto(out, HEADER_BYTES)
            ciphertext.copyInto(out, HEADER_BYTES + iv.size)
            return out
        } catch (e: GeneralSecurityException) {
            throw VaultException("could not seal vault entry '$name'", e)
        }
    }

    /**
     * Validates the framing of [sealed] and decrypts it under [key] for entry [name].
     *
     * @throws VaultException for a bad magic, an unsupported iv length, a
     *   truncated entry, or an authentication failure (tampering, wrong name or wrong key).
     */
    fun open(key: SecretKey, name: String, sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER_BYTES) {
            throw VaultException("vault entry '$name' is truncated (${sealed.size} bytes)")
        }
        if (!sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw VaultException("vault entry '$name' has bad magic; not a Memo vault file")
        }
        val ivLength = sealed[MAGIC.size].toInt() and 0xFF
        if (ivLength != IV_LENGTH) {
            throw VaultException("vault entry '$name' has unsupported iv length $ivLength")
        }
        val bodyStart = HEADER_BYTES + ivLength
        if (sealed.size < bodyStart + TAG_BYTES) {
            throw VaultException("vault entry '$name' is truncated (${sealed.size} bytes)")
        }
        val iv = sealed.copyOfRange(HEADER_BYTES, bodyStart)
        val body = sealed.copyOfRange(bodyStart, sealed.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(name))
            return cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw VaultException(
                "vault entry '$name' failed authentication (tampered, wrong name or wrong key)",
                e,
            )
        }
    }

    /** Backup file names for [name], newest first: `name.bak1`, `name.bak2`, `name.bak3`. */
    fun backupNames(name: String): List<String> = (1..BACKUP_COUNT).map { "$name.bak$it" }

    /**
     * Returns the first candidate that opens, tried in list order, together
     * with its candidate name. Candidates whose bytes are null (missing) are
     * skipped. A candidate whose [opener] throws [VaultException] is skipped.
     * Returns null when no candidate opens.
     *
     * The caller lists the primary first, then `.bak1..3`. [opener] receives the
     * candidate bytes and returns the plaintext.
     */
    fun selectReadable(
        candidates: List<Pair<String, ByteArray?>>,
        opener: (ByteArray) -> ByteArray,
    ): Pair<String, ByteArray>? {
        for ((candidateName, bytes) in candidates) {
            if (bytes == null) continue
            try {
                return candidateName to opener(bytes)
            } catch (e: VaultException) {
                continue
            }
        }
        return null
    }

    /** Number of rolling backups kept per entry. */
    const val BACKUP_COUNT = 3

    private fun aad(name: String): ByteArray = "memo:$name:v1".toByteArray(Charsets.UTF_8)
}
