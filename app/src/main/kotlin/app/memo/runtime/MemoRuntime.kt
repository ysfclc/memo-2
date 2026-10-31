package app.memo.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.memo.core.audit.AuditSink
import app.memo.core.audit.HashChainAudit
import app.memo.core.catalog.ModelCatalog
import app.memo.core.codec.SynCodec
import app.memo.core.domain.AgentRun
import app.memo.core.domain.AuditEntry
import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.Person
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepStatus
import app.memo.core.domain.Task
import app.memo.core.domain.WorkflowSpec
import app.memo.core.engine.ArtifactBuilder
import app.memo.core.engine.Attempt
import app.memo.core.engine.DataStore
import app.memo.core.engine.ExecutorRoutes
import app.memo.core.engine.MetricExecutor
import app.memo.core.engine.OutboundAudit
import app.memo.core.engine.ReasonExecutor
import app.memo.core.engine.WorkflowEngine
import app.memo.core.plan.CommandGrammar
import app.memo.core.platform.ModelRef
import app.memo.core.registry.Registry
import app.memo.core.registry.Workspace
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.ModelFormat
import app.memo.models.ModelStore
import app.memo.platform.android.llm.InferenceService
import app.memo.platform.android.llm.litert.LiteRtLmRuntime
import app.memo.platform.android.llm.llama.LlamaCppRuntime
import app.memo.platform.android.voice.SherpaOfflineAsr
import app.memo.platform.android.voice.SherpaRecorderCapture
import app.memo.platform.android.voice.SherpaRecorderTranscriber
import app.memo.platform.android.voice.VoiceMessageStore
import app.memo.platform.android.voice.VoiceModelInstaller
import app.memo.platform.android.voice.VoiceModels
import app.memo.platform.android.vault.KeystoreVault
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Owns the single source of truth for the app: the persisted workspace, the
 * workflow engine, the hash-chained audit trail and the local model store.
 *
 * All state is persisted through [Persistence] into the encrypted vault. The
 * workspace, runs and audit are loaded on the runtime thread after the one-time
 * legacy import, so construction never blocks the main thread. Until that load
 * finishes the workspace is empty. If storage cannot be read or written, the
 * app keeps running in memory and [Snapshot.storageError] says why.
 *
 * Read-only mode applies while a load or import problem is set, or while a vault
 * recovery from a backup is waiting for [confirmRecovery]. In that mode nothing is
 * written, the engine checkpoint throws so side effects are refused, and approvals
 * that lead to later side effects are not delivered.
 *
 * Crash safety: the engine checkpoints a run synchronously before every side-effect
 * executor. Runs are persisted before the audit chain, both in [persistAll] and in the
 * audit sink. At bootstrap, runs that were mid side effect are recovered with
 * `WorkflowEngine.recoverAfterRestart`, which lands them in NEEDS_REVIEW. Runs restored
 * from a backup that hold a side effect after a completed gate are marked NEEDS_REVIEW.
 *
 * Run driver: after bootstrap, after every decision and review action, and on a timer,
 * the runtime advances each RUNNING run whose waiting step is due. The timer is armed only
 * while a run waits on a backoff, fires at most once per second, and is idle otherwise.
 *
 * All mutations run on one background thread (the runtime thread). The timer only posts
 * work to that thread. UI state is published on the main thread as an immutable [Snapshot].
 */
class MemoRuntime(context: Context) {

    private val appContext = context.applicationContext

