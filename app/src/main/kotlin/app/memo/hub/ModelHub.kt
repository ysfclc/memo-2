package app.memo.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.memo.R
import app.memo.core.catalog.HfParsers
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.core.platform.VaultException
import app.memo.platform.android.vault.KeystoreVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Lifecycle of one model in the hub, as the UI sees it. */
enum class HubStatus { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, FAILED }

/** Observable state of one model. [progress] is 0..1. */
data class HubState(
    val status: HubStatus = HubStatus.NOT_INSTALLED,
    val progress: Float = 0f,
    val message: String = "",
)

/** A model file found on the device. [matchesCatalogId] is set when the size matches a pinned entry. */
data class DeviceModel(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val matchesCatalogId: String?,
)

/** Outcome of a SAF import: a hash match installs, anything else is kept unverified. */
sealed interface ImportResult {
    data class Verified(val entryId: String) : ImportResult
    data class Unverified(val fileName: String) : ImportResult
}

/**
 * Files of one model, all under `filesDir/hub/models/<id>/`:
 * the active file, `.part`, `.previous`, `.cancel` marker, and `.sha256` sidecars.
 */
internal class ModelFiles(dir: File, val name: String) {
    val target = File(dir, name)
    val part = File(dir, "$name.part")
    val previous = File(dir, "$name.previous")
    val sidecar = File(dir, "$name.sha256")
    val previousSidecar = File(dir, "$name.previous.sha256")
    val cancelMarker = File(dir, "$name.cancel")
}

/**
 * The model hub: the pinned catalog, live Hugging Face discovery, background downloads
 * (through [DownloadWorker]), verified installs, SAF import, device scan, and migration of
 * the first-generation `ModelStore` layout (`filesDir/models/`).
 *
 * Nothing becomes installed without a SHA-256 and size match. Installed state is a
 * file that matches its catalog size plus a `.sha256` sidecar equal to the pinned digest.
 *
 * One process-wide instance: [get]. The worker reaches it through the same call.
 */
class ModelHub private constructor(context: Context) {

    private val app = context.applicationContext
    private val root = File(app.filesDir, "hub")
    private val modelsRoot = File(root, "models")
    private val importsDir = File(root, "imports")
    private val legacyDir = File(app.filesDir, "models")
    private val vault by lazy { KeystoreVault(app) }
    private val client = HfClient { readToken() }
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val states = MutableStateFlow<Map<String, HubState>>(emptyMap())

    /** Per-model state for every catalog entry and every entry that has been queued. */
    val statesFlow: StateFlow<Map<String, HubState>> = states.asStateFlow()

    private val loadLock = Mutex()

    @Volatile
    private var loaded = false

    /**
     * One-time load: creates the hub directories and the notification channel, migrates
     * legacy installs, and publishes the state of every catalog entry. Runs on
     * Dispatchers.IO and is idempotent. The constructor does no I/O; callers that need
     * states launch this off the main thread first.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            loadLock.withLock {
                if (loaded) return@withContext
                modelsRoot.mkdirs()
                importsDir.mkdirs()
                ensureChannel()
                migrateLegacy()
                refreshStates()
                loaded = true
            }
        }
    }

    // ------------------------------------------------------------ queries

    fun catalog(): List<ModelEntry> = ModelCatalog.entries

    fun state(id: String): HubState = states.value[id] ?: HubState()

    /** True when the file exists, has the pinned size, and its sidecar holds the pinned digest. */
    fun isInstalled(entry: ModelEntry): Boolean {
        val files = filesFor(entry)
        return files.target.isFile &&
            files.target.length() == entry.sizeBytes &&
            runCatching { files.sidecar.readText().trim() == entry.sha256 }.getOrDefault(false)
    }

    /** The verified file for [entry], or null when it is not installed. */
    fun installedFile(entry: ModelEntry): File? = if (isInstalled(entry)) filesFor(entry).target else null

    fun freeBytes(): Long = freeBytesAt(modelsRoot)

    // ------------------------------------------------------------ downloads

