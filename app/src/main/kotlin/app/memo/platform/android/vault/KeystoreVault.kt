package app.memo.platform.android.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultException
import app.memo.core.platform.VaultName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Decides when [KeystoreVault] rotates backups. Pure, so it can be tested on the JVM.
 */
internal object BackupRotationPolicy {
    /** Minimum time between two backup rotations of the same entry. */
    const val INTERVAL_MS: Long = 10 * 60 * 1000L

    /**
     * True when a rotation is due: at least [INTERVAL_MS] since [lastRotationMs], or the
     * clock went backwards (then rotating is the safe choice).
     */
    fun isDue(now: Long, lastRotationMs: Long): Boolean =
        now < lastRotationMs || now - lastRotationMs >= INTERVAL_MS
}

/**
 * [Vault] backed by files in `noBackupFilesDir/vault/`, sealed with AES-256-GCM
 * under an Android Keystore key (see [VaultFormat] for the file format).
 *
 * - **Key.** Keystore alias `memo_vault_v1`, AES-256, GCM, no padding, randomized
 *   encryption required. It is created only when no vault data exists. If data
 *   exists but the key is missing, every operation throws [VaultException]:
 *   a new key is never generated over existing data (fail closed).
 * - **Write.** Seal, write `name.tmp` and `fd.sync()`, then atomically move `.tmp`
 *   over the primary. A failure leaves the previous value intact. Before the move,
 *   backups are rotated (`.bak2`→`.bak3`, `.bak1`→`.bak2`, current copied to `.bak1`)
 *   but **at most once per 10 minutes per name** ([BackupRotationPolicy]). The last
 *   rotation time is kept in memory and starts from the `.bak1` modification time, so a
 *   restart does not rotate again straight away. Writes in between replace only the
 *   primary. Consequence: `.bak1` may be up to 10 minutes older than the primary, and
 *   a fallback read can roll back to that point.
 * - **Read.** Tries the primary, then `.bak1..3`. A fallback is recorded in
 *   [lastRecovery] and logged. When nothing opens, throws [VaultException];
 *   a corrupt entry never reads as null.
 * - **Reserved names.** Names ending in `.tmp` or `.bak<digits>` are rejected by
 *   [write], because [list] hides them as sidecar files.
 *
 * **Threading.** Every method performs blocking file and Keystore I/O. Call them
 * off the main thread. Operations on the same name are serialised by a per-name
 * lock. Key creation is serialised by a global lock.
 */
class KeystoreVault(context: Context) : Vault {
    private val dir = File(context.noBackupFilesDir, "vault")
    private val locks = ConcurrentHashMap<String, Any>()

    /** Last backup rotation per entry name, in epoch millis. Guarded by the per-name lock. */
    private val lastRotation = ConcurrentHashMap<String, Long>()

    /**
     * Describes the most recent read that had to use a backup copy, or null if
     * no fallback has happened since this instance was created. The app shows it to the user,
     * e.g. "Recovered workspace.syn from backup .bak1; review your data".
     */
    @Volatile
    var lastRecovery: String? = null
        private set