    data class Snapshot(
        val runs: List<AgentRun> = emptyList(),
        val people: List<Person> = emptyList(),
        val companies: List<Company> = emptyList(),
        val tasks: List<Task> = emptyList(),
        val drafts: List<ContentDraft> = emptyList(),
        val auditCount: Int = 0,
        val auditOk: Boolean = true,
        val quarantined: Int = 0,
        val lastReply: String = "",
        val storageError: String = "",
        /** A vault backup was used at load; writes stay paused until [confirmRecovery]. */
        val recoveryPending: Boolean = false,
    )

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "memo-runtime") }
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "memo-runtime-tick").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val vault = KeystoreVault(context)
    private val persistence = Persistence(vault, context.filesDir) { Log.w(TAG, it) }

    /** Replaced on the runtime thread once loaded. Empty until then. */
    @Volatile
    var workspace: Workspace = Workspace()
        private set

    /** Why storage is read-only for this session (failed import or load). Empty when writable. */
    private var loadProblem: String = ""

    /**
     * Set when the vault had to be restored from a backup at load. Writes stay off until
     * [confirmRecovery] clears it. Null when there is nothing to confirm.
     */
    private var pendingRecovery: String? = null

    /** Failed saves by entry name. An entry is removed on its next successful save. */
    private val saveErrors = linkedMapOf<String, String>()

    /** True while a timer tick is queued on the runtime thread. Only touched on the runtime thread. */
    private var tickPending: Boolean = false

    private var auditChain: HashChainAudit = HashChainAudit(
        sink = AuditSink { entries -> writeAudit(entries) },
    )

    val engine: WorkflowEngine = WorkflowEngine(
        routes = ExecutorRoutes(
            reason = ReasonExecutor { _, step -> Attempt.Completed(output = step.prompt) },
            browser = app.memo.core.engine.BrowserExecutor { _, _ ->
                Attempt.Failed("browser automation is not available in this build")
            },
            app = app.memo.core.engine.AppAutomationExecutor { _, _ ->
                Attempt.Failed("app automation is not available in this build")
            },
            metric = MetricExecutor { _, _ ->
                Attempt.Failed("metric collection is not available in this build")
            },
            dataStore = DataStore { kind, _, payload -> persistRow(kind, payload) },
            artifactBuilder = ArtifactBuilder { _, type, prior -> buildArtifact(type, prior) },
        ),
        packetProvider = { PacketBuilder.build(workspace) },
        audit = OutboundAudit { event -> auditChain.append(event, System.currentTimeMillis()) },
        checkpoint = { run -> checkpointRun(run) },
    )

    /** Local model downloads, verification, import and discovery. */
    val models = ModelStore(context)

    /**
     * The one resident chat model. Every consumer — the chat UI and the voice loop — queues
     * through this, so a second model can never load behind the manager's back.
     */
    val inference = InferenceManager(
        factory = { format -> runtimeFor(format) },
        onStateChanged = { state -> InferenceService.onManagerState(appContext, state) },
    )

    internal val chats = ChatRepository(vault)
    internal val chatSettings = ChatSettingsRepository(vault)
    internal val chatService = ChatService(
        repository = chats,
        inference = inference,
        resolveModel = ::resolveInstalledChatModel,
    )

    /** The Talk tab's hands-free session; engines are built lazily on first [VoiceSession.prepare]. */
    val voice: VoiceSession by lazy {
        VoiceSession(
            appContext = appContext,
            inference = inference,
            chatModel = {
                val activeId = models.activeId ?: return@VoiceSession null
                val entry = ModelCatalog.byId(activeId) ?: return@VoiceSession null
                val file = models.hub.installedFile(entry) ?: return@VoiceSession null
                ModelRef(
                    id = entry.id,
                    format = entry.format,
                    path = file.absolutePath,
                    sha256 = entry.sha256,
                    sizeBytes = entry.sizeBytes,
                )
            },
        )
    }

    /**
     * Hold-to-record voice messages on the Text tab (TASKS M2.B1 mini-task 1). Transcription
     * builds the pinned offline Whisper lazily through [LazyRecorderTranscriber]: when the
     * model is not installed the load throws a plain reason, which the recorder reports as
     * Untranscribed - the audio is kept, and no fake text is produced.
     */
    val voiceMessages: VoiceMessageRecorder by lazy {
        val installer = VoiceModelInstaller(File(appContext.filesDir, "voice"))
        VoiceMessageRecorder(
            capture = SherpaRecorderCapture(appContext),
            store = VoiceMessageStore(appContext),
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "memo-voice-messages").apply { isDaemon = true }
            },
            transcriber = LazyRecorderTranscriber {
                val whisper = VoiceModels.entries.first { it.id == "asr-whisper-base" }
                if (!installer.isInstalled(whisper)) {
                    throw IllegalStateException(
                        "The Whisper voice-message model is not installed. Download it in Models.",
                    )
                }
                SherpaRecorderTranscriber(SherpaOfflineAsr(installer.dirFor(whisper)))
            },
        )
    }

    /**
     * Per-model voice downloads for the Models tab's Voice section (plan task M2.A2). Installs
     * block on network and SHA-256 verification, so they run on a dedicated daemon thread;
     * [VoiceModelDownloads] writes its Compose state from that thread. The installer reads and
     * writes the same `filesDir/voice` root the voice session, read-aloud and voice-message
     * transcription use.
     */
    val voiceModelDownloads: VoiceModelDownloads by lazy {
        VoiceModelDownloads(
            port = InstallerVoicePort(VoiceModelInstaller(File(appContext.filesDir, "voice"))),
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "memo-voice-downloads").apply { isDaemon = true }
            },
        )
    }

    private fun runtimeFor(format: ModelFormat): LlmRuntime = when (format) {
        ModelFormat.LITERTLM -> LiteRtLmRuntime(
            cacheDir = File(appContext.cacheDir, "litert-lm"),
            availableRam = { availableRamBytes() },
        )
        ModelFormat.GGUF -> LlamaCppRuntime(availableRam = { availableRamBytes() })
    }

    private fun availableRamBytes(): Long {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }

    val snapshot: MutableState<Snapshot> = mutableStateOf(Snapshot())

    init {
        exec.execute { bootstrap() }
    }

    // ------------------------------------------------------------ commands

    private fun resolveInstalledChatModel(modelId: String): ModelRef? {
        val entry = models.entryFor(modelId) ?: return null
        val file = models.hub.installedFile(entry) ?: return null
        return ModelRef(entry.id, entry.format, file.absolutePath, entry.sha256, entry.sizeBytes)
    }

    /** Entry point for typed or spoken input. Returns an immediate reply. */
    fun handleUtterance(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "Type or say a command."
        val parsed = CommandGrammar.parse(text, System.currentTimeMillis())
        if (parsed == null) return "I did not recognise that. ${Planner.helpText()}"
        if (parsed == CommandGrammar.VoiceCommand.Cancel) {
            exec.execute { cancelActiveRuns() }
            return "Stopping active runs."
        }
        val spec = Planner.plan(parsed, System.currentTimeMillis())
            ?: return "That command cannot be planned yet."
        exec.execute { startRun(spec) }
        return "Planned \"${spec.title}\". Review it in Live."
    }

    /**
     * Approve or veto a waiting gate, binding the hash that was on screen. Equivalent to
     * the [decide] overload with a null [displayedHash]: the gate's current artifact hash is used.
     */
    fun decide(runId: String, stepId: String, approve: Boolean) = decide(runId, stepId, approve, null)

    /**
     * Approve or veto a waiting gate. [displayedHash] is the content hash the user saw. It is
     * bound at approval, so content that changed since is refused (NEEDS_REVIEW) rather than
     * approved silently. A null [displayedHash] uses the gate's current artifact hash.
     * While storage is read-only, an approval whose run has later side effects is not delivered.
     */
    fun decide(runId: String, stepId: String, approve: Boolean, displayedHash: String?) {
        exec.execute {
            val run = engine.run(runId)
            if (approve && readOnly() && run != null && hasSideEffectAfter(run, stepId)) {
                publish(READ_ONLY_APPROVAL)
                return@execute
            }
            val state = run?.stepById(stepId)
            if (state == null) {
                publish("That step is not waiting for a decision.")
                return@execute
            }
            val hash = if (approve) displayedHash ?: state.artifactContentHash else ""
            val outcome = runCatching {
                engine.deliverGate(
                    runId,
                    stepId,
                    approve,
                    hash,
                    if (approve) "approved on device" else "vetoed on device",
                )
            }
            persistAll()
            drive()
            publish(RuntimeRules.decisionMessage(approve, stepId, outcome))
        }
    }

    /**
     * Resolves a step in review. [retry] = true re-arms it (a side effect is then executed again,
     * after the human decision); false skips it, and skipping a gate also skips the steps bound to it.
     * Retry is refused while storage is read-only.
     */
    fun resolveReview(runId: String, stepId: String, retry: Boolean) {
        exec.execute {
            if (retry && readOnly()) {
                publish(READ_ONLY_REASON)
                return@execute
            }
            val outcome = runCatching { engine.resolveReview(runId, stepId, retry) }
            persistAll()
            drive()
            publish(
                outcome.exceptionOrNull()?.let { "Could not resolve the review: ${SynCodec.safeMessage(it, 160)}" }
                    ?: if (retry) "Review: step queued for another attempt." else "Review: step skipped.",
            )
        }
    }

    /**
     * Continues an interrupted run. Interrupted side effects are not re-run automatically: they
     * need [resolveReview] first. Refused while storage is read-only.
     */
    fun resumeRun(runId: String) {
        exec.execute {
            if (readOnly()) {
                publish(READ_ONLY_REASON)
                return@execute
            }
            val outcome = runCatching { engine.resumeInterrupted(runId) }
            persistAll()
            drive()
            publish(
                outcome.exceptionOrNull()?.let { "Could not resume the run: ${SynCodec.safeMessage(it, 160)}" }
                    ?: "Run resumed.",
            )
        }
    }

    /**
     * Explicit user reset: deletes the vault (workspace, runs, audit, marker) and its Keystore
     * key, then starts empty. Nothing is recovered afterwards. This cannot be undone, so the UI
     * must ask for confirmation first. If the reset does not complete, storage stays read-only.
     */
    fun resetVault() {
        exec.execute {
            try {
                vault.resetAll()
                engine.forgetAllRuns()
                workspace = Workspace()
                auditChain = HashChainAudit(sink = AuditSink { entries -> writeAudit(entries) })
                loadProblem = ""
                pendingRecovery = null
                saveErrors.clear()
                publish("Vault reset. Starting empty.")
            } catch (e: Exception) {
                Log.w(TAG, "vault reset did not complete (${SynCodec.safeMessage(e)})")
                loadProblem = appendProblem(loadProblem, "reset did not complete (${SynCodec.safeMessage(e)})")
                publish("Reset did not complete; storage stays read-only.")
            }
        }
    }

    /**
     * Accepts a vault recovery from a backup, so writes resume. The primary is then
     * written from the recovered state on the next save. Does nothing when there is no pending recovery.
     */
    fun confirmRecovery() {
        exec.execute {
            if (pendingRecovery != null) {
                pendingRecovery = null
                persistAll()
                publish("Recovery confirmed.")
            } else {
                publish("")
            }
        }
    }

    fun stopEverything() {
        exec.execute {
            cancelActiveRuns()
            publish("All active runs stopped.")
        }
    }

    /** Runs on the runtime thread: all mutations go through here. */
    fun onRuntime(block: () -> Unit) = exec.execute(block)

    fun publishNow(message: String = "") = exec.execute { publish(message) }

    // ----------------------------------------------------------- internals

    /**
     * Runs once on the runtime thread before any command: legacy import, then
     * loads, then recovery of runs that were mid side effect. A failure leaves the
     * app usable in memory with writes disabled.
     */
    private fun bootstrap() {
        persistence.migrateLegacyPlaintext()
        // A plaintext cleanup failure after a completed import is reported, but does not block the load.
        persistence.migrationProblem?.takeIf { !persistence.migrationFinished }
            ?.let { loadProblem = appendProblem(loadProblem, "legacy import: $it") }

        if (loadProblem.isEmpty()) {
            loadOrNull("workspace") { persistence.loadWorkspace() }?.let { workspace = it }
            loadOrNull("runs") { persistence.loadRuns() }.orEmpty().forEach { engine.putRun(it) }
            val restoredAudit = loadOrNull("audit") { persistence.loadAudit() }.orEmpty()
            auditChain = HashChainAudit(
                sink = AuditSink { entries -> writeAudit(entries) },
                restored = restoredAudit,
            )
        }
        // Set before recovery, so a restored backup keeps the session read-only until confirmed.
        pendingRecovery = persistence.lastRecovery
            .takeIf { it.isNotEmpty() }
            ?.let { "Recovered from a backup: ${it.joinToString("; ")}" }
        if (pendingRecovery != null) markRunsFromBackupForReview()
        if (loadProblem.isEmpty()) recoverInterruptedRuns()
        drive()
        publish("")
    }

    /**
     * Runs restored from a backup that hold a side effect after a COMPLETED gate are marked
     * NEEDS_REVIEW: the backup may predate the outcome of that side effect. Nothing executes here.
     */
    private fun markRunsFromBackupForReview() {
        engine.runs()
            .filter { it.status !in TERMINAL && RuntimeRules.needsBackupReview(it) }
            .forEach { run ->
                runCatching {
                    engine.markNeedsReview(run.id, "restored from a backup; confirm before any side effect")
                }
            }
    }

    /**
     * Runs restored as RUNNING or INTERRUPTED that have a RUNNING step go through the
     * engine's crash-recovery rules: a side effect becomes NEEDS_REVIEW, never a re-run.
     * Nothing is advanced here. Changed runs are persisted when writes are allowed.
     */
    private fun recoverInterruptedRuns() {
        val interrupted = engine.runs().filter { run ->
            (run.status == RunStatus.RUNNING || run.status == RunStatus.INTERRUPTED) &&
                run.steps.any { it.status == StepStatus.RUNNING }
        }
        if (interrupted.isEmpty()) return
        interrupted.forEach { run ->
            try {
                engine.recoverAfterRestart(run.id)
            } catch (e: Exception) {
                Log.w(TAG, "could not recover run ${run.id} (${SynCodec.safeMessage(e)})")
                loadProblem = appendProblem(loadProblem, "run ${run.id} could not be recovered")
            }
        }
        persistAll()
    }

    /**
     * Run driver, on the runtime thread: advances due runs, then arms the timer if a run
     * still waits on a backoff. Safe to call at any time.
     */
    private fun drive() {
        advanceDueRuns(System.currentTimeMillis())
        scheduleNextTick(System.currentTimeMillis())
    }

    /** Advances every RUNNING run whose waiting step has reached its resumeAt. Persists when anything moved. */
    private fun advanceDueRuns(now: Long) {
        val due = engine.runs().filter { RuntimeRules.isDue(it, now) }
        if (due.isEmpty()) return
        due.forEach { run -> runCatching { engine.advance(run.id) } }
        persistAll()
    }

    /**
     * Arms one timer tick for the earliest backoff still in the future. The tick is at least one
     * second after the previous one. Nothing is armed when no run is waiting, so the runtime idles.
     */
    private fun scheduleNextTick(now: Long) {
        if (tickPending) return
        val wake = RuntimeRules.nextWake(engine.runs(), now) ?: return
        tickPending = true
        val tick = Runnable {
            exec.execute {
                tickPending = false
                advanceDueRuns(System.currentTimeMillis())
                scheduleNextTick(System.currentTimeMillis())
            }
        }
        ticker.schedule(
            tick,
            RuntimeRules.tickDelayMs(wake, now),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun <T> loadOrNull(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "could not load $what (${SynCodec.safeMessage(e)})")
            loadProblem = appendProblem(loadProblem, "$what could not be read (${SynCodec.safeMessage(e)})")
            null
        }

    private fun appendProblem(current: String, next: String): String =
        if (current.isEmpty()) next else "$current · $next"

    /** True while writes are refused: a load or import problem, or an unconfirmed recovery. */
    private fun readOnly(): Boolean = loadProblem.isNotEmpty() || pendingRecovery != null

    /** True when a step after the gate [stepId] is a side effect. */
    private fun hasSideEffectAfter(run: AgentRun, stepId: String): Boolean {
        val index = run.steps.indexOfFirst { it.spec.id == stepId }
        return index >= 0 && run.steps.drop(index + 1).any { it.spec.isSideEffect }
    }

    /**
     * Engine checkpoint, called synchronously on the runtime thread right before a
     * side-effect executor runs. Writes the full runs list with [run] in place. Throws when
     * read-only or when the write fails, so the engine refuses the side effect.
     */
    private fun checkpointRun(run: AgentRun) {
        check(!readOnly()) { READ_ONLY_REASON }
        try {
            persistence.saveRunsWith(run, engine.runs())
            saveErrors.remove("runs")
        } catch (e: Exception) {
            Log.w(TAG, "could not checkpoint run ${run.id} (${SynCodec.safeMessage(e)})")
            saveErrors["runs"] = SynCodec.safeMessage(e)
            throw e
        }
    }

    private fun startRun(spec: WorkflowSpec) {
        val result = runCatching {
            val run = engine.createRun(spec)
            engine.advance(run.id)
        }
        persistAll()
        drive()
        publish(result.exceptionOrNull()?.let { "Could not start: ${SynCodec.safeMessage(it, 160)}" } ?: "")
    }

    private fun cancelActiveRuns() {
        engine.runs()
            .filter { it.status !in TERMINAL }
            .forEach { runCatching { engine.cancel(it.id, "stopped by user") } }
        persistAll()
    }

    private fun buildArtifact(type: String, prior: Map<String, String>): Attempt {
        val json = prior.values.lastOrNull { it.trim().startsWith("{") }
            ?: return Attempt.Failed("nothing filled for '$type' yet")
        return Attempt.Completed(output = json)
    }

    private fun persistRow(kind: String, payload: String): String {
        val raw = runCatching { JSONObject(payload) }.getOrNull() ?: return "payload is not JSON"
        val now = System.currentTimeMillis()
        val put: Registry.Put<*>? = when (kind) {
            "person" -> Person.fromJson(raw)?.let { workspace.people.upsert(it, now) }
            "company" -> Company.fromJson(raw)?.let { workspace.companies.upsert(it, now) }
            "task" -> Task.fromJson(raw)?.let { workspace.tasks.upsert(it, now) }
            "draft" -> ContentDraft.fromJson(raw)?.let { workspace.drafts.upsert(it, now) }
            else -> return "kind '$kind' is not persisted in this build"
        }
        return when (put) {
            null -> "invalid $kind"
            is Registry.Put.Failed<*> -> put.reason
            else -> ""
        }
    }

    private fun publish(message: String) {
        val next = Snapshot(
            runs = engine.runs(),
            people = workspace.people.items(),
            companies = workspace.companies.items(),
            tasks = workspace.tasks.items(),
            drafts = workspace.drafts.items(),
            auditCount = auditChain.size,
            auditOk = auditChain.verify().ok,
            quarantined = workspace.quarantineCount(),
            lastReply = message,
            storageError = storageMessage(),
            recoveryPending = pendingRecovery != null,
        )
        main.post { snapshot.value = next }
    }

    private fun storageMessage(): String {
        val parts = mutableListOf<String>()
        if (loadProblem.isNotEmpty()) {
            parts += "Storage is read-only this session: $loadProblem"
        }
        pendingRecovery?.let { parts += "$it. Saving is paused until you confirm." }
        if (persistence.migrationFinished) {
            persistence.migrationProblem?.let { parts += "Legacy plaintext cleanup incomplete: $it" }
        }
        saveErrors.forEach { (what, reason) -> parts += "Could not save $what ($reason)" }
        return parts.joinToString(" · ")
    }

    /** Saves the workspace, the runs and the audit chain, in that order. Skipped entirely while read-only. */
    private fun persistAll() {
        if (readOnly()) return
        save("workspace") { persistence.saveWorkspace(workspace) }
        save("runs") { persistence.saveRuns(engine.runs()) }
        save("audit") { persistence.saveAudit(auditChain.entries()) }
    }

    /**
     * Audit sink: receives the full chain after every append. Runs are written first.
     * If that fails, the audit is not written, so the trail never gets ahead of the runs.
     */
    private fun writeAudit(entries: List<AuditEntry>) {
        if (readOnly()) return
        if (save("runs") { persistence.saveRuns(engine.runs()) }) {
            save("audit") { persistence.saveAudit(entries) }
        }
    }

    /** Runs [block] and records a failure in [saveErrors]. Returns true on success. */
    private fun save(what: String, block: () -> Unit): Boolean =
        try {
            block()
            saveErrors.remove(what)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not save $what (${SynCodec.safeMessage(e)})")
            saveErrors[what] = SynCodec.safeMessage(e)
            false
        }

    private companion object {
        const val TAG = "MemoRuntime"
        const val READ_ONLY_REASON = "storage is read-only this session; side effects are refused"
        const val READ_ONLY_APPROVAL =
            "Storage is read-only; approvals that lead to outbound actions are disabled until storage is fixed"

        val TERMINAL = setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)
    }
}

