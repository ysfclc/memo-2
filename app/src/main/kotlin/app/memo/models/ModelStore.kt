package app.memo.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.mutableStateMapOf
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Local model lifecycle: one-tap download from the pinned source, resumable
 * transfer, SHA-256 + size verification before a model becomes usable, import
 * of a model file the user picks, and discovery of model files already on
 * the device. Nothing is trusted until its hash matches the catalog pin.
 */
class ModelStore(private val context: Context) {

    enum class Status { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, FAILED }

    data class ModelState(
        val status: Status = Status.NOT_INSTALLED,
        val progress: Float = 0f,
        val message: String = "",
    )

    /** A model file found on the device that the app did not download itself. */
    data class Discovered(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val matchesCatalogId: String?,
    )

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val importsDir = File(modelsDir, "imported").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("memo_models", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "memo-models") }
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    /** Observable per-model state for Compose. Written on the main thread only. */
    val states = mutableStateMapOf<String, ModelState>()

    init {
        ModelCatalog.entries.forEach { entry ->
            states[entry.id] = if (isInstalled(entry)) {
                ModelState(Status.INSTALLED, 1f, "Installed and verified")
            } else if (partFile(entry).exists()) {
                ModelState(Status.NOT_INSTALLED, 0f, "Partial download, one tap resumes it")
            } else {
                ModelState(Status.NOT_INSTALLED, 0f, "Not installed")
            }
        }
    }

    fun state(entry: ModelEntry): ModelState = states[entry.id] ?: ModelState()

    /** The model the orchestrator is allowed to use. Only verified files qualify. */
    var activeId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> ModelCatalog.byId(id)?.let(::isInstalled) == true }
        set(value) {
            runCatching { prefs.edit().putString("active", value).apply() }
        }

    fun isInstalled(entry: ModelEntry): Boolean {
        val file = fileFor(entry)
        val sidecar = sidecarFor(entry)
        return file.exists() &&
            file.length() == entry.sizeBytes &&
            runCatching { sidecar.readText().trim() == entry.sha256 }.getOrDefault(false)
    }

    /** One tap: start (or resume) the pinned download, verify, then install. */
    fun download(entry: ModelEntry) {
        if (state(entry).status == Status.DOWNLOADING || state(entry).status == Status.VERIFYING) return
        if (isInstalled(entry)) return
        cancelFlags[entry.id] = false
        publish(entry.id, ModelState(Status.DOWNLOADING, 0f, "Connecting to source"))
        worker.execute { runDownload(entry) }
    }

    fun cancel(entry: ModelEntry) {
        cancelFlags[entry.id] = true
    }

    /**
     * Import a model file the user picked (Storage Access Framework). The file is
     * hashed; if it matches a pinned catalog entry it is installed, otherwise it is
     * kept as an unverified import that the orchestrator will never load.
     */
    fun importFrom(open: () -> java.io.InputStream?, displayName: String) {
        worker.execute {
            val temp = File(importsDir, "incoming.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                open()?.use { input ->
                    FileOutputStream(temp).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                } ?: throw IOException("cannot open selected file")
                val hex = digest.digest().toHex()
                val match = ModelCatalog.entries.firstOrNull { it.sha256 == hex }
                if (match != null) {
                    val target = fileFor(match)
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) throw IOException("could not move verified model into place")
                    sidecarFor(match).writeText(match.sha256)
                    main.post { states[match.id] = ModelState(Status.INSTALLED, 1f, "Imported and verified") }
                    activeId = activeId ?: match.id
                } else {
                    val kept = File(importsDir, displayName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    if (kept.exists()) kept.delete()
                    temp.renameTo(kept)
                }
            } catch (error: Exception) {
                temp.delete()
            }
        }
    }

    /** Model-looking files already on this device (app storage and app-scoped external storage). */
    fun discover(onResult: (List<Discovered>) -> Unit) {
        worker.execute {
            val roots = listOfNotNull(
                modelsDir,
                context.getExternalFilesDir(null),
                context.getExternalFilesDir("models"),
            )
            val found = roots.asSequence()
                .flatMap { root -> root.walkTopDown().maxDepth(3).filter { it.isFile } }
                .filter { file ->
                    val n = file.name.lowercase()
                    (n.endsWith(".litertlm") || n.endsWith(".task") || n.endsWith(".bin")) &&
                        !n.endsWith(".part") && file.length() > 1_000_000L
                }
                .distinctBy { it.absolutePath }
                .map { file ->
                    Discovered(
                        path = file.absolutePath,
                        name = file.name,
                        sizeBytes = file.length(),
                        matchesCatalogId = ModelCatalog.entries.firstOrNull { it.sizeBytes == file.length() }?.id,
                    )
                }
                .toList()
            main.post { onResult(found) }
        }
    }

    /** Free bytes on the storage that holds models, for the UI footer. */
    fun freeBytes(): Long = runCatching { StatFs(modelsDir.path).availableBytes }.getOrDefault(0L)

    // ------------------------------------------------------------ internals

    private fun runDownload(entry: ModelEntry) {
        val part = partFile(entry)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            if (part.exists()) {
                // Re-hash what we already have so the final digest covers the whole file.
                part.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                offset = part.length()
            }

            val free = freeBytes()
            val needed = (entry.sizeBytes - offset).coerceAtLeast(0L)
            if (free > 0 && free < needed + STORAGE_HEADROOM) {
                throw IOException("needs ${mb(needed + STORAGE_HEADROOM)} free, have ${mb(free)}")
            }

            if (offset < entry.sizeBytes) {
                val conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                val code = conn.responseCode
                var append = offset > 0
                if (code == HttpURLConnection.HTTP_OK && offset > 0) {
                    // Server ignored the range: start over.
                    part.delete()
                    digest.reset()
                    offset = 0
                    append = false
                } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("source answered HTTP $code")
                }

                var written = offset
                FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            if (cancelFlags[entry.id] == true) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            if (written - lastReport > PROGRESS_STEP || written == entry.sizeBytes) {
                                lastReport = written
                                val fraction = (written.toFloat() / entry.sizeBytes).coerceIn(0f, 1f)
                                publish(entry.id, ModelState(Status.DOWNLOADING, fraction, "${mb(written)} of ${mb(entry.sizeBytes)}"))
                            }
                        }
                    }
                }
                conn.disconnect()
            }

            publish(entry.id, ModelState(Status.VERIFYING, 1f, "Checking SHA-256"))
            if (part.length() != entry.sizeBytes) {
                throw IOException("size mismatch: got ${part.length()} bytes")
            }
            val hex = digest.digest().toHex()
            if (hex != entry.sha256) {
                part.delete()
                throw IOException("checksum mismatch, file discarded")
            }

            val target = fileFor(entry)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("could not finalise model file")
            sidecarFor(entry).writeText(entry.sha256)
            activeId = activeId ?: entry.id
            publish(entry.id, ModelState(Status.INSTALLED, 1f, "Installed and verified"))
        } catch (error: Exception) {
            val cancelled = cancelFlags[entry.id] == true
            val message = if (cancelled) "Paused. One tap resumes." else (error.message ?: "download failed")
            publish(
                entry.id,
                ModelState(
                    status = if (cancelled) Status.NOT_INSTALLED else Status.FAILED,
                    progress = 0f,
                    message = message,
                ),
            )
        }
    }

    private fun publish(id: String, state: ModelState) {
        main.post { states[id] = state }
    }

    private fun fileFor(entry: ModelEntry) = File(modelsDir, entry.file)
    private fun partFile(entry: ModelEntry) = File(modelsDir, entry.file + ".part")
    private fun sidecarFor(entry: ModelEntry) = File(modelsDir, entry.file + ".sha256")

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val STORAGE_HEADROOM = 200_000_000L
        const val PROGRESS_STEP = 4_000_000L
    }
}
