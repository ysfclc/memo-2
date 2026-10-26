package app.memo.runtime

import app.memo.core.audit.AuditSink
import app.memo.core.audit.HashChainAudit
import app.memo.core.codec.SynCodec
import app.memo.core.domain.AgentRun
import app.memo.core.domain.AuditEntry
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultName
import app.memo.core.registry.Workspace
import app.memo.vault.MemoVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * All durable app state (workspace, runs, audit) lives in the encrypted [Vault].
 * This class is the only place that encodes or decodes those entries, and it
 * also performs the one-time import of the legacy plaintext files.
 *
 * Blocking I/O: every method must run off the main thread (the runtime thread).
 *
 * Migration contract ([migrateLegacyPlaintext]):
 * 1. Runs once. The marker entry [MARKER] is written only after a successful import.
 * 2. All-or-nothing parse: if any legacy file fails to parse, nothing is written
 *    to the vault and nothing is deleted.
 * 3. Write to the vault, read back, and verify (workspace by SYN serialization,
 *    runs by ids, audit by hashes plus [HashChainAudit.verify]).
 * 4. Only after verification: overwrite each plaintext file with zeros, fsync,
 *    and delete it. The marker is written after the deletes.
 * 5. Any failure sets [migrationProblem] and keeps the plaintext. The caller then
 *    must not load or write vault state for this session.
 */