/**
 * Pure rules behind [MemoRuntime]: which runs are due, when the timer wakes, how a decision
 * is reported, and which restored runs need review. No Android types, so they are unit-tested on the JVM.
 */
internal object RuntimeRules {

    /** Minimum spacing between timer ticks while a run waits. */
    const val TICK_MIN_MS: Long = 1_000L

    /** A RUNNING run is due when its waiting step has no external ticket and its resumeAt has passed. */
    fun isDue(run: AgentRun, now: Long): Boolean {
        if (run.status != RunStatus.RUNNING) return false
        val step = run.steps.getOrNull(run.pointer) ?: return false
        return step.externalTicket.isEmpty() && step.resumeAt <= now
    }

    /** Earliest future resumeAt among RUNNING runs' waiting steps, or null when nothing waits on a backoff. */
    fun nextWake(runs: List<AgentRun>, now: Long): Long? = runs.asSequence()
        .filter { it.status == RunStatus.RUNNING }
        .mapNotNull { run -> run.steps.getOrNull(run.pointer)?.takeIf { it.externalTicket.isEmpty() }?.resumeAt }
        .filter { it > now }
        .minOrNull()

    /** Delay for the next tick: the wake time, but never sooner than [TICK_MIN_MS]. */
    fun tickDelayMs(wake: Long, now: Long): Long = maxOf(TICK_MIN_MS, wake - now)

    /** The message shown after a gate decision. Errors are reduced to a short, content-free summary. */
    fun decisionMessage(approve: Boolean, stepId: String, outcome: Result<AgentRun>): String {
        outcome.exceptionOrNull()?.let {
            return "Could not record the decision: ${SynCodec.safeMessage(it, 160)}"
        }
        if (!approve) return "Vetoed."
        val gate = outcome.getOrNull()?.stepById(stepId)
        return if (gate?.status == StepStatus.NEEDS_REVIEW) "Content changed, review again." else "Approved."
    }

    /** A run restored from a backup needs review when a side effect follows a COMPLETED gate. */
    fun needsBackupReview(run: AgentRun): Boolean =
        run.steps.indices.any { i ->
            run.steps[i].spec.isSideEffect &&
                run.steps.subList(0, i).any { it.spec.kind == StepKind.HUMAN_APPROVAL && it.status == StepStatus.COMPLETED }
        }
}
