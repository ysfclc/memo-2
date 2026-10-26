package app.memo.core.catalog

/**
 * Frontier providers, ALWAYS reached through their real websites inside
 * Memo's hardened WebView (browser-first law D-006). Verified against the
 * Fieldwork 3.1 source registry byte-for-byte (hosts + start URLs), 2026-10.
 */
data class FrontierProvider(
    val id: String,
    val displayName: String,
    val startUrl: String,
    val hosts: Set<String>,
)

object ProviderRegistry {

    val all: List<FrontierProvider> = listOf(
        FrontierProvider("chatgpt", "ChatGPT", "https://chatgpt.com/", setOf("chatgpt.com", "chat.openai.com")),
        FrontierProvider("claude", "Claude", "https://claude.ai/new", setOf("claude.ai")),
        FrontierProvider("gemini", "Gemini", "https://gemini.google.com/app", setOf("gemini.google.com")),
        FrontierProvider("grok", "Grok", "https://grok.com/", setOf("grok.com")),
        FrontierProvider("deepseek", "DeepSeek", "https://chat.deepseek.com/", setOf("deepseek.com", "chat.deepseek.com")),
        FrontierProvider("perplexity", "Perplexity", "https://www.perplexity.ai/", setOf("perplexity.ai")),
        FrontierProvider("mistral", "Le Chat", "https://chat.mistral.ai/chat", setOf("mistral.ai", "chat.mistral.ai")),
        FrontierProvider("copilot", "Copilot", "https://copilot.microsoft.com/", setOf("copilot.microsoft.com")),
    )

    private val byId: Map<String, FrontierProvider> = all.associateBy { it.id }

    fun byId(id: String): FrontierProvider? = byId[id]

    fun knownIds(): Set<String> = byId.keys

    fun startUrlFor(id: String): String? = byId[id]?.startUrl

    /** True when a host belongs to this provider's domain family (www./subdomain tolerant). */
    fun hostBelongsTo(providerId: String, host: String): Boolean {
        val provider = byId[providerId] ?: return false
        val hostLower = host.lowercase().trim().removePrefix("www.")
        return provider.hosts.any { hostLower == it.lowercase() ||
            hostLower.endsWith("." + it.lowercase()) }
    }
}
