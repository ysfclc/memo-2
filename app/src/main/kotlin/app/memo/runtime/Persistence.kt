package app.memo.runtime

import app.memo.core.audit.AuditSink
import app.memo.core.audit.HashChainAudit
import app.memo.core.codec.SynCodec
import app.memo.core.domain.AgentRun
import app.memo.core.domain.AuditEntry
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultName
import app.memo.core.registry.Workspace
import app.memo.platform.android.vault.KeystoreVault
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
 * 2. All-or-nothing parse: if the legacy workspace or any legacy run or audit row
 *    fails to parse, nothing is written to the vault and nothing is deleted.
 *    The legacy workspace is `workspace.syn.json`; `workspace.syn.json.tmp` is used
 *    only when the primary is missing or unparseable and the temp file parses.
 *    An unparseable temp file is ignored, logged and removed.
 * 3. Write to the vault, read back, and verify (workspace by SYN serialization,
 *    runs by ids, audit by hashes plus [HashChainAudit.verify]).
 * 4. Only after verification: overwrite each plaintext file with zeros, fsync,
 *    and delete it. The marker is written after the deletes.
 * 5. A failure before the marker sets [migrationProblem] and keeps the plaintext.
 *    The caller then must not load or write vault state for this session.
 * 6. When the marker already exists, leftover plaintext of all four legacy files
 *    is securely deleted and never imported. A failed deletion is reported in
 *    [migrationProblem] while [migrationFinished] stays true, so startup is not blocked.
 *
 * Unreadable rows: [loadRuns] and [loadAudit] throw when any stored row does not
 * parse, and record the entry in [unreadableEntries]. Writes to a recorded entry
 * are refused, so a reduced list is never saved over the stored content.
 */