    /**
     * Queues the download of [entry] as unique work. Resumes the `.part` file when one exists.
     * A second call while the work is queued or running changes nothing (KEEP).
     */
    fun enqueue(entry: ModelEntry) {
        if (isInstalled(entry)) {
            publish(entry.id, HubState(HubStatus.INSTALLED, 1f, "Installed and verified"))
            return
        }
        val files = filesFor(entry)
        files.cancelMarker.delete()
        publish(entry.id, HubState(HubStatus.DOWNLOADING, 0f, "Queued"))
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadSpec.toData(entry))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG_DOWNLOAD)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(workName(entry.id), ExistingWorkPolicy.KEEP, request)
    }

    /** Stops the download and keeps the `.part` file, so the next [enqueue] resumes it. */
    fun pause(entry: ModelEntry) {
        WorkManager.getInstance(app).cancelUniqueWork(workName(entry.id))
        if (!running.contains(entry.id)) {
            publish(entry.id, HubState(HubStatus.NOT_INSTALLED, 0f, pausedMessage(entry)))
        }
    }

    /** Stops the download and deletes the `.part` file. The next download starts from byte 0. */
    fun cancel(entry: ModelEntry) {
        val files = filesFor(entry)
        files.cancelMarker.writeText("cancel")
        WorkManager.getInstance(app).cancelUniqueWork(workName(entry.id))
        if (!running.contains(entry.id)) {
            files.part.delete()
            files.cancelMarker.delete()
            publish(entry.id, HubState(HubStatus.NOT_INSTALLED, 0f, "Cancelled. Partial file removed."))
        }
    }

    /**
     * Makes the previous verified file active again. Returns false when there is no
     * `.previous` file. Only the hub's own verified files move.
     */
    fun rollback(entry: ModelEntry): Boolean {
        val files = filesFor(entry)
        val done = InstallSwap.rollback(files.target, files.previous, files.sidecar, files.previousSidecar)
        refreshState(entry)
        return done
    }

    /** Cancels pending work, then removes only this model's verified file and hub sidecars. */
    suspend fun uninstall(entry: ModelEntry): Boolean = withContext(Dispatchers.IO) {
        val files = filesFor(entry)
        val cancellation = WorkManager.getInstance(app).cancelUniqueWork(workName(entry.id))
        val cancelled = runCatching { cancellation.result.get(10L, TimeUnit.SECONDS) }.isSuccess
        if (!cancelled) return@withContext false

        val lock = downloadLocks.computeIfAbsent(entry.id) { Mutex() }
        lock.withLock {
            if (running.contains(entry.id) || !isInstalled(entry)) return@withLock false
            val owned = listOf(files.target, files.sidecar, files.part, files.previous, files.previousSidecar, files.cancelMarker)
            val removed = owned.all { file -> !file.exists() || file.delete() }
            if (removed) {
                publish(entry.id, HubState(HubStatus.NOT_INSTALLED, 0f, "Removed from this device"))
                true
            } else {
                refreshState(entry)
                false
            }
        }
    }

    /**
     * The download body. Called by [DownloadWorker] on an IO dispatcher. Verifies size and
     * SHA-256 before anything becomes installed. Cancellation (pause or cancel) propagates as
     * [CancellationException]; the `.part` file is kept on pause and removed on cancel.
     */
    internal suspend fun runDownload(spec: DownloadSpec) {
        val lock = downloadLocks.computeIfAbsent(spec.id) { Mutex() }
        lock.withLock {
            val files = filesFor(spec.id, spec.fileName)
            running.add(spec.id)
            try {
            files.target.parentFile?.mkdirs()
            publish(spec.id, HubState(HubStatus.DOWNLOADING, 0f, "Checking storage"))

            var partLength = if (files.part.isFile) files.part.length() else 0L
            if (partLength > spec.sizeBytes) {
                files.part.delete()
                partLength = 0L
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (partLength > 0L) hashInto(files.part, digest)

            if (RangeResume.needsRequest(partLength, spec.sizeBytes)) {
                val needed = (spec.sizeBytes - partLength).coerceAtLeast(0L) + STORAGE_HEADROOM
                val free = freeBytesAt(files.target.parentFile ?: modelsRoot)
                if (free in 1 until needed) {
                    throw IOException("needs ${mb(needed)} free, have ${mb(free)}")
                }
                publish(spec.id, HubState(HubStatus.DOWNLOADING, partLength.fraction(spec.sizeBytes), "Connecting"))
                streamBody(spec, files, partLength, digest)
            }

            currentCoroutineContext().ensureActive()
            publish(spec.id, HubState(HubStatus.VERIFYING, 1f, "Checking SHA-256"))
            InstallSwap.install(
                part = files.part,
                target = files.target,
                previous = files.previous,
                targetSidecar = files.sidecar,
                previousSidecar = files.previousSidecar,
                expectedSize = spec.sizeBytes,
                expectedSha256 = spec.sha256,
                actualSize = files.part.length(),
                actualSha256 = digest.digest().toHex(),
            )
            publish(spec.id, HubState(HubStatus.INSTALLED, 1f, "Installed and verified"))
        } catch (stop: CancellationException) {
            if (files.cancelMarker.exists()) {
                files.part.delete()
                files.cancelMarker.delete()
                publish(spec.id, HubState(HubStatus.NOT_INSTALLED, 0f, "Cancelled. Partial file removed."))
            } else {
                publish(spec.id, HubState(HubStatus.NOT_INSTALLED, state(spec.id).progress, "Paused. One tap resumes."))
            }
            throw stop
        } catch (error: Exception) {
            publish(spec.id, HubState(HubStatus.FAILED, 0f, error.message ?: "download failed"))
            } finally {
                running.remove(spec.id)
                notifyDone(spec.id)
            }
        }
    }

    private suspend fun streamBody(spec: DownloadSpec, files: ModelFiles, startAt: Long, digest: MessageDigest) {
        var response = client.open(spec.url, rangeFrom = startAt)
        var plan = RangeResume.plan(startAt, spec.sizeBytes, response.status, response.contentRange)
        if (plan == RangeResume.Plan.RestartRequest) {
            response.close()
            files.part.delete()
            digest.reset()
            response = client.open(spec.url, rangeFrom = 0L)
            plan = RangeResume.plan(0L, spec.sizeBytes, response.status, response.contentRange)
        }
        response.use { body ->
            when (plan) {
                RangeResume.Plan.Complete -> return
                RangeResume.Plan.RestartRequest -> throw IOException("source would not resume the download")
                is RangeResume.Plan.Write -> {
                    val offset = (plan as RangeResume.Plan.Write).offset
                    if (offset == 0L) {
                        files.part.delete()
                        digest.reset()
                    }
                    var written = offset
                    var lastReport = offset
                    val buffer = ByteArray(BUFFER_BYTES)
                    FileOutputStream(files.part, offset > 0L).use { out ->
                        body.body.use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                written += n
                                if (written - lastReport >= PROGRESS_STEP_BYTES || written == spec.sizeBytes) {
                                    lastReport = written
                                    val fraction = written.fraction(spec.sizeBytes)
                                    publish(
                                        spec.id,
                                        HubState(HubStatus.DOWNLOADING, fraction, "${mb(written)} of ${mb(spec.sizeBytes)}"),
                                    )
                                    progressNotification(spec.id, (fraction * 100).toInt())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ discovery

    /**
     * Live discovery: the litert-community list (LiteRT-LM repos) plus a GGUF search for [query].
     * Each repo costs two API calls (model and tree); at most [MAX_REPOS] repos per source.
     * Repos that fail are skipped, so the result is partial rather than empty on a bad day.
     * Returned entries are candidates: each is pinned, but none is installed until downloaded.
     */
    suspend fun discoverOnline(query: String = DEFAULT_QUERY): List<ModelEntry> = withContext(Dispatchers.IO) {
        val found = mutableListOf<ModelEntry>()
        val litertRepos = HfParsers.parseList(client.getText(LITERT_LIST_URL))
            .filter { "litert-lm" in it.tags }
            .take(MAX_REPOS)
        litertRepos.forEach { item ->
            runCatching { describe(item.repo) { info, files -> HfParsers.bestLitertCandidate(info, files) } }
                .onSuccess { entry -> entry?.let(found::add) }
                .onFailure { Log.w(TAG, "discovery skipped ${item.repo}: ${it.javaClass.simpleName}") }
        }
        val search = URLEncoder.encode(query.ifBlank { DEFAULT_QUERY }, "UTF-8")
        val ggufRepos = HfParsers.parseList(client.getText("$API/models?search=$search&filter=gguf&sort=downloads&limit=$MAX_REPOS"))
        ggufRepos.forEach { item ->
            runCatching { describe(item.repo) { info, files -> HfParsers.bestGgufCandidate(info, files) } }
                .onSuccess { entry -> entry?.let(found::add) }
                .onFailure { Log.w(TAG, "discovery skipped ${item.repo}: ${it.javaClass.simpleName}") }
        }
        found.distinctBy { it.sha256 }
    }

    private fun describe(repo: String, pick: (app.memo.core.catalog.HfModelInfo, List<app.memo.core.catalog.HfFile>) -> ModelEntry?): ModelEntry? {
        val info = HfParsers.parseModelInfo(client.getText("$API/models/$repo"))
        val files = HfParsers.parseTree(client.getText("$API/models/$repo/tree/${info.revision}?recursive=true"))
        return pick(info, files)
    }

    // ------------------------------------------------------------ device

    /** Model-looking files already on this device: hub, legacy and app-scoped external storage. */
    fun scanDevice(): List<DeviceModel> {
        val roots = listOfNotNull(modelsRoot, legacyDir, app.getExternalFilesDir(null), app.getExternalFilesDir("models"))
        return roots.asSequence()
            .flatMap { root -> root.walkTopDown().maxDepth(3).filter { it.isFile } }
            .filter { file ->
                val n = file.name.lowercase()
                (n.endsWith(".litertlm") || n.endsWith(".task") || n.endsWith(".bin") || n.endsWith(".gguf")) &&
                    !n.endsWith(".part") && file.length() > 1_000_000L
            }
            .distinctBy { it.absolutePath }
            .map { file ->
                DeviceModel(
                    path = file.absolutePath,
                    name = file.name,
                    sizeBytes = file.length(),
                    matchesCatalogId = ModelCatalog.entries.firstOrNull { it.sizeBytes == file.length() }?.id,
                )
            }
            .toList()
    }

    /**
     * SAF import, blocking: the stream is copied and hashed once. A digest and size match
     * with a catalog entry installs it through [InstallSwap]; anything else is kept under
     * `hub/imports/` as an unverified import, which is never loaded.
     */
    fun importFile(open: () -> InputStream?, displayName: String): ImportResult {
        importsDir.mkdirs()
        val temp = File(importsDir, "incoming.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = open() ?: throw IOException("cannot open selected file")
            var size = 0L
            source.use { input ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        size += n
                    }
                }
            }
            val hex = digest.digest().toHex()
            val match = ModelCatalog.entries.firstOrNull { it.sha256 == hex && it.sizeBytes == size }
            if (match != null) {
                val files = filesFor(match)
                InstallSwap.install(
                    part = temp,
                    target = files.target,
                    previous = files.previous,
                    targetSidecar = files.sidecar,
                    previousSidecar = files.previousSidecar,
                    expectedSize = match.sizeBytes,
                    expectedSha256 = match.sha256,
                    actualSize = size,
                    actualSha256 = hex,
                )
                publish(match.id, HubState(HubStatus.INSTALLED, 1f, "Imported and verified"))
                return ImportResult.Verified(match.id)
            }
            val kept = File(importsDir, displayName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            kept.delete()
            if (!temp.renameTo(kept)) throw IOException("could not keep the imported file")
            return ImportResult.Unverified(kept.name)
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
    }

    // ------------------------------------------------------------ internals

    /**
     * Moves installs made by the first-generation ModelStore (`filesDir/models/<file>` with a
     * `.sha256` sidecar) into the hub layout, and an interrupted `.part` with them. Only a file
     * whose size and sidecar digest match the pinned entry moves. Idempotent.
     */
    private fun migrateLegacy() {
        ModelCatalog.entries.forEach { entry ->
            val files = filesFor(entry)
            val legacy = File(legacyDir, files.name)
            val legacySidecar = File(legacyDir, "${files.name}.sha256")
            if (!isInstalled(entry) && legacy.isFile && legacy.length() == entry.sizeBytes &&
                runCatching { legacySidecar.readText().trim() == entry.sha256 }.getOrDefault(false)
            ) {
                files.target.parentFile?.mkdirs()
                files.target.delete()
                files.sidecar.delete()
                if (legacy.renameTo(files.target)) {
                    legacySidecar.renameTo(files.sidecar)
                } else {
                    Log.w(TAG, "legacy model ${entry.id} could not be moved; it stays in place")
                }
            }
            val legacyPart = File(legacyDir, "${files.name}.part")
            if (legacyPart.isFile && !files.part.exists()) {
                files.part.parentFile?.mkdirs()
                legacyPart.renameTo(files.part)
            }
        }
    }

    private fun refreshStates() {
        ModelCatalog.entries.forEach { refreshState(it) }
    }

    private fun refreshState(entry: ModelEntry) {
        if (running.contains(entry.id)) return
        val state = when {
            isInstalled(entry) -> HubState(HubStatus.INSTALLED, 1f, "Installed and verified")
            filesFor(entry).part.isFile -> HubState(HubStatus.NOT_INSTALLED, 0f, "Partial download, one tap resumes it")
            else -> HubState(HubStatus.NOT_INSTALLED, 0f, "Not installed")
        }
        publish(entry.id, state)
    }

    private fun pausedMessage(entry: ModelEntry): String =
        if (filesFor(entry).part.isFile) "Paused. One tap resumes." else "Not installed"

    private fun publish(id: String, state: HubState) {
        states.update { it + (id to state) }
    }

    private fun filesFor(entry: ModelEntry): ModelFiles = filesFor(entry.id, fileNameOf(entry))

    private fun filesFor(id: String, fileName: String): ModelFiles = ModelFiles(File(modelsRoot, id), fileName)

    private fun fileNameOf(entry: ModelEntry): String = entry.file.substringAfterLast('/')

    private fun readToken(): String? = try {
        vault.read(TOKEN_NAME)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: VaultException) {
        Log.w(TAG, "hf token could not be read; continuing without it")
        null
    }

    private fun hashInto(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
    }

    private fun freeBytesAt(dir: File): Long =
        runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

    private fun Long.fraction(total: Long): Float = (toFloat() / total).coerceIn(0f, 1f)

    // ------------------------------------------------------------ notifications

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** The foreground notification for a download, as WorkManager requires (dataSync). */
    fun foregroundInfo(id: String, text: String): ForegroundInfo =
        ForegroundInfo(notificationId(id), buildNotification(text, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun progressNotification(id: String, percent: Int) {
        runCatching {
            NotificationManagerCompat.from(app).notify(notificationId(id), buildNotification("Downloading model", percent))
        }
    }

    private fun notifyDone(id: String) {
        runCatching { NotificationManagerCompat.from(app).cancel(notificationId(id)) }
    }

    private fun buildNotification(text: String, percent: Int): Notification =
        NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo_mark)
            .setContentTitle("Memo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .build()

    private fun notificationId(id: String): Int = 0x4d000000 or (id.hashCode() and 0x00ffffff)

    companion object {
        private const val TAG = "ModelHub"
        private const val CHANNEL_ID = "model-downloads"
        private const val TAG_DOWNLOAD = "model-download"
        private const val TOKEN_NAME = "hf_token"
        private const val API = "https://huggingface.co/api"
        private const val LITERT_LIST_URL = "$API/models?author=litert-community&limit=200&sort=downloads"
        private const val DEFAULT_QUERY = "instruct"
        private const val MAX_REPOS = 8
        private const val STORAGE_HEADROOM = 200_000_000L
        private const val PROGRESS_STEP_BYTES = 4_000_000L
        private const val BUFFER_BYTES = 1 shl 16

        private fun workName(id: String) = "hub-model-$id"

        @Volatile
        private var instance: ModelHub? = null

        /** The process-wide hub. The first call migrates legacy installs and scans states. */
        fun get(context: Context): ModelHub =
            instance ?: synchronized(this) {
                instance ?: ModelHub(context).also { instance = it }
            }
    }
}
