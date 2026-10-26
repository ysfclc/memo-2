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

    /** Approve or veto a waiting gate. The artifact hash is re-read on the runtime thread. */
    fun decide(runId: String, stepId: String, approve: Boolean) {
        exec.execute {
            val state = engine.run(runId)?.stepById(stepId)
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
     * loads. A failure leaves the app usable in memory with writes disabled.
     */
    private fun bootstrap() {
        persistence.migrateLegacyPlaintext()
        persistence.migrationProblem?.let { loadProblem = appendProblem(loadProblem, "legacy import: $it") }

        if (loadProblem.isEmpty()) {
            loadOrNull("workspace") { persistence.loadWorkspace() }?.let { workspace = it }
            loadOrNull("runs") { persistence.loadRuns() }.orEmpty().forEach { engine.putRun(it) }
            val restoredAudit = loadOrNull("audit") { persistence.loadAudit() }.orEmpty()
            auditChain = HashChainAudit(
                sink = AuditSink { entries -> writeAudit(entries) },
                restored = restoredAudit,
            )
        }
        publish("")
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
        )
        main.post { snapshot.value = next }
    }

    private fun storageMessage(): String {
        val parts = mutableListOf<String>()
        if (loadProblem.isNotEmpty()) {
            parts += "Storage is read-only this session: $loadProblem"
        }
        saveErrors.forEach { (what, reason) -> parts += "Could not save $what ($reason)" }
        return parts.joinToString(" · ")
    }

    /** Saves the workspace and the runs. Skipped entirely while storage is read-only. */
    private fun persistAll() {
        if (loadProblem.isNotEmpty()) return
        save("workspace") { persistence.saveWorkspace(workspace) }
        save("runs") { persistence.saveRuns(engine.runs()) }
    }

    /** Audit sink: receives the full chain after every append. */
    private fun writeAudit(entries: List<AuditEntry>) {
        if (loadProblem.isNotEmpty()) return
        save("audit") { persistence.saveAudit(entries) }
    }

    private fun save(what: String, block: () -> Unit) {
        try {
            block()
            saveErrors.remove(what)
        } catch (e: Exception) {
            Log.w(TAG, "could not save $what", e)
            saveErrors[what] = e.message ?: e.javaClass.simpleName
        }
    }

    private companion object {
        const val TAG = "MemoRuntime"
        val TERMINAL = setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)
    }
}
