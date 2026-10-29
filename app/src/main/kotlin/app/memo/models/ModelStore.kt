package app.memo.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.core.catalog.ModelEntryCodec
import app.memo.hub.DeviceModel
import app.memo.hub.HubState
import app.memo.hub.HubStatus
import app.memo.hub.ImportResult
import app.memo.hub.ModelHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * UI-facing facade over [ModelHub]. Keeps the API that ModelsScreen uses; the work is
 * done by the hub (downloads through WorkManager, verified installs, device scan, SAF import).
 *
 * Construction does no I/O on the calling thread: the hub loads its state on Dispatchers.IO,
 * and this store copies the published states into [states] on the main thread.
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

    /** The hub behind this store, for APIs beyond the ones ModelsScreen uses. */
    val hub: ModelHub by lazy { ModelHub.get(context) }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "memo-models") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Observable per-model state for Compose. Written on the main thread only. */
    val states = mutableStateMapOf<String, ModelState>()

    private val discoveredState = mutableStateMapOf<String, ModelEntry>()
    private val discoveredEntries = java.util.concurrent.ConcurrentHashMap<String, ModelEntry>()
    private val discoveryLock = Any()
    private val discoveryReady = CompletableDeferred<Unit>()
    private var discoveredWriteBlocked = false

    /** A read-only explanation when saved discovery metadata could not be trusted or updated. */
    var discoveryError by mutableStateOf<String?>(null)
        private set

    /** Last import outcome, surfaced by the model screen without exposing exception text. */
    var importMessage by mutableStateOf<String?>(null)
        private set

    private val prefs by lazy { context.getSharedPreferences("memo_models", Context.MODE_PRIVATE) }

    init {
        // Off the main thread: the hub loads and migrates; then states are mirrored here.
        scope.launch(Dispatchers.IO) {
            try {
                hub.ensureLoaded()
                loadDiscoveredEntries()
                discoveryReady.complete(Unit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                discoveryReady.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                discoveryReady.completeExceptionally(failure)
                return@launch
            }
            hub.statesFlow.collect { all ->
                scope.launch { mirror(all) }
            }
        }
    }

    private fun mirror(all: Map<String, HubState>) {
        all.forEach { (id, s) -> states[id] = s.toModelState() }
    }

    fun state(entry: ModelEntry): ModelState = states[entry.id] ?: ModelState()

    /** Seed entries and persisted live-discovery entries, for the model browser and picker. */
    fun catalogEntries(): List<ModelEntry> = (ModelCatalog.entries + discoveredState.values).distinctBy { it.id }

    /** Returns the current pinned entry for [id], including a live-discovered record. */
    fun entryFor(id: String): ModelEntry? = ModelCatalog.byId(id) ?: discoveredEntries[id]

    /** The model the orchestrator is allowed to use. Only verified files qualify. */
    var activeId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> entryFor(id)?.let(::isInstalled) == true }
        set(value) {
            runCatching { prefs.edit().putString("active", value).apply() }
        }

    fun isInstalled(entry: ModelEntry): Boolean = hub.isInstalled(entry)

    /** One tap: queue the pinned download (or resume it). Verification happens before install. */
    fun download(entry: ModelEntry) {
        if (entryFor(entry.id) != entry) return
        io.execute { hub.enqueue(entry) }
    }

    /** Live search plus persistent, revision-pinned candidate metadata. Callback runs on the main thread. */
    fun discoverOnline(query: String, onResult: (List<ModelEntry>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val found = try {
                discoveryReady.await()
                hub.discoverOnline(query)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                scope.launch {
                    discoveryError = "Live model search is unavailable."
                    onResult(emptyList())
                }
                return@launch
            }

            synchronized(discoveryLock) {
                val merged = LinkedHashMap(discoveredEntries)
                var changed = false
                var collision = false
                found.forEach { candidate ->
                    val seed = ModelCatalog.byId(candidate.id)
                    if (seed != null) {
                        if (seed != candidate) collision = true
                        return@forEach
                    }
                    val previous = merged[candidate.id]
                    if (previous != null && (previous.repo != candidate.repo || previous.file != candidate.file)) {
                        collision = true
                    } else if (previous != candidate) {
                        merged[candidate.id] = candidate
                        changed = true
                    }
                }

                var error = if (collision) "A model ID collision was ignored." else null
                var writable = !discoveredWriteBlocked
                if (writable && changed) {
                    writable = runCatching {
                        prefs.edit().putString(DISCOVERED_ENTRIES_KEY, ModelEntryCodec.encode(merged.values.toList())).commit()
                    }.getOrDefault(false)
                    if (!writable) {
                        discoveredWriteBlocked = true
                        error = "Saved model metadata could not be updated; new candidates are view-only."
                    }
                }
                if (writable) {
                    discoveredEntries.clear()
                    discoveredEntries.putAll(merged)
                    val installed = merged.values.filter(::isInstalled).associate { entry ->
                        entry.id to ModelState(Status.INSTALLED, 1f, "Installed and verified")
                    }
                    scope.launch {
                        discoveredState.clear()
                        discoveredState.putAll(merged)
                        states.putAll(installed)
                        discoveryError = error
                        onResult(found)
                    }
                } else {
                    scope.launch {
                        discoveryError = error ?: "Saved model metadata is unavailable; new candidates are view-only."
                        onResult(found)
                    }
                }
            }
        }
    }

    /** Pauses the download and keeps the partial file, so the next tap resumes it. */
    fun cancel(entry: ModelEntry) {
        io.execute { hub.pause(entry) }
    }

    /** Deletes the partial file of [entry] and stops its download. */
    fun discardPartial(entry: ModelEntry) {
        io.execute { hub.cancel(entry) }
    }

    /** Removes an installed model after [canUnload] confirms its runtime is idle and released. */
    fun uninstall(entry: ModelEntry, canUnload: () -> Boolean, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val removed = canUnload() && runCatching { hub.uninstall(entry) }.getOrDefault(false)
            if (removed && prefs.getString("active", null) == entry.id) activeId = null
            scope.launch { onResult(removed) }
        }
    }

    /**
     * Import a model file the user picked (Storage Access Framework). A digest match with a
     * pinned entry installs it; otherwise it is kept as an unverified import, never loaded.
     */
    fun importFrom(open: () -> InputStream?, displayName: String) {
        io.execute {
            try {
                val result = hub.importFile(open, displayName)
                scope.launch {
                    when (result) {
                        is ImportResult.Verified -> {
                            activeId = activeId ?: result.entryId
                            importMessage = "Imported and verified. It is available in Installed."
                        }
                        is ImportResult.Unverified -> importMessage = "Saved as unverified; this file cannot be loaded."
                    }
                }
            } catch (_: Exception) {
                scope.launch { importMessage = "Import failed; the selected file could not be read." }
            }
        }
    }

    /** Model-looking files already on this device. [onResult] runs on the main thread. */
    fun discover(onResult: (List<Discovered>) -> Unit) {
        io.execute {
            val found = hub.scanDevice().map { it.toDiscovered() }
            main.post { onResult(found) }
        }
    }

    /** Free bytes on the storage that holds models, for the UI footer. */
    fun freeBytes(): Long = hub.freeBytes()

    private fun loadDiscoveredEntries() {
        val encoded = prefs.getString(DISCOVERED_ENTRIES_KEY, null) ?: return
        val loaded = try {
            ModelEntryCodec.decode(encoded)
        } catch (_: RuntimeException) {
            discoveredWriteBlocked = true
            scope.launch { discoveryError = "Saved model metadata is invalid and was kept unchanged." }
            return
        }
        if (loaded.any { entry ->
                ModelCatalog.byId(entry.id)?.let { seed -> seed != entry } == true
            }
        ) {
            discoveredWriteBlocked = true
            scope.launch { discoveryError = "Saved model metadata conflicts with a pinned seed and was kept unchanged." }
            return
        }
        val seeds = loaded.filter { ModelCatalog.byId(it.id) == null }
        discoveredEntries.putAll(seeds.associateBy { it.id })
        val installed = seeds.filter(::isInstalled).associate { entry ->
            entry.id to ModelState(Status.INSTALLED, 1f, "Installed and verified")
        }
        scope.launch {
            discoveredState.putAll(seeds.associateBy { it.id })
            states.putAll(installed)
        }
    }

    private fun HubState.toModelState(): ModelState = ModelState(
        status = when (status) {
            HubStatus.NOT_INSTALLED -> Status.NOT_INSTALLED
            HubStatus.DOWNLOADING -> Status.DOWNLOADING
            HubStatus.VERIFYING -> Status.VERIFYING
            HubStatus.INSTALLED -> Status.INSTALLED
            HubStatus.FAILED -> Status.FAILED
        },
        progress = progress,
        message = message,
    )

    private fun DeviceModel.toDiscovered() = Discovered(
        path = path,
        name = name,
        sizeBytes = sizeBytes,
        matchesCatalogId = matchesCatalogId,
    )

    private companion object {
        const val DISCOVERED_ENTRIES_KEY = "discovered_model_entries_v1"
    }
}
