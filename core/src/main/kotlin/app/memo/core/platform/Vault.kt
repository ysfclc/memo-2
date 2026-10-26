package app.memo.core.platform

/**
 * Raised when stored data cannot be read or written safely: tampering, a
 * corrupt file with no valid backup, a missing key, or an I/O failure. It is
 * never swallowed into a null result.
 */
class VaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Validation for vault entry names. */
object VaultName {
    /** Allowed name shape: `^[a-z0-9_.-]{1,64}$`. */
    val PATTERN = Regex("^[a-z0-9_.-]{1,64}$")

    /**
     * Returns [name] unchanged when it is valid.
     *
     * @throws IllegalArgumentException when [name] does not match [PATTERN].
     */
    fun validate(name: String): String {
        require(PATTERN.matches(name)) { "invalid vault name: must match ^[a-z0-9_.-]{1,64}$" }
        return name
    }
}

/**
 * Encrypted, named byte storage for workspace, run, audit and settings data.
 *
 * - **Names** are validated against `^[a-z0-9_.-]{1,64}$` by every method
 *   (see [VaultName]). An invalid name throws [IllegalArgumentException].
 * - **Atomic write.** [write] either fully replaces the entry or leaves the
 *   previous contents intact. A reader never sees a partial write.
 * - **Corruption is loud.** [read] returns null only when the entry does not
 *   exist. Tampering, unreadable data or a missing key throws [VaultException].
 *   An implementation may fall back to a valid backup copy, but it must report
 *   the fallback and must not return null for a corrupt entry.
 * - **Delete** of a missing entry is a no-op.
 * - **List** returns the names that start with [prefix], sorted ascending.
 *   An empty prefix lists every entry.
 *
 * Implementations are expected to be thread-safe for distinct names. Callers
 * must serialise writes to the same name.
 */
interface Vault {
    /**
     * Returns the bytes stored under [name], or null if no such entry exists.
     *
     * @throws VaultException if the entry exists but cannot be read safely.
     */
    fun read(name: String): ByteArray?

    /**
     * Stores [bytes] under [name], replacing any previous value atomically.
     *
     * @throws VaultException on I/O or encryption failure. The previous value is kept.
     */
    fun write(name: String, bytes: ByteArray)

    /** Removes the entry under [name]. Missing entries are ignored. */
    fun delete(name: String)

    /** Lists stored names that start with [prefix], sorted ascending. */
    fun list(prefix: String): List<String>
}
