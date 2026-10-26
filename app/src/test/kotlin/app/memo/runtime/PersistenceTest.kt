package app.memo.runtime

import app.memo.core.audit.HashChainAudit
import app.memo.core.audit.InMemoryAuditSink
import app.memo.core.codec.SynCodec
import app.memo.core.domain.AgentRun
import app.memo.core.domain.AuditEntry
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import app.memo.core.domain.Person
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.Task
import app.memo.core.domain.WorkflowSpec
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultException
import app.memo.core.platform.VaultName
import app.memo.core.registry.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("persistence-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun migratesV1PlaintextIntoVault() {
        writeLegacy(dir)
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        assertNotNull(vault.entries[Persistence.MARKER], "marker must be written")
        val stored = JSONObject(String(vault.entries.getValue(Persistence.WORKSPACE), Charsets.UTF_8))
        assertEquals(2, stored.getInt("schemaVersion"), "v1 workspace is stored as v2")

        assertEquals(listOf("Ada"), persistence.loadWorkspace().people.items().map { it.fullName })
        assertEquals(listOf("run-1"), persistence.loadRuns().map { it.id })
        val audit = persistence.loadAudit()
        assertEquals(2, audit.size)
        assertTrue(HashChainAudit(sink = InMemoryAuditSink(), restored = audit).verify().ok)

        assertFalse(File(dir, "workspace.syn.json").exists(), "plaintext workspace must be deleted")
        assertFalse(File(dir, "runs.json").exists(), "plaintext runs must be deleted")
        assertFalse(File(dir, "audit.json").exists(), "plaintext audit must be deleted")
    }

    @Test
    fun migrationRunsOnlyOnce() {
        writeLegacy(dir)
        val vault = MemoryVault()
        Persistence(vault, dir).migrateLegacyPlaintext()

        // A new plaintext file appearing after the import must not be imported or deleted.
        File(dir, "workspace.syn.json").writeText(v1Envelope(workspaceWith("Grace")))
        val second = Persistence(vault, dir)
        second.migrateLegacyPlaintext()

        assertNull(second.migrationProblem)
        assertEquals(listOf("Ada"), second.loadWorkspace().people.items().map { it.fullName })
        assertTrue(File(dir, "workspace.syn.json").exists())
    }

    @Test
    fun corruptRunsKeepsPlaintextAndReportsProblem() {
        val truncatedRuns = "[{\"id\":"
        writeLegacy(dir, runsText = truncatedRuns)
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNotNull(persistence.migrationProblem)
        assertEquals(truncatedRuns, File(dir, "runs.json").readText())
        assertTrue(File(dir, "workspace.syn.json").exists())
        assertTrue(File(dir, "audit.json").exists())
        assertNull(vault.entries[Persistence.WORKSPACE], "nothing is written when any file fails to parse")
        assertNull(vault.entries[Persistence.MARKER], "no marker, so the import is retried")
    }

    @Test
    fun failedVaultWriteKeepsPlaintext() {
        writeLegacy(dir)
        val persistence = Persistence(FailingVault(), dir)

        persistence.migrateLegacyPlaintext()

        assertNotNull(persistence.migrationProblem)
        assertTrue(File(dir, "workspace.syn.json").exists())
        assertTrue(File(dir, "runs.json").exists())
        assertTrue(File(dir, "audit.json").exists())
    }

    @Test
    fun saveAndLoadRoundTripForAllThree() {
        val vault = MemoryVault()
        val writer = Persistence(vault, dir)
        writer.saveWorkspace(workspaceWith("Ada"))
        writer.saveRuns(listOf(runFixture("run-1")))
        writer.saveAudit(auditFixture())

        val reader = Persistence(vault, dir)
        val ws = reader.loadWorkspace()
        assertEquals(listOf("Ada"), ws.people.items().map { it.fullName })
        assertEquals(1, ws.tasks.size)
        assertEquals(listOf("run-1"), reader.loadRuns().map { it.id })
        val audit = reader.loadAudit()
        assertEquals(auditFixture().map { it.hash }, audit.map { it.hash })
        assertTrue(HashChainAudit(sink = InMemoryAuditSink(), restored = audit).verify().ok)
    }

    @Test
    fun emptyVaultLoadsEmptyWorkspace() {
        val persistence = Persistence(MemoryVault(), dir)
        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        val ws = persistence.loadWorkspace()
        assertEquals(0, ws.people.size)
        assertEquals(0, ws.tasks.size)
        assertTrue(persistence.loadRuns().isEmpty())
        assertTrue(persistence.loadAudit().isEmpty())
    }

    // ------------------------------------------------------------ fixtures

    /** In-memory [Vault] with the same name rules as the real one. */
    private class MemoryVault : Vault {
        val entries = linkedMapOf<String, ByteArray>()

        override fun read(name: String): ByteArray? {
            VaultName.validate(name)
            return entries[name]?.copyOf()
        }

        override fun write(name: String, bytes: ByteArray) {
            VaultName.validate(name)
            entries[name] = bytes.copyOf()
        }

        override fun delete(name: String) {
            VaultName.validate(name)
            entries.remove(name)
        }

        override fun list(prefix: String): List<String> =
            entries.keys.filter { it.startsWith(prefix) }.sorted()
    }

    private class FailingVault : Vault {
        override fun read(name: String): ByteArray? = null
        override fun write(name: String, bytes: ByteArray) {
            throw VaultException("disk full")
        }
        override fun delete(name: String) = Unit
        override fun list(prefix: String): List<String> = emptyList()
    }

    private fun workspaceWith(personName: String): Workspace {
        val ws = Workspace()
        ws.people.upsert(Person(fullName = personName, phone = "+1 555 0100"), NOW)
        ws.tasks.upsert(Task(title = "Call $personName", dueAt = NOW + 1_000L), NOW)
        return ws
    }

    /** The envelope the previous build wrote: schemaVersion 1, no schemas map, no v2 registries. */
    private fun v1Envelope(ws: Workspace): String {
        val doc = JSONObject(SynCodec.serialize(ws, NOW))
        doc.put("schemaVersion", 1)
        doc.remove("schemas")
        val registries = doc.getJSONObject("registries")
        V2_REGISTRIES.forEach { registries.remove(it) }
        return doc.toString(2)
    }

    private fun runFixture(id: String): AgentRun {
        val spec = WorkflowSpec(
            id = "plan-$id",
            title = "Draft a note",
            createdAt = NOW,
            steps = listOf(StepSpec(id = "draft", kind = StepKind.REASON, title = "Draft", prompt = "Write a short note")),
        )
        return AgentRun(
            id = id,
            spec = spec,
            status = RunStatus.COMPLETED,
            createdAt = NOW,
            steps = spec.steps.map { StepState(spec = it) },
        )
    }

    private fun auditFixture(): List<AuditEntry> {
        val chain = HashChainAudit(sink = InMemoryAuditSink())
        chain.append(
            OutboundEvent(
                runId = "run-1",
                stepId = "send",
                channel = OutboundChannel.API_OPTIN,
                target = "api.example.com",
                action = "completed: Send",
                contentHash = "hash-1",
            ),
            NOW,
        )
        chain.append(
            OutboundEvent(
                runId = "run-1",
                stepId = "post",
                channel = OutboundChannel.API_OPTIN,
                target = "api.example.com",
                action = "completed: Post",
                contentHash = "hash-2",
            ),
            NOW + 1L,
        )
        return chain.entries()
    }

    private fun writeLegacy(target: File, runsText: String? = null) {
        File(target, "workspace.syn.json").writeText(v1Envelope(workspaceWith("Ada")))
        val runs = runsText ?: JSONArray().apply { put(runFixture("run-1").toJson()) }.toString()
        File(target, "runs.json").writeText(runs)
        val audit = JSONArray().apply { auditFixture().forEach { put(it.toJson()) } }
        File(target, "audit.json").writeText(audit.toString())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        val V2_REGISTRIES = listOf(
            "projects", "notes", "workflow_templates", "prompt_templates",
            "principles", "content_preferences", "channel_accounts", "provider_adapters",
        )
    }
}