class Persistence(
    private val vault: Vault,
    private val legacyDir: File,
    private val log: (String) -> Unit = {},
) {

    /** Set when the legacy import failed or could not finish. Null when the vault is authoritative. */
    var migrationProblem: String? = null
        private set

    init {
        listOf(WORKSPACE, RUNS, AUDIT, MARKER).forEach { VaultName.validate(it) }
    }

    // ------------------------------------------------------------ vault I/O

    /** The stored workspace, or an empty workspace when none is stored. */
    fun loadWorkspace(): Workspace {
        val bytes = vault.read(WORKSPACE) ?: return Workspace()
        return MemoVault.load(ByteArrayInputStream(bytes))
            ?: throw IllegalStateException("stored workspace is not a valid SYN envelope")
    }

    fun saveWorkspace(workspace: Workspace) {
        vault.write(WORKSPACE, encodeWorkspace(workspace))
    }

    /** Stored runs; rows that do not parse are skipped, as in the previous build. */
    fun loadRuns(): List<AgentRun> {
        val bytes = vault.read(RUNS) ?: return emptyList()
        return decodeRunRows(String(bytes, Charsets.UTF_8)).filterNotNull()
    }

    fun saveRuns(runs: List<AgentRun>) {
        vault.write(RUNS, encodeRuns(runs))
    }

    /** Stored audit chain; rows that do not parse are skipped (verify() then reports the break). */
    fun loadAudit(): List<AuditEntry> {
        val bytes = vault.read(AUDIT) ?: return emptyList()
        return decodeAuditRows(String(bytes, Charsets.UTF_8)).filterNotNull()
    }

    fun saveAudit(entries: List<AuditEntry>) {
        vault.write(AUDIT, encodeAudit(entries))
    }

    // ------------------------------------------------------------ migration

    /**
     * Imports the legacy plaintext files once. Never throws: failures are
     * recorded in [migrationProblem] and logged. See the class KDoc for the contract.
     */
    fun migrateLegacyPlaintext() {
        migrationProblem = null
        try {
            if (vault.read(MARKER) != null) {
                // Migration already done. A leftover plaintext temp file is removed; it never blocks startup.
                runCatching { secureDeleteIfPresent(legacyWorkspaceTmp) }
                    .onFailure { log("could not remove leftover ${legacyWorkspaceTmp.name}: ${it.message}") }
                return
            }
            importLegacy()
        } catch (e: Exception) {
            fail("legacy import did not complete (${e.message ?: e.javaClass.simpleName}); plaintext kept", e)
        }
    }

    private fun importLegacy() {
        val workspaceSource = newestExisting(legacyWorkspace, legacyWorkspaceTmp)
        val runsSource = legacyRuns.takeIf { it.exists() }
        val auditSource = legacyAudit.takeIf { it.exists() }

        // Step 1: parse everything before writing anything.
        val workspace = workspaceSource?.let { parseLegacyWorkspace(it) }
        val runs = runsSource?.let { parseLegacyRuns(it) }
        val audit = auditSource?.let { parseLegacyAudit(it) }

        // Step 2: write each present entry to the vault.
        workspace?.let { vault.write(WORKSPACE, encodeWorkspace(it.workspace)) }
        runs?.let { vault.write(RUNS, encodeRuns(it)) }
        audit?.let { vault.write(AUDIT, encodeAudit(it)) }

        // Step 3: read back and verify before any plaintext is removed.
        workspace?.let { verifyWorkspace(it.workspace) }
        runs?.let { verifyRuns(it) }
        audit?.let { verifyAudit(it) }

        // Step 4: securely delete the plaintext.
        val removalFailures = mutableListOf<String>()
        listOf(legacyWorkspace, legacyWorkspaceTmp, legacyRuns, legacyAudit).forEach { file ->
            try {
                secureDeleteIfPresent(file)
            } catch (e: Exception) {
                removalFailures += "${file.name}: ${e.message}"
            }
        }

        // Step 5: marker, so the import never runs twice.
        vault.write(MARKER, markerBytes(workspace != null, runs?.size ?: 0, audit?.size ?: 0))

        if (removalFailures.isNotEmpty()) {
            fail("data imported and verified, but plaintext removal failed (${removalFailures.joinToString("; ")})", null)
        } else {
            log("legacy plaintext imported and removed (runs=${runs?.size ?: 0}, audit=${audit?.size ?: 0})")
        }
    }

    private fun fail(problem: String, cause: Throwable?) {
        migrationProblem = problem
        log("migration problem: $problem")
        if (cause != null) log("migration cause: ${cause.javaClass.simpleName}")
    }

    private data class ParsedWorkspace(val workspace: Workspace, val migrated: Boolean)

    private fun parseLegacyWorkspace(file: File): ParsedWorkspace {
        val text = file.readText(Charsets.UTF_8)
        return when (val outcome = SynCodec.parse(text)) {
            is SynCodec.ParseOutcome.Ok -> {
                if (outcome.migrated) log("legacy workspace v${outcome.fromVersion} migrated to current schema")
                ParsedWorkspace(outcome.workspace, outcome.migrated)
            }
            is SynCodec.ParseOutcome.Rejected ->
                throw IllegalStateException("${file.name} rejected: ${outcome.reason}")
        }
    }

    private fun parseLegacyRuns(file: File): List<AgentRun> {
        val rows = decodeRunRows(file.readText(Charsets.UTF_8))
        val bad = rows.indexOfFirst { it == null }
        require(bad < 0) { "${file.name} row $bad is not a valid run" }
        return rows.filterNotNull()
    }

    private fun parseLegacyAudit(file: File): List<AuditEntry> {
        val rows = decodeAuditRows(file.readText(Charsets.UTF_8))
        val bad = rows.indexOfFirst { it == null }
        require(bad < 0) { "${file.name} row $bad is not a valid audit entry" }
        return rows.filterNotNull()
    }

    private fun verifyWorkspace(expected: Workspace) {
        val back = loadWorkspace()
        // exportedAt is fixed to 0 on both sides so only the registry content is compared.
        val same = SynCodec.serialize(back, 0L) == SynCodec.serialize(expected, 0L)
        check(same) { "workspace read back from the vault differs from the import" }
    }

    private fun verifyRuns(expected: List<AgentRun>) {
        val back = loadRunsStrict()
        check(back.size == expected.size) { "runs count ${back.size} != ${expected.size} after write" }
        check(back.map { it.id } == expected.map { it.id }) { "run ids differ after write" }
    }

    private fun verifyAudit(expected: List<AuditEntry>) {
        val back = loadAudit()
        check(back.size == expected.size) { "audit count ${back.size} != ${expected.size} after write" }
        check(back.map { it.hash } == expected.map { it.hash }) { "audit hashes differ after write" }
        val chain = HashChainAudit(sink = AuditSink { _ -> }, restored = back).verify()
        check(chain.ok) { "audit chain does not verify after write: ${chain.reason}" }
    }

    private fun loadRunsStrict(): List<AgentRun> {
        val bytes = vault.read(RUNS) ?: return emptyList()
        return decodeRunRows(String(bytes, Charsets.UTF_8)).map {
            requireNotNull(it) { "run row failed to decode after write" }
        }
    }

    private fun newestExisting(vararg files: File): File? =
        files.filter { it.exists() }.maxByOrNull { it.lastModified() }

    /** Overwrites the file with zeros of the same length, fsyncs, then deletes it. */
    private fun secureDeleteIfPresent(file: File) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { raf ->
            val zeros = ByteArray(CHUNK)
            var remaining = raf.length()
            raf.seek(0)
            while (remaining > 0) {
                val n = minOf(remaining, CHUNK.toLong()).toInt()
                raf.write(zeros, 0, n)
                remaining -= n
            }
            raf.fd.sync()
        }
        check(file.delete()) { "could not delete ${file.name} after zeroing" }
    }

    private fun markerBytes(hasWorkspace: Boolean, runs: Int, audit: Int): ByteArray =
        JSONObject()
            .put("migration", "v1")
            .put("workspace", hasWorkspace)
            .put("runs", runs)
            .put("audit", audit)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private val legacyWorkspace = File(legacyDir, "workspace.syn.json")
    private val legacyWorkspaceTmp = File(legacyDir, "workspace.syn.json.tmp")
    private val legacyRuns = File(legacyDir, "runs.json")
    private val legacyAudit = File(legacyDir, "audit.json")

    companion object {
        /** Vault entry names. Each matches `^[a-z0-9_.-]{1,64}$`. */
        const val WORKSPACE = "workspace.syn"
        const val RUNS = "runs.json"
        const val AUDIT = "audit.json"
        const val MARKER = "migration.v1"

        private const val CHUNK = 8192

        fun encodeWorkspace(workspace: Workspace): ByteArray =
            ByteArrayOutputStream().also { MemoVault.save(workspace, it) }.toByteArray()

        fun encodeRuns(runs: List<AgentRun>): ByteArray {
            val arr = JSONArray()
            runs.forEach { arr.put(it.toJson()) }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        fun encodeAudit(entries: List<AuditEntry>): ByteArray {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        /** One entry per row; null where the row is not a valid run. Throws on malformed JSON. */
        internal fun decodeRunRows(text: String): List<AgentRun?> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i -> arr.optJSONObject(i)?.let { AgentRun.fromJson(it) } }
        }

        /** One entry per row; null where the row is not a valid audit entry. Throws on malformed JSON. */
        internal fun decodeAuditRows(text: String): List<AuditEntry?> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i -> arr.optJSONObject(i)?.let { AuditEntry.fromJson(it) } }
        }
    }
}
