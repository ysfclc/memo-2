package app.memo.platform.android.voice

import app.memo.hub.InstallSwap
import app.memo.hub.RangeResume
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies and unpacks pinned voice models into `root/<id>/`.
 *
 * - Each download resumes from its `.part` file with a Range request ([RangeResume]), and the
 *   finished file is installed only if its size and SHA-256 match the pin ([InstallSwap]).
 * - Downloads are HTTPS only and may redirect only to the hosts in [VoiceDownloadPolicy].
 * - A `.tar.bz2` archive is unpacked into a staging folder with [TarBz2Extractor], which refuses
 *   unsafe paths. Only after the expected folder appears is it renamed into place.
 * - A model counts as installed only when its marker file holds the pinned SHA-256.
 *
 * Blocking I/O. Call from a background thread, never the main thread.
 *
 * @param root the app-private `filesDir/voice` directory.
 */
class VoiceModelInstaller(private val root: File) {

    /** The result of [install]. */
    sealed interface Outcome {
        data class Installed(val dir: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Where [model] lives once installed. The folder may not exist yet. */
    fun dirFor(model: VoiceModel): File = File(root, model.id)

    /** True when [model] was installed with exactly the pinned digest. */
    fun isInstalled(model: VoiceModel): Boolean =
        runCatching { markerFile(model).readText().trim() == model.sha256 }.getOrDefault(false)

    /**
     * Installs [model] unless it is already installed. Calls [onProgress] with bytes downloaded
     * and the total. Never throws: every failure is reported as [Outcome.Failed].
     */
    fun install(model: VoiceModel, onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        if (isInstalled(model)) return Outcome.Installed(dirFor(model))
        return try {
            root.mkdirs()
            when (model.packaging) {
                VoicePackaging.TAR_BZ2 -> installArchive(model, onProgress)
                VoicePackaging.SINGLE_FILE -> installSingleFile(model, onProgress)
            }
            markerFile(model).writeText(model.sha256)
            Outcome.Installed(dirFor(model))
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "download failed")
        } catch (e: SecurityException) {
            Outcome.Failed("storage refused the model files")
        }
    }

    private fun installArchive(model: VoiceModel, onProgress: (Long, Long) -> Unit) {
        val archive = File(root, "${model.id}.archive")
        val previous = File(root, "${model.id}.archive.previous")
        val sidecar = File(root, "${model.id}.archive.sha256")
        val previousSidecar = File(root, "${model.id}.archive.previous.sha256")
        val part = File(root, "${model.id}.archive.part")

        transfer(model, part, onProgress)
        verifyAndInstall(model, part, archive, previous, sidecar, previousSidecar)

        val staging = File(root, "${model.id}.staging")
        staging.deleteRecursively()
        try {
            TarBz2Extractor.extract(archive, staging)
            val source = File(staging, model.archiveRoot)
            if (!source.isDirectory) throw IOException("archive does not contain ${model.archiveRoot}")
            val dir = dirFor(model)
            dir.deleteRecursively()
            if (!source.renameTo(dir)) throw IOException("could not move ${model.id} into place")
            for (name in model.requiredFiles) {
                if (!File(dir, name).isFile) throw IOException("${model.id} is missing $name after unpacking")
            }
        } finally {
            staging.deleteRecursively()
            archive.delete()
            sidecar.delete()
            previous.delete()
            previousSidecar.delete()
        }
    }

    private fun installSingleFile(model: VoiceModel, onProgress: (Long, Long) -> Unit) {
        val dir = dirFor(model)
        dir.mkdirs()
        val target = File(dir, model.requiredFiles.single())
        val previous = File(dir, target.name + ".previous")
        val sidecar = File(dir, target.name + ".sha256")
        val previousSidecar = File(dir, target.name + ".previous.sha256")
        val part = File(dir, target.name + ".part")
        transfer(model, part, onProgress)
        verifyAndInstall(model, part, target, previous, sidecar, previousSidecar)
        previous.delete()
        previousSidecar.delete()
    }

    /** Hands the completed download to [InstallSwap], which checks size and digest before moving it. */
    private fun verifyAndInstall(
        model: VoiceModel,
        part: File,
        target: File,
        previous: File,
        sidecar: File,
        previousSidecar: File,
    ) {
        val size = part.length()
        val actualSha = if (size == model.sizeBytes) sha256Of(part) else ""
        InstallSwap.install(
            part = part,
            target = target,
            previous = previous,
            targetSidecar = sidecar,
            previousSidecar = previousSidecar,
            expectedSize = model.sizeBytes,
            expectedSha256 = model.sha256,
            actualSize = size,
            actualSha256 = actualSha,
        )
    }

    /** Brings [part] to the pinned size by resuming from its current length. */
    private fun transfer(model: VoiceModel, part: File, onProgress: (Long, Long) -> Unit) {
        var restarts = 0
        while (true) {
            val have = if (part.isFile) part.length() else 0L
            if (!RangeResume.needsRequest(have, model.sizeBytes)) return
            val response = openDownload(model.url, have)
            try {
                when (val plan = RangeResume.plan(have, model.sizeBytes, response.status, response.contentRange)) {
                    RangeResume.Plan.Complete -> return
                    RangeResume.Plan.RestartRequest -> {
                        if (++restarts > MAX_RESTARTS) throw IOException("server would not resume the download")
                        part.delete()
                        continue
                    }
                    is RangeResume.Plan.Write -> {
                        val appendFrom = plan.offset
                        FileOutputStream(part, appendFrom > 0L).use { out ->
                            copyWithProgress(response.body, out, appendFrom, model.sizeBytes, onProgress)
                        }
                    }
                }
            } finally {
                response.close()
            }
            if (part.length() < model.sizeBytes) throw IOException("download interrupted; it will resume")
            return
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        out: FileOutputStream,
        startAt: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var done = startAt
        var nextReport = startAt
        onProgress(done, total)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            if (done - nextReport >= PROGRESS_STEP_BYTES || done == total) {
                onProgress(done, total)
                nextReport = done
            }
        }
    }

    private class Response(
        val status: Int,
        val contentRange: String?,
        val body: java.io.InputStream,
        private val connection: HttpURLConnection,
    ) : AutoCloseable {
        override fun close() {
            runCatching { body.close() }
            connection.disconnect()
        }
    }

    /** Opens a download, following redirects by hand so each hop passes [VoiceDownloadPolicy]. */
    private fun openDownload(url: String, rangeFrom: Long): Response {
        var current = URL(url)
        VoiceDownloadPolicy.refusal(current)?.let { throw IOException(it) }
        var hops = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without a location")
                if (++hops > VoiceDownloadPolicy.MAX_REDIRECTS) throw IOException("too many redirects")
                current = URL(current, location)
                VoiceDownloadPolicy.refusal(current)?.let { throw IOException(it) }
                continue
            }
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL || code == HTTP_RANGE_NOT_SATISFIABLE) {
                val body = if (code == HTTP_RANGE_NOT_SATISFIABLE) java.io.ByteArrayInputStream(ByteArray(0)) else connection.inputStream
                return Response(code, connection.getHeaderField("Content-Range"), body, connection)
            }
            connection.disconnect()
            throw IOException("download answered HTTP $code")
        }
    }

    private fun markerFile(model: VoiceModel): File = File(dirFor(model), MARKER_NAME)

    private companion object {
        const val MARKER_NAME = ".memo-installed"
        const val MAX_RESTARTS = 1
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_STEP_BYTES = 1L shl 20
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
