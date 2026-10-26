package app.memo.core.codec

import app.memo.core.Limits
import app.memo.core.registry.Registry
import app.memo.core.registry.Workspace
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SYN envelope (Pocket law): strict validate -> migrate -> load.
 * A bad envelope is REJECTED as a whole (caller keeps its old copy) — the
 * codec never merges partial garbage against good data.Individual rows
 * inside a valid envelope may quarantine themselves (registry law).
 */
object SynCodec {

    const val FORMAT_ID: String = "SYN"

    /** Schema version of every registry in a v1 envelope (v1 predates per-registry schemas). */
    private const val V1_REGISTRY_SCHEMA: String = "1.0"

    /** Envelope key for quarantined rows; optional, so v2 files written before it existed still parse. */
    private const val QUARANTINE_KEY: String = "quarantine"

    /** Upper bound on registry keys in one envelope; leaves headroom over the 16 registries in use. */
    private const val MAX_REGISTRIES_PER_ENVELOPE: Int = 64

    sealed interface ParseOutcome {
        data class Ok(
            val workspace: Workspace,
            val fromVersion: Int,
            val migrated: Boolean,
        ) : ParseOutcome

        data class Rejected(val reason: String) : ParseOutcome
    }

    /** Migration chain: `fromVersion -> transformer`. Each step produces the next version. */
    private val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
        1 to { doc: JSONObject -> migrateV1ToV2(doc) },
    )

    fun parse(text: String): ParseOutcome {
        if (text.length > Limits.MAX_WORKSPACE_BYTES) {
            return ParseOutcome.Rejected("workspace larger than ${Limits.MAX_WORKSPACE_BYTES} bytes")
        }
        val doc = runCatching { JSONObject(text) }.fold(
            onSuccess = { it },
            onFailure = { return ParseOutcome.Rejected("invalid JSON: ${it.message}") },
        )
        if (doc.optString("format") != FORMAT_ID) {
            return ParseOutcome.Rejected("envelope format must be $FORMAT_ID")
        }
        val version = doc.optInt("schemaVersion", 0)
        if (version <= 0) {
            return ParseOutcome.Rejected("missing or invalid schemaVersion")
        }
        if (version > Limits.WORKSPACE_SCHEMA_VERSION) {
            return ParseOutcome.Rejected("workspace from newer version $version (this build: ${Limits.WORKSPACE_SCHEMA_VERSION})")
        }
        val rawRegistries = doc.optJSONObject("registries")
            ?: return ParseOutcome.Rejected("missing registries object")
        if (rawRegistries.length() > MAX_REGISTRIES_PER_ENVELOPE) {
            return ParseOutcome.Rejected("registries object suspiciously large")
        }
        var migrated = false
        var current = doc
        var v = version
        while (v < Limits.WORKSPACE_SCHEMA_VERSION) {
            val step = migrations[v]
                ?: return ParseOutcome.Rejected("no migration registered from v$v")
            current = step(current)
            v += 1
            migrated = true
        }
        val registries = current.optJSONObject("registries")
            ?: return ParseOutcome.Rejected("missing registries object")
        val workspace = Workspace()
        for (registry in workspace.all()) {
            registry.load(registries.optJSONArray(registry.kind))
        }
        restoreQuarantine(workspace, current.optJSONObject(QUARANTINE_KEY))
        return ParseOutcome.Ok(workspace, version, migrated)
    }

    fun serialize(workspace: Workspace, exportedAt: Long): String {
        val registries = JSONObject()
        val schemas = JSONObject()
        for (registry in workspace.all()) {
            registries.put(registry.kind, registry.toRows())
            schemas.put(registry.kind, registry.schemaVersion())
        }
        val out = JSONObject()
            .put("format", FORMAT_ID)
            .put("schemaVersion", Limits.WORKSPACE_SCHEMA_VERSION)
            .put("exportedAt", exportedAt)
            .put("schemas", schemas)
            .put("registries", registries)
        val quarantine = quarantineJson(workspace)
        if (quarantine.length() > 0) out.put(QUARANTINE_KEY, quarantine)
        val text = out.toString(2)
        if (text.length > Limits.MAX_WORKSPACE_BYTES) {
            throw IllegalStateException("serialized workspace exceeds the size cap")
        }
        return text
    }

    /**
     * Quarantined rows, keyed by registry kind. Each entry keeps the reason and the
     * original row text, so a bad row survives every save until the user releases it.
     * Kinds with no quarantined rows are omitted.
     */
    private fun quarantineJson(workspace: Workspace): JSONObject {
        val out = JSONObject()
        for (registry in workspace.all()) {
            val rows = registry.quarantinedRows()
            if (rows.isEmpty()) continue
            val arr = JSONArray()
            rows.forEach { arr.put(JSONObject().put("reason", it.reason).put("raw", it.raw)) }
            out.put(registry.kind, arr)
        }
        return out
    }

    /**
     * Re-adds quarantined rows from the envelope, unchanged. Entries without a
     * string `raw` are skipped; unknown registry kinds are ignored. Restored rows
     * are never promoted into the valid rows here.
     */
    private fun restoreQuarantine(workspace: Workspace, quarantine: JSONObject?) {
        if (quarantine == null) return
        for (registry in workspace.all()) {
            val arr = quarantine.optJSONArray(registry.kind) ?: continue
            val rows = mutableListOf<Registry.Quarantined>()
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                if (!entry.has("raw")) continue
                rows += Registry.Quarantined(
                    kind = registry.kind,
                    reason = entry.optString("reason", "restored from quarantine"),
                    raw = entry.optString("raw", ""),
                )
            }
            registry.restoreQuarantined(rows)
        }
    }

    /**
     * v1 -> v2. Keeps every v1 registry's rows exactly as stored, adds the
     * registries introduced in v2 as empty arrays, records the "1.0" schema
     * for every registry in a `schemas` map, and stamps schemaVersion 2.
     */
    private fun migrateV1ToV2(v1: JSONObject): JSONObject {
        val v1Registries = v1.optJSONObject("registries") ?: JSONObject()
        val registries = JSONObject()
        val schemas = JSONObject()
        for (registry in Workspace().all()) {
            registries.put(registry.kind, v1Registries.optJSONArray(registry.kind) ?: JSONArray())
            schemas.put(registry.kind, V1_REGISTRY_SCHEMA)
        }
        return v1
            .put("registries", registries)
            .put("schemas", schemas)
            .put("schemaVersion", 2)
    }
}
