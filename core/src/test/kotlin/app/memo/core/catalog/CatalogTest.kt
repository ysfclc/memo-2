package app.memo.core.catalog

import app.memo.core.platform.ModelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pins ARE the contract: model URLs point at fixed repository revisions
 * and carry the audited byte sizes and SHA-256 digests from threadline-vo
 * (copied verbatim). If any of these values changes, the change must go
 * through the audit trail — never a silent edit.
 */
class CatalogTest {

    @Test
    fun pinnedQwen3SizesAndHashesSurviveTheMigration() {
        val entries = ModelCatalog.entries
        assertTrue(entries.size >= 10)
        val byId = entries.associateBy { it.id }

        // Audited byte sizes and SHA-256 digests are unchanged; only the revision moved
        // from the old hand-pinned sha (e668717 / 87603f0) to the current API revision.
        val small = byId.getValue("qwen3-0.6b")
        assertEquals(344_671_744L, small.sizeBytes)
        assertEquals("03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856", small.sha256)
        assertEquals(ModelRole.ORCHESTRATOR, small.role)
        assertEquals(ModelFormat.LITERTLM, small.format)
        assertEquals(700, small.outputTokenBudget)
        assertEquals("fed81d17517e6ea64362e917ecf7e5accff12492", small.revision)
        assertEquals(
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/" +
                "fed81d17517e6ea64362e917ecf7e5accff12492/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            small.url,
        )
        assertTrue(small.isRunnableSize)

        val mid = byId.getValue("qwen3-1.7b")
        assertEquals(977_184_032L, mid.sizeBytes)
        assertEquals("2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8", mid.sha256)
        assertEquals(ModelRole.CHAT, mid.role)
        assertTrue(mid.url.contains("litert-community/Qwen3-1.7B/resolve/79bf0667b32ae025b972e5fe555fe72ca6e8816c/"))

        assertEquals(small, ModelCatalog.defaultEntry())
        assertEquals(ModelRole.ORCHESTRATOR, ModelCatalog.byRole(ModelRole.ORCHESTRATOR)?.role)
        assertNull(ModelCatalog.byId("does-not-exist"))
        assertFalse(ModelCatalog.isSha256Shape("not-a-hash"))
    }

    @Test
    fun providersByteForByteFromFieldwork() {
        val registry = ProviderRegistry
        assertEquals(8, registry.all.size)
        assertEquals(
            listOf("chatgpt", "claude", "gemini", "grok", "deepseek", "perplexity", "mistral", "copilot"),
            registry.all.map { it.id },
        )
        assertEquals("https://chatgpt.com/", registry.startUrlFor("chatgpt"))
        assertEquals("https://claude.ai/new", registry.startUrlFor("claude"))
        assertEquals("https://gemini.google.com/app", registry.startUrlFor("gemini"))
        assertEquals("https://grok.com/", registry.startUrlFor("grok"))
        assertEquals("https://chat.deepseek.com/", registry.startUrlFor("deepseek"))
        assertEquals("https://www.perplexity.ai/", registry.startUrlFor("perplexity"))
        assertEquals("https://chat.mistral.ai/chat", registry.startUrlFor("mistral"))
        assertEquals("https://copilot.microsoft.com/", registry.startUrlFor("copilot"))
        assertNull(registry.startUrlFor("aol-instant-messenger"))
    }

    @Test
    fun hostMatchingIsDomainFamilyAware() {
        assertTrue(ProviderRegistry.hostBelongsTo("chatgpt", "chatgpt.com"))
        assertTrue(ProviderRegistry.hostBelongsTo("chatgpt", "www.chatgpt.com"))
        assertTrue(ProviderRegistry.hostBelongsTo("chatgpt", "chat.openai.com"))
        assertTrue(ProviderRegistry.hostBelongsTo("deepseek", "chat.deepseek.com"))
        assertTrue(ProviderRegistry.hostBelongsTo("mistral", "chat.mistral.ai"))
        assertTrue(ProviderRegistry.hostBelongsTo("Grok", "WWW.grok.com"))
        // Look-alike hosts never pass.
        assertFalse(ProviderRegistry.hostBelongsTo("grok", "evil-grok.com"))
        assertFalse(ProviderRegistry.hostBelongsTo("grok", "grok.com.evil.example"))
        assertFalse(ProviderRegistry.hostBelongsTo("nope", "chatgpt.com"))
    }
}
