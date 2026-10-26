package app.memo.core.util

import java.security.MessageDigest

/**
 * SHA-256 hex digests (UTF-8). Used by the audit hash chain, registry
 * content signatures, gate content hashes and natural-key ids. One helper
 * so the digest format is identical everywhere.
 */
object Sha256Hex {

    fun hex(input: String): String = hex(input.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
