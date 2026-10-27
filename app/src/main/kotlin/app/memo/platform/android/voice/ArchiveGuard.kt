package app.memo.platform.android.voice

import java.io.File
import java.io.IOException

/**
 * Path rules for unpacking voice archives. Pure apart from the file-system resolution.
 *
 * An archive entry is refused when it is absolute, names a drive, contains `..`, contains a NUL,
 * or resolves outside the destination. Links are never created. Refusing is safer than repairing.
 */
object ArchiveGuard {

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * The safe relative path for archive entry [name] (forward slashes, no `.` segments), or null
     * when the entry must be rejected.
     */
    fun safeEntryPath(name: String): String? {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0) return null
        val normalised = name.replace('\\', '/')
        if (normalised.startsWith("/") || DRIVE.containsMatchIn(normalised)) return null
        val parts = normalised.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * The file that [entry] would create under [root], or throws [IOException] when the entry is
     * unsafe or would land outside [root].
     */
    fun resolveInside(root: File, entry: String): File {
        val rel = safeEntryPath(entry) ?: throw IOException("refused unsafe archive entry")
        val rootPath = root.canonicalFile
        val target = File(rootPath, rel).canonicalFile
        if (target != rootPath && !target.path.startsWith(rootPath.path + File.separator)) {
            throw IOException("refused archive entry outside the destination")
        }
        return target
    }
}
