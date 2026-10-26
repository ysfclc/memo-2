package app.memo.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.memo.core.audit.AuditSink
import app.memo.core.audit.HashChainAudit
import app.memo.core.domain.AgentRun
import app.memo.core.domain.AuditEntry
import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.Person
import app.memo.core.domain.RunStatus
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
import app.memo.core.registry.Registry
import app.memo.core.registry.Workspace
import app.memo.models.ModelStore
import app.memo.platform.android.vault.KeystoreVault
import org.json.JSONObject
import java.util.concurrent.Executors

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
 * `WorkflowEngine.recoverAfterRestart`, which lands them in NEEDS_REVIEW.
 *
 * All mutations run on one background thread; UI state is published on the
 * main thread as an immutable [Snapshot].
 */
class MemoRuntime(context: Context) {

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
    private val main = Handler(Looper.getMainLooper())

    private val persistence = Persistence(KeystoreVault(context), context.filesDir) { Log.w(TAG, it) }

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

    val snapshot: MutableState<Snapshot> = mutableStateOf(Snapshot())

    init {
        exec.execute { bootstrap() }
    }

    // ------------------------------------------------------------ commands

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
     * Approve or veto a waiting gate. The artifact hash is re-read on the runtime thread.
     * While storage is read-only, an approval whose run has later side effects is not delivered.
     */
    fun decide(runId: String, stepId: String, approve: Boolean) {
        exec.execute {
            val run = engine.run(runId)
            if (approve && readOnly() && run != null && hasSideEffectAfter(run, stepId)) {
                publish(READ_ONLY_APPROVAL)
                return@execute
            }
            val state = run?.stepById(stepId)
            if (state != null) {
                runCatching {
                    engine.deliverGate(
                        runId,
                        stepId,
                        approve,
                        if (approve) state.artifactContentHash else "",
                        if (approve) "approved on device" else "vetoed on device",
                    )
                    engine.advance(runId)
                }
            }
            persistAll()
            publish(if (approve) "Approved." else "Vetoed.")
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
        if (loadProblem.isEmpty()) recoverInterruptedRuns()
        publish("")
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
                Log.w(TAG, "could not recover run ${run.id}", e)
                loadProblem = appendProblem(loadProblem, "run ${run.id} could not be recovered")
            }
        }
        persistAll()
    }

    private fun <T> loadOrNull(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "could not load $what", e)
            loadProblem = appendProblem(loadProblem, "$what could not be read (${e.message ?: e.javaClass.simpleName})")
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
            Log.w(TAG, "could not checkpoint run ${run.id}", e)
            saveErrors["runs"] = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    private fun startRun(spec: WorkflowSpec) {
        val result = runCatching {
            val run = engine.createRun(spec)
            engine.advance(run.id)
        }
        persistAll()
        publish(result.exceptionOrNull()?.message?.let { "Could not start: $it" } ?: "")
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
            Log.w(TAG, "could not save $what", e)
            saveErrors[what] = e.message ?: e.javaClass.simpleName
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