    override fun read(name: String): ByteArray? {
        VaultName.validate(name)
        synchronized(lockFor(name)) {
            val candidates = listOf(name) + VaultFormat.backupNames(name)
            if (candidates.none { File(dir, it).exists() }) return null

            val key = loadKey()
            val failures = mutableListOf<String>()
            val readable = candidates.map { candidate -> candidate to readOrNull(candidate) }
            val found = VaultFormat.selectReadable(readable) { bytes ->
                try {
                    VaultFormat.open(key, name, bytes)
                } catch (e: VaultException) {
                    failures += e.message ?: "unreadable copy"
                    throw e
                }
            } ?: throw VaultException(
                "no readable copy of vault entry '$name' (${failures.joinToString("; ").ifEmpty { "all copies missing" }})"
            )

            val (source, plain) = found
            if (source != name) {
                val backup = source.removePrefix(name)
                lastRecovery = "Recovered $name from backup $backup; review your data"
                Log.w(
                    TAG,
                    "vault entry '$name' was unreadable and was restored from backup '$source'" +
                        if (failures.isNotEmpty()) " (${failures.joinToString("; ")})" else "",
                )
            }
            return plain
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        VaultName.validate(name)
        require(!isSidecar(name)) { "vault name '$name' is reserved for sidecar files" }
        synchronized(lockFor(name)) {
            val key = synchronized(KEY_LOCK) { keyForWrite() }
            val sealed = VaultFormat.seal(key, name, bytes)

            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(sealed)
                    out.flush()
                    out.fd.sync()
                }
                val now = System.currentTimeMillis()
                if (BackupRotationPolicy.isDue(now, lastRotationOf(name))) {
                    rotateBackups(name)
                    lastRotation[name] = now
                }
                replaceFile(tmp, File(dir, name))
            } catch (e: IOException) {
                throw VaultException("write failed for vault entry '$name'; previous value kept", e)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    override fun delete(name: String) {
        VaultName.validate(name)
        synchronized(lockFor(name)) {
            (listOf(name, "$name.tmp") + VaultFormat.backupNames(name)).forEach { candidate ->
                val file = File(dir, candidate)
                if (file.exists() && !file.delete()) {
                    throw VaultException("could not delete vault file '$candidate'")
                }
            }
        }
    }

    override fun list(prefix: String): List<String> {
        val names = dir.list() ?: return emptyList()
        return names
            .filter { it.startsWith(prefix) && !isSidecar(it) && VaultName.PATTERN.matches(it) }
            .sorted()
    }

    private fun lockFor(name: String): Any = locks.getOrPut(name) { Any() }

    /**
     * Last rotation for [name]. Not seen yet in this process: the `.bak1` modification
     * time, which is when the last rotation copied the primary (0 when none exists).
     */
    private fun lastRotationOf(name: String): Long =
        lastRotation[name] ?: File(dir, "$name.bak1").takeIf { it.exists() }?.lastModified()?.also {
            lastRotation[name] = it
        } ?: 0L

    private fun readOrNull(candidate: String): ByteArray? {
        val file = File(dir, candidate)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "could not read vault file '$candidate'", e)
            null
        }
    }

    private fun rotateBackups(name: String) {
        val backups = VaultFormat.backupNames(name).map { File(dir, it) }
        val (bak1, bak2, bak3) = backups
        if (bak2.exists()) replaceFile(bak2, bak3)
        if (bak1.exists()) replaceFile(bak1, bak2)
        val primary = File(dir, name)
        // Copy rather than move, so the primary stays present through the whole write.
        if (primary.exists()) Files.copy(primary.toPath(), bak1.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun replaceFile(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(),
                dst.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            // Atomic move unsupported on this filesystem. POSIX rename(2) still replaces atomically.
            if (!src.renameTo(dst)) {
                throw VaultException("could not move '${src.name}' to '${dst.name}'", e)
            }
        }
    }

    private fun hasVaultData(): Boolean = dir.list()?.isNotEmpty() == true

    /** Returns the existing key, or creates one only when there is no vault data. */
    private fun keyForWrite(): SecretKey {
        existingKey()?.let { return it }
        if (hasVaultData()) throw VaultException(MISSING_KEY)
        return createKey()
    }

    private fun loadKey(): SecretKey = existingKey() ?: throw VaultException(MISSING_KEY)

    private fun existingKey(): SecretKey? {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            throw VaultException("Android Keystore unavailable", e)
        } catch (e: IOException) {
            throw VaultException("Android Keystore unavailable", e)
        }
    }

    private fun createKey(): SecretKey {
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (e: GeneralSecurityException) {
            throw VaultException("could not create vault key", e)
        } catch (e: ProviderException) {
            throw VaultException("could not create vault key", e)
        }
    }

    /** Temp and backup files: never listed, and reserved as entry names. */
    private fun isSidecar(name: String): Boolean = name.endsWith(".tmp") || BACKUP_SUFFIX.containsMatchIn(name)

    private companion object {
        const val TAG = "KeystoreVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "memo_vault_v1"
        const val MISSING_KEY = "vault key missing; data cannot be decrypted"
        val BACKUP_SUFFIX = Regex("\\.bak\\d+$")
        val KEY_LOCK = Any()
    }
}
