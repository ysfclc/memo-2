package app.memo.hub

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.memo.core.catalog.ModelEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a download needs, passed as WorkManager input. Built from a pinned [ModelEntry] and
 * validated again on the worker side, so a malformed input can never reach the network.
 */
internal data class DownloadSpec(
    val id: String,
    val url: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    companion object {
        private const val KEY_ID = "id"
        private const val KEY_URL = "url"
        private const val KEY_FILE = "file"
        private const val KEY_SHA = "sha"
        private const val KEY_SIZE = "size"

        private val ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val PINNED_URL = Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^?#]+$")
        private val FILE = Regex("^[A-Za-z0-9._-]{1,128}$")

        fun toData(entry: ModelEntry): Data = workDataOf(
            KEY_ID to entry.id,
            KEY_URL to entry.url,
            KEY_FILE to entry.file.substringAfterLast('/'),
            KEY_SHA to entry.sha256,
            KEY_SIZE to entry.sizeBytes,
        )

        /** The spec from WorkManager input, or null when any field is missing or malformed. */
        fun fromData(data: Data): DownloadSpec? {
            val spec = DownloadSpec(
                id = data.getString(KEY_ID) ?: return null,
                url = data.getString(KEY_URL) ?: return null,
                fileName = data.getString(KEY_FILE) ?: return null,
                sha256 = data.getString(KEY_SHA) ?: return null,
                sizeBytes = data.getLong(KEY_SIZE, 0L),
            )
            return spec.takeIf { ID.matches(it.id) && PINNED_URL.matches(it.url) && SHA.matches(it.sha256) &&
                FILE.matches(it.fileName) && it.sizeBytes > 0L }
        }
    }
}

/**
 * Runs one model download in the foreground (dataSync). Pause and cancel arrive as
 * WorkManager cancellation; [ModelHub.runDownload] decides what to keep (see its docs).
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val spec = DownloadSpec.fromData(inputData) ?: return Result.failure()
        val hub = ModelHub.get(applicationContext)
        setForeground(hub.foregroundInfo(spec.id, "Downloading ${spec.fileName}"))
        return try {
            withContext(Dispatchers.IO) { hub.runDownload(spec) }
            Result.success()
        } catch (stop: CancellationException) {
            throw stop
        } catch (error: Exception) {
            // The hub has already published the failure; the .part file stays for a later resume.
            Result.failure()
        }
    }
}
