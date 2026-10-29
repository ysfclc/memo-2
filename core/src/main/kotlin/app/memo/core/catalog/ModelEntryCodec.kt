package app.memo.core.catalog

import app.memo.core.platform.ModelFormat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Strict versioned codec for public, hash-pinned entries found by live model discovery. */
object ModelEntryCodec {
    const val VERSION: Int = 1
    const val MAX_ENTRIES: Int = 500
    const val MAX_TEXT_CHARS: Int = 1_000_000

    private val ENVELOPE_KEYS = setOf("v", "entries")
    private val ENTRY_KEYS = setOf(
        "id", "displayName", "role", "format", "repo", "revision", "file", "url", "sha256",
        "sizeBytes", "family", "params", "quant", "license", "gated", "minRamBytes",
        "thinkingByDefault", "outputTokenBudget", "description",
    )

    fun encode(entries: List<ModelEntry>): String {
        require(entries.size <= MAX_ENTRIES) { "too many model entries" }
        require(entries.map { it.id }.toSet().size == entries.size) { "duplicate model ids" }
        val array = JSONArray()
        entries.forEach { array.put(encodeEntry(it)) }
        return JSONObject().put("v", VERSION).put("entries", array).toString()
    }

    /** Rejects unknown fields, invalid pins, duplicate ids, and oversized records. */
    fun decode(text: String): List<ModelEntry> {
        if (text.length > MAX_TEXT_CHARS) throw ModelEntryCodecException("model list is too large")
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw ModelEntryCodecException("model list is not valid JSON")
        }
        if (root.keys().asSequence().toSet() != ENVELOPE_KEYS) throw ModelEntryCodecException("model list fields are invalid")
        if (readInt(root, "v") != VERSION) throw ModelEntryCodecException("model list version is unsupported")
        val array = root.get("entries") as? JSONArray ?: throw ModelEntryCodecException("entries must be an array")
        if (array.length() > MAX_ENTRIES) throw ModelEntryCodecException("too many model entries")
        val entries = (0 until array.length()).map { index ->
            val item = array.get(index) as? JSONObject ?: throw ModelEntryCodecException("entry must be an object")
            decodeEntry(item)
        }
        if (entries.map { it.id }.toSet().size != entries.size) throw ModelEntryCodecException("duplicate model ids")
        return entries
    }

    private fun encodeEntry(entry: ModelEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("displayName", entry.displayName)
        .put("role", entry.role.name)
        .put("format", entry.format.name)
        .put("repo", entry.repo)
        .put("revision", entry.revision)
        .put("file", entry.file)
        .put("url", entry.url)
        .put("sha256", entry.sha256)
        .put("sizeBytes", entry.sizeBytes)
        .put("family", entry.family)
        .put("params", entry.params ?: JSONObject.NULL)
        .put("quant", entry.quant ?: JSONObject.NULL)
        .put("license", entry.license ?: JSONObject.NULL)
        .put("gated", entry.gated)
        .put("minRamBytes", entry.minRamBytes)
        .put("thinkingByDefault", entry.thinkingByDefault)
        .put("outputTokenBudget", entry.outputTokenBudget)
        .put("description", entry.description)

    private fun decodeEntry(item: JSONObject): ModelEntry {
        if (item.keys().asSequence().toSet() != ENTRY_KEYS) throw ModelEntryCodecException("entry fields are invalid")
        return try {
            ModelEntry(
                id = readString(item, "id"),
                displayName = readString(item, "displayName"),
                role = ModelRole.valueOf(readString(item, "role")),
                format = ModelFormat.valueOf(readString(item, "format")),
                repo = readString(item, "repo"),
                revision = readString(item, "revision"),
                file = readString(item, "file"),
                url = readString(item, "url"),
                sha256 = readString(item, "sha256"),
                sizeBytes = readLong(item, "sizeBytes"),
                family = readString(item, "family"),
                params = readNullableString(item, "params"),
                quant = readNullableString(item, "quant"),
                license = readNullableString(item, "license"),
                gated = readBoolean(item, "gated"),
                minRamBytes = readLong(item, "minRamBytes"),
                thinkingByDefault = readBoolean(item, "thinkingByDefault"),
                outputTokenBudget = readInt(item, "outputTokenBudget"),
                description = readString(item, "description"),
            )
        } catch (_: IllegalArgumentException) {
            throw ModelEntryCodecException("entry values are invalid")
        }
    }

    private fun readString(item: JSONObject, key: String): String =
        item.get(key) as? String ?: throw ModelEntryCodecException("$key must be text")

    private fun readNullableString(item: JSONObject, key: String): String? =
        if (item.isNull(key)) null else readString(item, key)

    private fun readBoolean(item: JSONObject, key: String): Boolean =
        item.get(key) as? Boolean ?: throw ModelEntryCodecException("$key must be a boolean")

    private fun readLong(item: JSONObject, key: String): Long {
        val number = item.get(key) as? Number ?: throw ModelEntryCodecException("$key must be an integer")
        val value = number.toLong()
        if (number.toDouble() != value.toDouble()) throw ModelEntryCodecException("$key must be an integer")
        return value
    }

    private fun readInt(item: JSONObject, key: String): Int {
        val value = readLong(item, key)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw ModelEntryCodecException("$key is out of range")
        return value.toInt()
    }
}

/** Fixed, content-free reason for rejected model metadata. */
class ModelEntryCodecException(message: String) : RuntimeException(message)