class Persistence(
    private val vault: Vault,
    private val legacyDir: File,
    private val log: (String) -> Unit = {},
) {

    /**
     * Set when the legacy import failed, or when plaintext removal failed after the
     * import completed. Check [migrationFinished] to tell the two apart.
     */
    var migrationProblem: String? = null
        private set

    /** True once the vault holds the migrated data (marker written, or marker found at start). */
    var migrationFinished: Boolean = false
        private set

    /** Entries whose stored content could not be fully read this session, with the reason. */
    private val unreadable = linkedMapOf<String, String>()

    /**
     * Describes the most recent vault read that had to use a backup copy, or null.
     * A read from a backup can be a silent rollback, so the runtime treats such a
     * session as read-only until the user confirms.
     */
    val lastRecovery: String?
        get() = (vault as? KeystoreVault)?.lastRecovery

    init {
        listOf(WORKSPACE, RUNS, AUDIT, MARKER).forEach { VaultName.validate(it) }
    }

    /** Entries that could not be fully read this session, keyed by vault entry name. */
    fun unreadableEntries(): Map<String, String> = unreadable.toMap()

    // ------------------------------------------------------------ vault I/O

    /** The stored workspace, or an empty workspace when none is stored. */
    fun loadWorkspace(): Workspace {
        val bytes = vault.read(WORKSPACE) ?: return Workspace()
        return MemoVault.load(ByteArrayInputStream(bytes)) ?: run {
            val reason = "stored workspace is not a valid SYN envelope"
            unreadable[WORKSPACE] = reason
            throw IllegalStateException(reason)
        }
    }

    fun saveWorkspace(workspace: Workspace) {
        refuseIfUnreadable(WORKSPACE)
        vault.write(WORKSPACE, encodeWorkspace(workspace))
    }

    /**
     * Stored runs. Throws when any row does not parse: the entry is then recorded in
     * [unreadableEntries] and the stored bytes are left untouched.
     */
    fun loadRuns(): List<AgentRun> {
        val bytes = vault.read(RUNS) ?: return emptyList()
        val rows = decodeRunRows(String(bytes, Charsets.UTF_8))
        rejectUnreadableRows(RUNS, "run", rows)
        return rows.filterNotNull()
    }

    /** Writes the full list. Refused while [RUNS] is unreadable. */
    fun saveRuns(runs: List<AgentRun>) {
        refuseIfUnreadable(RUNS)
        vault.write(RUNS, encodeRuns(runs))
    }

    /**
     * Writes [current] with the run matching [run] replaced, or appended when absent.
     * Used by the engine checkpoint, which must persist the full list synchronously.
     */
    fun saveRunsWith(run: AgentRun, current: List<AgentRun>) {
        val merged = if (current.any { it.id == run.id }) {
            current.map { if (it.id == run.id) run else it }
        } else {
            current + run
        }
        saveRuns(merged)
    }

    /**
     * Stored audit chain. Throws when any row does not parse: the entry is then recorded in
     * [unreadableEntries] and the stored bytes are left untouched.
     */
    fun loadAudit(): List<AuditEntry> {
        val bytes = vault.read(AUDIT) ?: return emptyList()
        val rows = decodeAuditRows(String(bytes, Charsets.UTF_8))
        rejectUnreadableRows(AUDIT, "audit", rows)
        return rows.filterNotNull()
    }

    /** Writes the full chain. Refused while [AUDIT] is unreadable. */
    fun saveAudit(entries: List<AuditEntry>) {
        refuseIfUnreadable(AUDIT)
        vault.write(AUDIT, encodeAudit(entries))
    }

    private fun rejectUnreadableRows(name: String, noun: String, rows: List<Any?>) {
        val bad = rows.count { it == null }
        if (bad == 0) return
        val reason = "$bad of ${rows.size} stored $noun rows could not be read"
        unreadable[name] = reason
        throw IllegalStateException("$name: $reason")
    }

    private fun refuseIfUnreadable(name: String) {
        unreadable[name]?.let { throw IllegalStateException("refusing to overwrite $name: $it") }
    }

    // ------------------------------------------------------------ migration

    /**
     * Imports the legacy plaintext files once. Never throws: failures are
     * recorded in [migrationProblem] and logged. See the class KDoc for the contract.
     */
    fun migrateLegacyPlaintext() {
        migrationProblem = null
        migrationFinished = false
        try {
            if (vault.read(MARKER) != null) {
                // Migration already done. Leftover plaintext is removed, never imported.
                migrationFinished = true
                reportPlaintextRemoval(removePlaintext(legacyFiles()))
                return
            }
            importLegacy()
        } catch (e: Exception) {
            fail("legacy import did not complete (${e.message ?: e.javaClass.simpleName}); plaintext kept", e)
        }
    }

    private fun importLegacy() {
        val workspace = pickLegacyWorkspace()
        val runsSource = legacyRuns.takeIf { it.exists() }
        val auditSource = legacyAudit.takeIf { it.exists() }

        // Step 1: parse everything before writing anything.
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
        val removalFailures = removePlaintext(legacyFiles())

        // Step 5: marker, so the import never runs twice.
        vault.write(MARKER, markerBytes(workspace != null, runs?.size ?: 0, audit?.size ?: 0))
        migrationFinished = true

        if (removalFailures.isNotEmpty()) {
            reportPlaintextRemoval(removalFailures, "data imported and verified, but ")
        } else {
            log("legacy plaintext imported and removed (runs=${runs?.size ?: 0}, audit=${audit?.size ?: 0})")
        }
    }

    /**
     * Chooses the legacy workspace: the primary file when it parses, otherwise the
     * temp file when that parses. An unparseable temp file never fails the import.
     * When the primary exists but neither file parses, the import fails.
     */
    private fun pickLegacyWorkspace(): ParsedWorkspace? {
        val primary = legacyWorkspace.takeIf { it.exists() }
        val primaryResult = primary?.let { runCatching { parseLegacyWorkspace(it) } }
        primaryResult?.getOrNull()?.let { return it }

        if (legacyWorkspaceTmp.exists()) {
            val tmpResult = runCatching { parseLegacyWorkspace(legacyWorkspaceTmp) }
            tmpResult.getOrNull()?.let {
                log("using ${legacyWorkspaceTmp.name}: ${legacyWorkspace.name} is missing or unparseable")
                return it
            }
            log("ignoring unparseable ${legacyWorkspaceTmp.name}: ${tmpResult.exceptionOrNull()?.message}")
        }
        primaryResult?.exceptionOrNull()?.let { throw it }
        return null
    }

    /**
     * Securely deletes each file that exists and returns one message per failure.
     * Every file is attempted even when an earlier one fails.
     */
    private fun removePlaintext(files: List<File>): List<String> {
        val failures = mutableListOf<String>()
        files.forEach { file ->
            try {
                secureDeleteIfPresent(file)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return failures
    }

    /** Records a plaintext removal failure without blocking startup. The vault stays authoritative. */
    private fun reportPlaintextRemoval(failures: List<String>, prefix: String = "") {
        if (failures.isEmpty()) return
        migrationProblem = "${prefix}plaintext removal failed (${failures.joinToString("; ")})"
        log("migration problem: $migrationProblem")
    }

    private fun legacyFiles(): List<File> = listOf(legacyWorkspace, legacyWorkspaceTmp, legacyRuns, legacyAudit)

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
