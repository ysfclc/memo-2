package app.memo.core.catalog

import app.memo.core.platform.ModelFormat
import org.json.JSONArray
import org.json.JSONObject

/** One row of `GET /api/models?author=…` or `?search=…&filter=gguf`. */
data class HfListItem(val repo: String, val downloads: Long, val tags: List<String>)

/** The fields of `GET /api/models/{repo}` that pinning needs. */
data class HfModelInfo(val repo: String, val revision: String, val gated: Boolean, val license: String?)

/** One file from `GET /api/models/{repo}/tree/{sha}`. [sha256] is `lfs.oid`, null when absent or masked. */
data class HfFile(val path: String, val sizeBytes: Long, val sha256: String?)

/**
 * Parsers for the Hugging Face model API, plus the conversion of a repository file
 * into a pinned [ModelEntry]. Pure: no network, no Android.
 *
 * A file is only a candidate when the tree API gives its SHA-256 (`lfs.oid`). For
 * gated repositories the API masks that value without a token, so those files are
 * rejected here rather than pinned by guess.
 */
object HfParsers {

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SHA1 = Regex("^[0-9a-f]{40}$")
    private val REPO = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    private val PARAMS = Regex("(?<![A-Za-z0-9])(\\d+(?:\\.\\d+)?)[Bb](?![A-Za-z0-9])")
    private const val MIB = 1024L * 1024L

    private val FAMILIES = listOf(
        "deepseek" to "DeepSeek",
        "qwen" to "Qwen",
        "llama" to "Llama",
        "gemma" to "Gemma",
        "phi" to "Phi",
        "mistral" to "Mistral",
        "smollm" to "SmolLM",
    )

