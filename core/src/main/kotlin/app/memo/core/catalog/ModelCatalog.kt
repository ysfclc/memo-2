package app.memo.core.catalog

/**
 * Pinned, SHA-256-verified local model catalog (threadline-vo audit
 * constants, copied verbatim 2026-10; sizes in bytes; URLs carry pinned
 * repository revisions — never floating heads). All inference is on-device.
 */
enum class ModelRole { ORCHESTRATOR, CHAT, DEEP }

data class ModelEntry(
    val id: String,
    val displayName: String,
    val role: ModelRole,
    val repo: String,
    val file: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val ramPreflightBytes: Long,     // 0 = no memory preflight beyond storage
    val thinkingByDefault: Boolean,
    val outputTokenBudget: Int,      // 0 = engine default
    val description: String,
) {
    val isRunnableSize: Boolean
        get() = sizeBytes >= app.memo.core.Limits.MIN_MODEL_BYTES || sizeBytes == 0L
}

object ModelCatalog {

    private const val QWEN3_06B_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e668717/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true"

    private const val QWEN3_17B_URL =
        "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/87603f0/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true"

    private const val QWEN3_4B_URL =
        "https://huggingface.co/litert-community/Qwen3-4B-Thinking-2507/resolve/f8ce479/model.litertlm?download=true"

    val entries: List<ModelEntry> = listOf(
        ModelEntry(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B · dynamic wi4b32",
            role = ModelRole.ORCHESTRATOR,
            repo = "litert-community/Qwen3-0.6B",
            file = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            url = QWEN3_06B_URL,
            sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
            sizeBytes = 344_671_744L,
            ramPreflightBytes = 0L,
            thinkingByDefault = false,
            outputTokenBudget = 700,
            description = "Default orchestrator: smallest pinned Qwen3; thinking off for routine steps.",
        ),
        ModelEntry(
            id = "qwen3-1.7b",
            displayName = "Qwen3 1.7B · dynamic wi4b32",
            role = ModelRole.CHAT,
            repo = "litert-community/Qwen3-1.7B",
            file = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            url = QWEN3_17B_URL,
            sha256 = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
            sizeBytes = 977_184_032L,
            ramPreflightBytes = 2_400_000_000L,
            thinkingByDefault = false,
            outputTokenBudget = 1_200,
            description = "Default voice/chat companion size when the user wants a noticeably better answerer.",
        ),
        ModelEntry(
            id = "qwen3-4b-thinking",
            displayName = "Qwen3 4B Thinking 2507",
            role = ModelRole.DEEP,
            repo = "litert-community/Qwen3-4B-Thinking-2507",
            file = "model.litertlm",
            url = QWEN3_4B_URL,
            sha256 = "8a8a6a419a5570b3f4814d358fb795c0dcb6931206c7dae29480f47564c3f04d",
            sizeBytes = 2_477_192_112L,
            ramPreflightBytes = 4_300_000_000L,
            thinkingByDefault = true,
            outputTokenBudget = 2_048,
            description = "Deep planner: thinking budget bounded; CPU fallback only near 4.3 GB free RAM.",
        ),
    )

    private val byId = entries.associateBy { it.id }

    fun byId(id: String): ModelEntry? = byId[id]

    /** Default profile — the 0.6B orchestrator (fast-first law). */
    fun defaultEntry(): ModelEntry = entries.first { it.role == ModelRole.ORCHESTRATOR }

    fun byRole(role: ModelRole): ModelEntry? = entries.firstOrNull { it.role == role }

    fun isSha256Shape(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }
}
