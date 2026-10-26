package app.memo.core.catalog

import app.memo.core.Limits
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
    fun threePinnedModelsWithVerbatimSizesAndHashes() {
        val entries = ModelCatalog.entries
        assertEquals(3, entries.size)
        val byId = entries.associateBy { it.id }

        val small = byId.getValue("qwen3-0.6b")
        assertEquals(344_671_744L, small.sizeBytes)
        assertEquals("03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856", small.sha256)
        assertEquals(ModelRole.ORCHESTRATOR, small.role)
        assertEquals(false, small.thinkingByDefault)
        assertEquals(700, small.outputTokenBudget)
        assertTrue(small.url.contains("litert-community/Qwen3-0.6B/resolve/e668717/"))
        assertTrue(small.url.endsWith("?download=true"))
        assertTrue(small.isRunnableSize)

        val mid = byId.getValue("qwen3-1.7b")
        assertEquals(977_184_032L, mid.sizeBytes)
        assertEquals("2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8", mid.sha256)
        assertEquals(ModelRole.CHAT, mid.role)
        assertTrue(mid.url.contains("litert-community/Qwen3-1.7B/resolve/87603f0/"))

        val deep = byId.getValue("qwen3-4b-thinking")
        assertEquals(2_477_192_112L, deep.sizeBytes)
        assertEquals("8a8a6a419a5570b3f4814d358fb795c0dcb6931206c7dae29480f47564c3f04d", deep.sha256)
        assertEquals(ModelRole.DEEP, deep.role)
        assertTrue(deep.thinkingByDefault)
        assertTrue(deep.url.contains("Qwen3-4B-Thinking-2507/resolve/f8ce479/"))

        entries.forEach { entry ->
            assertTrue(ModelCatalog.isSha256Shape(entry.sha256), entry.id)
            assertTrue(entry.sizeBytes >= Limits.MIN_MODEL_BYTES, entry.id)
            assertTrue(entry.url.startsWith("https://huggingface.co/"), entry.id)
        }
        assertEquals(small, ModelCatalog.defaultEntry())
        assertEquals(mid, ModelCatalog.byRole(ModelRole.CHAT))
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
