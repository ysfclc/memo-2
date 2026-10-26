package app.memo.hub

import java.io.File
import java.io.IOException

/**
 * Verified install and rollback of one model file. Pure file operations, no Android.
 *
 * Layout for a model whose file is `F` (the hub keeps these side by side):
 * - `F`: the active, verified file
 * - `F.part`: the download in progress
 * - `F.previous`: the file that was active before the last install (rollback target)
 * - `F.sha256` and `F.previous.sha256`: sidecars holding the digest of `F` and `F.previous`
 *
 * [install] never leaves an unverified file at `F`: the caller's streaming digest and size
 * must match the expected values, or the part is deleted and `F` is left as it was.
 */
object InstallSwap {

    /**
     * Verifies [part] against the expected size and digest, then makes it the active file.
     *
     * - Mismatch (size or SHA-256): [part] is deleted, [target] and its sidecar are untouched,
     *   and [IOException] is thrown.
     * - Match: the old [target] moves to [previous] (replacing the older previous), [part]
     *   moves to [target], and the sidecars follow the same move. If the second rename fails,
     *   the old target is restored and the exception is rethrown.
     */
    fun install(
        part: File,
        target: File,
        previous: File,
        targetSidecar: File,
        previousSidecar: File,
        expectedSize: Long,
        expectedSha256: String,
        actualSize: Long,
        actualSha256: String,
    ) {
        if (!part.isFile) throw IOException("nothing to install: ${part.name} is missing")
        if (actualSize != expectedSize || part.length() != expectedSize) {
            part.delete()
            throw IOException("size mismatch: expected $expectedSize bytes, got $actualSize")
        }
        if (actualSha256 != expectedSha256) {
            part.delete()
            throw IOException("checksum mismatch, file discarded")
        }

        val hadTarget = target.exists()
        if (hadTarget) {
            previous.delete()
            previousSidecar.delete()
            if (!target.renameTo(previous)) throw IOException("could not keep the previous model")
            if (targetSidecar.exists()) targetSidecar.renameTo(previousSidecar)
        }
        if (!part.renameTo(target)) {
            if (hadTarget) {
                previous.renameTo(target)
                previousSidecar.renameTo(targetSidecar)
            }
            throw IOException("could not move the verified model into place")
        }
        targetSidecar.writeText(expectedSha256)
    }

    /**
     * Makes [previous] the active file again and [target] the new previous, sidecars included.
     * Returns false, changing nothing, when there is no previous file.
     */
    fun rollback(
        target: File,
        previous: File,
        targetSidecar: File,
        previousSidecar: File,
    ): Boolean {
        if (!previous.isFile) return false
        val swap = File(target.parentFile, target.name + ".swap")
        swap.delete()
        if (target.exists() && !target.renameTo(swap)) throw IOException("could not step the active model aside")
        if (!previous.renameTo(target)) {
            if (swap.exists()) swap.renameTo(target)
            throw IOException("could not restore the previous model")
        }
        if (swap.exists() && !swap.renameTo(previous)) throw IOException("could not keep the rolled-back model")

        val activeSidecarTmp = File(targetSidecar.parentFile, targetSidecar.name + ".swap")
        activeSidecarTmp.delete()
        if (targetSidecar.exists()) targetSidecar.renameTo(activeSidecarTmp)
        if (previousSidecar.exists()) previousSidecar.renameTo(targetSidecar)
        if (activeSidecarTmp.exists()) activeSidecarTmp.renameTo(previousSidecar)
        return true
    }
}
