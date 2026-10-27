package app.memo.platform.android.voice

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Unpacks a verified `.tar.bz2` voice archive into [destination]. Only regular files and
 * directories are created. Links and special entries make the whole unpack fail, and so does any
 * entry that [ArchiveGuard] refuses. The destination is created if missing.
 */
object TarBz2Extractor {

    fun extract(archive: File, destination: File) {
        destination.mkdirs()
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val target = ArchiveGuard.resolveInside(destination, entry.name)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink || entry.isLink -> throw IOException("refused link entry in archive")
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { tar.copyTo(it) }
                        }
                        else -> throw IOException("refused special entry in archive")
                    }
                }
            }
        }
    }
}