    /** Parses a list response. Entries without an `id` are skipped. */
    fun parseList(json: String): List<HfListItem> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val repo = obj.optString("id").ifEmpty { obj.optString("modelId") }
            if (!REPO.matches(repo)) return@mapNotNull null
            HfListItem(
                repo = repo,
                downloads = obj.optLong("downloads", 0L),
                tags = obj.optJSONArray("tags").strings(),
            )
        }
    }

    /**
     * Parses `GET /api/models/{repo}`. Throws [IllegalArgumentException] when the
     * response has no 40-hex `sha` (revision), because nothing can be pinned without one.
     */
    fun parseModelInfo(json: String): HfModelInfo {
        val obj = JSONObject(json)
        val repo = obj.optString("id").ifEmpty { obj.optString("modelId") }
        require(REPO.matches(repo)) { "model response has no valid repository id" }
        val revision = obj.optString("sha")
        require(SHA1.matches(revision)) { "model $repo has no 40-hex revision sha" }
        val tags = obj.optJSONArray("tags").strings()
        return HfModelInfo(
            repo = repo,
            revision = revision,
            gated = isGated(obj.opt("gated")),
            license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
        )
    }

    /** Parses `GET /api/models/{repo}/tree/{sha}` (directories are skipped). */
    fun parseTree(json: String): List<HfFile> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            if (obj.optString("type") != "file") return@mapNotNull null
            val path = obj.optString("path")
            if (path.isEmpty()) return@mapNotNull null
            val oid = obj.optJSONObject("lfs")?.optString("oid").orEmpty()
            HfFile(
                path = path,
                sizeBytes = obj.optLong("size", 0L),
                sha256 = oid.takeIf { SHA256.matches(it) },
            )
        }
    }

    /** Model container format from the file extension, or null for unsupported files. */
    fun formatOf(path: String): ModelFormat? = when {
        path.endsWith(".litertlm") -> ModelFormat.LITERTLM
        path.endsWith(".gguf") -> ModelFormat.GGUF
        else -> null
    }

    /** The pinned download URL: `https://huggingface.co/{repo}/resolve/{revision}/{path}`. */
    fun pinnedUrl(repo: String, revision: String, path: String): String =
        "https://huggingface.co/$repo/resolve/$revision/$path"

    /**
     * The pinned [ModelEntry] for one file, or null when the file cannot be pinned:
     * no SHA-256, an unsupported extension, a zero size, or an id that cannot be formed.
     */
    fun candidate(info: HfModelInfo, file: HfFile): ModelEntry? {
        val sha = file.sha256 ?: return null
        val format = formatOf(file.path) ?: return null
        if (file.sizeBytes <= 0L) return null
        val repoName = info.repo.substringAfter('/')
        val stem = file.path.substringAfterLast('/').substringBeforeLast('.')
        val id = slug("$repoName-$stem")
        if (id.isEmpty()) return null
        return ModelEntry(
            id = id,
            displayName = "$repoName · $stem",
            role = ModelRole.CHAT,
            format = format,
            repo = info.repo,
            revision = info.revision,
            file = file.path,
            url = pinnedUrl(info.repo, info.revision, file.path),
            sha256 = sha,
            sizeBytes = file.sizeBytes,
            family = familyOf(repoName),
            params = PARAMS.find(repoName)?.groupValues?.get(1)?.let { "${it}B" },
            quant = if (format == ModelFormat.GGUF) QuantRanking.quantOf(file.path) else null,
            license = info.license,
            gated = info.gated,
            minRamBytes = file.sizeBytes + file.sizeBytes / 4 + 512 * MIB,
            thinkingByDefault = false,
            outputTokenBudget = 0,
            description = "From Hugging Face ${info.repo}, pinned to revision ${info.revision.take(7)}.",
        )
    }

    /** Every pinnable file of one repository, in tree order. Files that fail [candidate] are left out. */
    fun candidates(info: HfModelInfo, files: List<HfFile>): List<ModelEntry> =
        files.mapNotNull { candidate(info, it) }

    /**
     * One entry per repository for GGUF: the file [QuantRanking] prefers. LiteRT-LM
     * repositories have no ranking, so this returns null for them.
     */
    fun bestGgufCandidate(info: HfModelInfo, files: List<HfFile>): ModelEntry? {
        val byPath = files.filter { it.sha256 != null }.associateBy { it.path }
        val best = QuantRanking.bestGguf(byPath.keys) ?: return null
        return byPath[best]?.let { candidate(info, it) }
    }

    /**
     * One entry per LiteRT-LM repository: the generic build. Vendor or platform
     * builds (NPU, Qualcomm, Intel, MediaTek, Google Tensor, web) are excluded; of the
     * rest, the file with the fewest underscores wins, then the shortest name, so
     * `Qwen3-0.6B.litertlm` beats `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`.
     * Null when no generic build exists.
     */
    fun bestLitertCandidate(info: HfModelInfo, files: List<HfFile>): ModelEntry? {
        val picked = files
            .filter { it.path.endsWith(".litertlm") && it.sha256 != null && !isPlatformBuild(it.path) }
            .minWithOrNull(compareBy<HfFile>({ it.path.count { c -> c == '_' } }, { it.path.length }, { it.path }))
        return picked?.let { candidate(info, it) }
    }

    private val PLATFORM_TOKENS = listOf(
        "npu", "qualcomm", "qcs", "intel", "mediatek", "mt69", "tensor", "web", "sm8", "-gpu", "_gpu",
    )

    private fun isPlatformBuild(path: String): Boolean {
        val lower = path.lowercase()
        return PLATFORM_TOKENS.any { lower.contains(it) }
    }

    private fun isGated(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        is String -> value.isNotBlank() && !value.equals("false", ignoreCase = true)
        else -> true
    }

    private fun familyOf(repoName: String): String {
        val lower = repoName.lowercase()
        return FAMILIES.firstOrNull { (key, _) -> lower.contains(key) }?.second ?: "Other"
    }

    private fun slug(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9.]+"), "-")
            .trim('-', '.')
            .take(64)
            .trimEnd('-', '.')

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
