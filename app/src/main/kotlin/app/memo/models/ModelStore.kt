package app.memo.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.hub.DeviceModel
import app.memo.hub.HubState
import app.memo.hub.HubStatus
import app.memo.hub.ImportResult
import app.memo.hub.ModelHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException
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

    private val prefs by lazy { context.getSharedPreferences("memo_models", Context.MODE_PRIVATE) }

    init {
        // Off the main thread: the hub loads and migrates; then states are mirrored here.
        scope.launch(Dispatchers.IO) {
            hub.ensureLoaded()
            hub.statesFlow.collect { all ->
                scope.launch { mirror(all) }
            }
        }
    }

    private fun mirror(all: Map<String, HubState>) {
        all.forEach { (id, s) -> states[id] = s.toModelState() }
    }

    fun state(entry: ModelEntry): ModelState = states[entry.id] ?: ModelState()

    /** The model the orchestrator is allowed to use. Only verified files qualify. */
    var activeId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> ModelCatalog.byId(id)?.let(::isInstalled) == true }
        set(value) {
            runCatching { prefs.edit().putString("active", value).apply() }
        }

    fun isInstalled(entry: ModelEntry): Boolean = hub.isInstalled(entry)

    /** One tap: queue the pinned download (or resume it). Verification happens before install. */
    fun download(entry: ModelEntry) {
        io.execute { hub.enqueue(entry) }
    }

    /** Pauses the download and keeps the partial file, so the next tap resumes it. */
    fun cancel(entry: ModelEntry) {
        io.execute { hub.pause(entry) }
    }

    /** Deletes the partial file of [entry] and stops its download. */
    fun discardPartial(entry: ModelEntry) {
        io.execute { hub.cancel(entry) }
    }

    /**
     * Import a model file the user picked (Storage Access Framework). A digest match with a
     * pinned entry installs it; otherwise it is kept as an unverified import, never loaded.
     */
    fun importFrom(open: () -> InputStream?, displayName: String) {
        io.execute {
            try {
                val result = hub.importFile(open, displayName)
                if (result is ImportResult.Verified) {
                    scope.launch { activeId = activeId ?: result.entryId }
                }
            } catch (_: IOException) {
                // Nothing is installed; the temporary copy has been removed by the hub.
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
}
