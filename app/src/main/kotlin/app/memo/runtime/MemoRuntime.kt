package app.memo.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.memo.core.audit.AuditEntry
import app.memo.core.audit.HashChainAudit
import app.memo.core.domain.AgentRun
import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.Person
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
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
import app.memo.vault.MemoVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Owns the single source of truth for the app: the persisted workspace, the
 * workflow engine, the hash-chained audit trail and the local model store.
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
    )

    private val dir: File = context.filesDir
    private val workspaceFile = File(dir, "workspace.syn.json")
    private val runsFile = File(dir, "runs.json")
    private val auditFile = File(dir, "audit.json")

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "memo-runtime") }
    private val main = Handler(Looper.getMainLooper())

    val workspace: Workspace = loadWorkspace()

    private val auditChain: HashChainAudit = HashChainAudit(
        sink = { entries -> writeAudit(entries) },
        restored = readAudit(),
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
        audit = OutboundAudit { event -> auditChain.append(event, System.currentTimeMillis()) },
    )

    /** Local model downloads, verification, import and discovery. */
    val models = ModelStore(context)

    val snapshot: MutableState<Snapshot> = mutableStateOf(Snapshot())

    init {
        readRuns().forEach { engine.putRun(it) }
        exec.execute { publish("") }
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
        val runs = engine.runs()
        val next = Snapshot(
            runs = runs,
            people = workspace.people.items(),
            companies = workspace.companies.items(),
            tasks = workspace.tasks.items(),
            drafts = workspace.drafts.items(),
            auditCount = auditChain.size,
            auditOk = auditChain.verify().ok,
            quarantined = workspace.quarantineCount(),
            lastReply = message,
        )
        main.post { snapshot.value = next }
    }

    private fun persistAll() {
        runCatching {
            val tmp = File(dir, workspaceFile.name + ".tmp")
            FileOutputStream(tmp).use { MemoVault.save(workspace, it) }
            if (workspaceFile.exists()) workspaceFile.delete()
            tmp.renameTo(workspaceFile)
        }
        runCatching {
            val arr = JSONArray()
            engine.runs().forEach { arr.put(it.toJson()) }
            runsFile.writeText(arr.toString())
        }
    }

    private fun loadWorkspace(): Workspace {
        if (!workspaceFile.exists()) return Workspace()
        return runCatching {
            FileInputStream(workspaceFile).use { MemoVault.load(it) }
        }.getOrNull() ?: Workspace()
    }

    private fun readRuns(): List<AgentRun> {
        if (!runsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(runsFile.readText())
            (0 until arr.length()).mapNotNull { AgentRun.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun readAudit(): List<AuditEntry> {
        if (!auditFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(auditFile.readText())
            (0 until arr.length()).mapNotNull { AuditEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeAudit(entries: List<AuditEntry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            auditFile.writeText(arr.toString())
        }
    }

    private companion object {
        val TERMINAL = setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)
    }
}
