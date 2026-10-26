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
import kotlin.test.assertFailsWith
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

        // A plaintext file reappearing after the import is never imported; it is securely deleted.
        File(dir, "workspace.syn.json").writeText(v1Envelope(workspaceWith("Grace")))
        val second = Persistence(vault, dir)
        second.migrateLegacyPlaintext()

        assertNull(second.migrationProblem)
        assertEquals(listOf("Ada"), second.loadWorkspace().people.items().map { it.fullName })
        assertFalse(File(dir, "workspace.syn.json").exists(), "leftover plaintext is removed after the marker")
    }

    @Test
    fun newerUnparseableTmpDoesNotBlockGoodPrimary() {
        writeLegacy(dir)
        val tmp = File(dir, "workspace.syn.json.tmp")
        tmp.writeText("{ half-written")
        tmp.setLastModified(System.currentTimeMillis() + 60_000L)
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        assertTrue(persistence.migrationFinished)
        assertEquals(listOf("Ada"), persistence.loadWorkspace().people.items().map { it.fullName })
        assertFalse(tmp.exists(), "the unparseable temp file is removed")
    }

    @Test
    fun newerParseableTmpWins() {
        writeLegacy(dir) // primary holds "Ada"
        val tmp = File(dir, "workspace.syn.json.tmp")
        tmp.writeText(v1Envelope(workspaceWith("Grace")))
        tmp.setLastModified(System.currentTimeMillis() + 60_000L)
        val persistence = Persistence(MemoryVault(), dir)

        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        assertEquals(listOf("Grace"), persistence.loadWorkspace().people.items().map { it.fullName })
    }

    @Test
    fun olderParseableTmpLosesToPrimary() {
        writeLegacy(dir) // primary holds "Ada"
        val tmp = File(dir, "workspace.syn.json.tmp")
        tmp.writeText(v1Envelope(workspaceWith("Grace")))
        tmp.setLastModified(System.currentTimeMillis() - 60_000L)
        val persistence = Persistence(MemoryVault(), dir)

        persistence.migrateLegacyPlaintext()

        assertEquals(listOf("Ada"), persistence.loadWorkspace().people.items().map { it.fullName })
    }

    @Test
    fun missingPrimaryImportsGoodTmp() {
        writeLegacy(dir)
        File(dir, "workspace.syn.json").delete()
        File(dir, "workspace.syn.json.tmp").writeText(v1Envelope(workspaceWith("Grace")))
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        assertEquals(listOf("Grace"), persistence.loadWorkspace().people.items().map { it.fullName })
        assertFalse(File(dir, "workspace.syn.json.tmp").exists())
    }

    @Test
    fun unparseablePrimaryAndTmpKeepsPlaintextAndFailsImport() {
        writeLegacy(dir)
        File(dir, "workspace.syn.json").writeText("{ broken")
        File(dir, "workspace.syn.json.tmp").writeText("{ also broken")
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNotNull(persistence.migrationProblem)
        assertFalse(persistence.migrationFinished)
        assertNull(vault.entries[Persistence.MARKER])
        assertNull(vault.entries[Persistence.WORKSPACE])
        assertTrue(File(dir, "workspace.syn.json").exists())
        assertTrue(File(dir, "workspace.syn.json.tmp").exists())
    }

    @Test
    fun markerPresentRemovesAllFourPlaintextFilesWithoutImporting() {
        val vault = MemoryVault()
        vault.write(Persistence.MARKER, "{\"migration\":\"v1\"}".toByteArray())
        writeLegacy(dir)
        File(dir, "workspace.syn.json.tmp").writeText(v1Envelope(workspaceWith("Grace")))
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertNull(persistence.migrationProblem)
        assertTrue(persistence.migrationFinished)
        assertNull(vault.entries[Persistence.WORKSPACE], "plaintext is never imported after the marker")
        listOf("workspace.syn.json", "workspace.syn.json.tmp", "runs.json", "audit.json").forEach {
            assertFalse(File(dir, it).exists(), "$it must be removed")
        }
    }

    @Test
    fun markerPresentReportsFailedRemovalWithoutBlocking() {
        val vault = MemoryVault()
        vault.write(Persistence.MARKER, "{\"migration\":\"v1\"}".toByteArray())
        writeLegacy(dir)
        // A directory where a plaintext file is expected cannot be zeroed, so its removal fails.
        File(dir, "audit.json").delete()
        File(dir, "audit.json").mkdir()
        val persistence = Persistence(vault, dir)

        persistence.migrateLegacyPlaintext()

        assertTrue(persistence.migrationFinished, "startup is not blocked by a cleanup failure")
        val problem = assertNotNull(persistence.migrationProblem)
        assertTrue(problem.contains("audit.json"), problem)
        assertFalse(File(dir, "workspace.syn.json").exists())
        assertFalse(File(dir, "runs.json").exists())
    }

    @Test
    fun corruptRunRowIsReportedAndNeverOverwritten() {
        val vault = MemoryVault()
        val stored = JSONArray().apply {
            put(runFixture("run-1").toJson())
            put(JSONObject().put("id", "broken-row"))
        }.toString().toByteArray(Charsets.UTF_8)
        vault.write(Persistence.RUNS, stored)
        val persistence = Persistence(vault, dir)

        assertFailsWith<IllegalStateException> { persistence.loadRuns() }
        assertNotNull(persistence.unreadableEntries()[Persistence.RUNS])

        assertFailsWith<IllegalStateException> { persistence.saveRuns(listOf(runFixture("run-2"))) }
        assertFailsWith<IllegalStateException> { persistence.saveRunsWith(runFixture("run-2"), emptyList()) }
        assertTrue(stored.contentEquals(vault.entries.getValue(Persistence.RUNS)), "stored rows are untouched")
    }

    @Test
    fun corruptAuditRowIsReportedAndNeverOverwritten() {
        val vault = MemoryVault()
        val stored = JSONArray().apply {
            auditFixture().forEach { put(it.toJson()) }
            put("not an object")
        }.toString().toByteArray(Charsets.UTF_8)
        vault.write(Persistence.AUDIT, stored)
        val persistence = Persistence(vault, dir)

        assertFailsWith<IllegalStateException> { persistence.loadAudit() }
        assertFailsWith<IllegalStateException> { persistence.saveAudit(auditFixture()) }
        assertTrue(stored.contentEquals(vault.entries.getValue(Persistence.AUDIT)))
    }

    @Test
    fun saveRunsWithReplacesTheRunInPlace() {
        val vault = MemoryVault()
        val persistence = Persistence(vault, dir)
        val first = runFixture("run-1")
        val second = runFixture("run-2")
        persistence.saveRuns(listOf(first, second))

        persistence.saveRunsWith(second.copy(status = RunStatus.CANCELLED), listOf(first, second))

        val back = Persistence(vault, dir).loadRuns()
        assertEquals(listOf("run-1", "run-2"), back.map { it.id })
        assertEquals(RunStatus.CANCELLED, back[1].status)
        assertEquals(RunStatus.COMPLETED, back[0].status)
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
