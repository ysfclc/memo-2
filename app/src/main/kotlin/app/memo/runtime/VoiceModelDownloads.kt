package app.memo.runtime

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.memo.platform.android.voice.VoiceModel
import app.memo.platform.android.voice.VoiceModelInstaller
import app.memo.platform.android.voice.VoiceModels
import java.util.concurrent.Executor

/**
 * The seam the Models tab's Voice section talks to: reads and installs against a
 * [VoiceModelInstaller] without the UI holding the installer itself.
 */
interface VoiceInstallPort {

    /** True when [model] counts as installed (its marker file matches the pinned digest). */
    fun isInstalled(model: VoiceModel): Boolean

    /**
     * Blocking install: network download plus SHA-256 verification. Never call on the main
     * thread. Every failure is reported as [VoiceModelInstaller.Outcome.Failed]; only the
     * progress callback is delivered along the way.
     */
    fun install(model: VoiceModel, onProgress: (Long, Long) -> Unit): VoiceModelInstaller.Outcome
}

/** Adapter over the real installer so callers share the one `filesDir/voice` root. */
class InstallerVoicePort(private val installer: VoiceModelInstaller) : VoiceInstallPort {

    override fun isInstalled(model: VoiceModel): Boolean = installer.isInstalled(model)

    override fun install(model: VoiceModel, onProgress: (Long, Long) -> Unit): VoiceModelInstaller.Outcome =
        installer.install(model, onProgress)
}

/** Per-model state shown on the Voice list. Compose-observable; writable off the main thread. */
sealed interface VoiceModelStatus {
    data object NotInstalled : VoiceModelStatus
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : VoiceModelStatus
    data object Installed : VoiceModelStatus
    data class Failed(val reason: String) : VoiceModelStatus
}

/**
 * Per-model voice-model downloads for the Models tab's Voice section (plan task M2.A2).
 *
 * Threading: [VoiceModelInstaller.install] blocks on network and digest verification, so
 * installs run on [executor] (one dedicated daemon thread in [MemoRuntime]). Status is Compose
 * snapshot state; jobs may write it directly from [executor]'s thread, and composable readers
 * observe it as usual. [download] is ignored while a model is already Downloading or
 * Installed, so double taps and refresh races queue nothing. A second install is still refused
 * inside the job, and an exception from [VoiceInstallPort.install] becomes [VoiceModelStatus.
 * Failed] rather than a crash.
 *
 * Progress is written at most once per 1% of the total, so hundreds of megabytes do not turn
 * into millions of recompositions.
 */
class VoiceModelDownloads(
    private val port: VoiceInstallPort,
    private val executor: Executor,
    val models: List<VoiceModel> = USER_FACING,
) {

    private val statuses: Map<String, MutableState<VoiceModelStatus>> =
        models.associate { model ->
            model.id to mutableStateOf<VoiceModelStatus>(VoiceModelStatus.NotInstalled)
        }

    /** Highest percent already written per model; shared only with the single executor thread. */
    private val lastPercent = HashMap<String, Int>()

    /** Compose-observable status for [id]; [VoiceModelStatus.NotInstalled] for unknown ids. */
    fun statusOf(id: String): VoiceModelStatus = statuses[id]?.value ?: VoiceModelStatus.NotInstalled

    /**
     * Re-reads [VoiceInstallPort.isInstalled] for every model on [executor]. A model mid
     * download keeps its Downloading state; everything else is set to Installed or
     * NotInstalled (a stale Failed clears, which makes the Retry button a real retry path).
     */
    fun refresh() {
        executor.execute {
            for (model in models) {
                val state = statuses[model.id] ?: continue
                if (state.value is VoiceModelStatus.Downloading) continue
                state.value = if (port.isInstalled(model)) {
                    VoiceModelStatus.Installed
                } else {
                    VoiceModelStatus.NotInstalled
                }
            }
        }
    }

    /**
     * Starts the install for [id]. Ignored while it is already Downloading or Installed.
     * Ends in [VoiceModelStatus.Installed] or [VoiceModelStatus.Failed]; a thrown install
     * reporter becomes a Failed status, never a crash.
     */
    fun download(id: String) {
        val state = statuses[id] ?: return
        val model = models.firstOrNull { it.id == id } ?: return
        if (busy(state.value)) return
        synchronized(lastPercent) { lastPercent[id] = 0 }
        executor.execute {
            if (busy(state.value)) return@execute
            state.value = VoiceModelStatus.Downloading(0L, model.sizeBytes)
            try {
                when (val outcome = port.install(model) { done, total -> reportProgress(id, done, total) }) {
                    is VoiceModelInstaller.Outcome.Installed -> state.value = VoiceModelStatus.Installed
                    is VoiceModelInstaller.Outcome.Failed -> state.value = VoiceModelStatus.Failed(outcome.reason)
                }
            } catch (e: Exception) {
                state.value = VoiceModelStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                synchronized(lastPercent) { lastPercent.remove(id) }
            }
        }
    }

    /** True when the status must not accept or rerun an install. */
    private fun busy(status: VoiceModelStatus): Boolean =
        status is VoiceModelStatus.Downloading || status == VoiceModelStatus.Installed

    /** Throttled progress: writes only when the whole-percent figure advances. */
    private fun reportProgress(id: String, done: Long, total: Long) {
        if (total <= 0L) return
        val percent = ((done * 100) / total).coerceIn(0L, 100L).toInt()
        val write: Boolean
        synchronized(lastPercent) {
            write = percent > (lastPercent[id] ?: 0)
            if (write) lastPercent[id] = percent
        }
        if (!write) return
        statuses[id]?.let { state -> state.value = VoiceModelStatus.Downloading(done, total) }
    }
}

/** The ids a person can install from the Models tab. */
val USER_FACING_VOICE_IDS: Set<String> = setOf(
    "asr-zipformer-en-20m-mobile",
    "asr-whisper-base",
    "tts-kokoro-en-v0-19",
    "vad-silero-v5",
)

/**
 * The user-facing voice models, in engine order (streaming ASR, offline ASR, TTS, VAD). The
 * wake-word model is excluded: nothing calls it until the wake-aware milestone (M2.C2).
 */
val USER_FACING: List<VoiceModel> = VoiceModels.entries.filter { it.id in USER_FACING_VOICE_IDS }
