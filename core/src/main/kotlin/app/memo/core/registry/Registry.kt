package app.memo.core.registry

import app.memo.core.Limits
import app.memo.core.codec.CanonicalJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scheme for one registry kind. Decoders return null for structurally bad
 * rows instead of throwing, which lets the registry quarantine them.
 */
interface RegScheme<T> {
    /** Schema major of rows written by this scheme; default 1, overridable. */
    val schemaMajor: Int get() = 1

    /** Schema minor of rows written by this scheme; default 0, overridable. */
    val schemaMinor: Int get() = 0

    /** Natural-key id; blank means "cannot be keyed" (quarantined by the registry). */
    fun id(item: T): String

    fun toJson(item: T): JSONObject

    fun fromJson(raw: JSONObject): T?

    /**
     * Attach bookkeeping: new records get version 1 and both stamps `now`;
     * bumps keep the original `createdAt`, refresh `updatedAt`, and take the
     * next version. Implementations use the domain data class `copy`.
     */
    fun stamped(item: T, existing: T?, now: Long, nextVersion: Int): T

    /** The version label carried by [item]; the registry bumps by exactly one. */
    fun versionOf(item: T): Int

    /**
     * Canonical business-content signature (timestamps and version stripped).
     * Two items with equal signatures are the SAME content — reuse, no bump.
     */
    fun contentSignature(item: T): String {
        val o = toJson(item)
        for (key in arrayOf("createdAt", "updatedAt", "version")) o.remove(key)
        return CanonicalJson.canonical(o)
    }
}

/**
 * One registry instance. All mutations go through [upsert] — there is no
 * destructive override, and nothing silently disappears: bad rows land in
 * the quarantine list with their raw text attached.
 */
class Registry<T>(val kind: String, val scheme: RegScheme<T>) {

    sealed interface Put<T> {
        /** First record under this natural key. */
        data class Created<T>(val item: T) : Put<T>

        /** Natural key existed with IDENTICAL content — reuse, no version bump. */
        data class Reused<T>(val existing: T) : Put<T>

        /** Natural key existed with changed content — bumped by exactly one version. */
        data class Bumped<T>(val current: T) : Put<T>

        /** Rejected: reason explains why; nothing changed. */
        data class Failed<T>(val reason: String) : Put<T>
    }

    data class Quarantined(val kind: String, val reason: String, val raw: String)

    private val byId = LinkedHashMap<String, T>()
    private val quarantined = mutableListOf<Quarantined>()

    val size: Int get() = byId.size

    fun items(): List<T> = byId.values.toList()

    fun byId(id: String): T? = byId[id]

    fun quarantinedRows(): List<Quarantined> = quarantined.toList()

    fun findFirst(predicate: (T) -> Boolean): T? = byId.values.firstOrNull(predicate)

    fun filter(predicate: (T) -> Boolean): List<T> = byId.values.filter(predicate)

    fun upsert(item: T, now: Long): Put<T> {
        val id = scheme.id(item)
        if (id.isBlank()) {
            quarantined += Quarantined(kind, "blank natural key", scheme.toJson(item).toString())
            return Put.Failed("blank natural key")
        }
        if (byId.size >= Limits.MAX_ENTRIES_PER_REGISTRY && id !in byId) {
            return Put.Failed("registry full (${Limits.MAX_ENTRIES_PER_REGISTRY} rows)")
        }
        val existing = byId[id]
        if (existing == null) {
            val fresh = scheme.stamped(item, null, now, 1)
            byId[id] = fresh
            return Put.Created(fresh)
        }
        return if (scheme.contentSignature(item) == scheme.contentSignature(existing)) {
            Put.Reused(existing)
        } else {
            val bumped = scheme.stamped(item, existing, now, scheme.versionOf(existing) + 1)
            byId[id] = bumped
            Put.Bumped(bumped)
        }
    }

    /** Load persisted rows. Duplicates with equal signatures dedupe silently; conflicts quarantine. */
    fun load(rows: JSONArray?) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val raw = rows.optJSONObject(i)
            if (raw == null) {
                quarantined += Quarantined(kind, "row $i not an object", rows.optString(i))
                continue
            }
            val decoded = scheme.fromJson(raw)
            if (decoded == null) {
                quarantined += Quarantined(kind, "row $i structurally invalid", raw.toString())
                continue
            }
            val id = scheme.id(decoded)
            if (id.isBlank()) {
                quarantined += Quarantined(kind, "row $i blank natural key", raw.toString())
                continue
            }
            val existing = byId[id]
            if (existing == null) {
                byId[id] = decoded
            } else if (scheme.contentSignature(decoded) != scheme.contentSignature(existing)) {
                quarantined += Quarantined(
                    kind,
                    "row $i conflicts with already-loaded row $id",
                    raw.toString(),
                )
            }
        }
    }

    fun toRows(): JSONArray {
        val arr = JSONArray()
        byId.values.forEach { arr.put(scheme.toJson(it)) }
        return arr
    }

    fun freeCapacity(): Int = (Limits.MAX_ENTRIES_PER_REGISTRY - byId.size).coerceAtLeast(0)

    /** Semantic version of [item]: the scheme's schema major.minor plus the row's patch revision. */
    fun semVerOf(item: T): SemVer =
        SemVer(scheme.schemaMajor, scheme.schemaMinor, scheme.versionOf(item))

    /** Schema version of this registry's rows, as "major.minor". */
    fun schemaVersion(): String = "${scheme.schemaMajor}.${scheme.schemaMinor}"
}
