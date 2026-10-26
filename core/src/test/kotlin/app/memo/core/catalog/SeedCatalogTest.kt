package app.memo.core.catalog

import app.memo.core.platform.ModelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Invariants every pinned seed entry must satisfy. The values themselves come from the live API. */
class SeedCatalogTest {

    private val hex40 = Regex("^[0-9a-f]{40}$")
    private val hex64 = Regex("^[0-9a-f]{64}$")

    @Test
    fun everyEntryIsFullyPinned() {
        assertTrue(ModelCatalog.entries.size >= 15, "seed list is shorter than planned")
        ModelCatalog.entries.forEach { e ->
            assertTrue(hex64.matches(e.sha256), "${e.id}: sha256 must be 64 lowercase hex")
            assertTrue(e.sizeBytes > 0, "${e.id}: size must be > 0")
            assertTrue(hex40.matches(e.revision), "${e.id}: revision must be a 40-hex commit sha")
            assertTrue(e.url.startsWith("https://huggingface.co/"), "${e.id}: url must be https on huggingface.co")
            assertTrue(e.url.contains("/resolve/${e.revision}/"), "${e.id}: url must pin the revision")
            assertTrue(e.url.endsWith("/" + e.file), "${e.id}: url must end with the file path")
            assertFalse(e.gated, "${e.id}: gated repos cannot be pinned without a token")
            assertTrue(e.license != null, "${e.id}: licence must be recorded from the API")
        }
    }

    @Test
    fun formatMatchesTheFileExtension() {
        ModelCatalog.entries.forEach { e ->
            when (e.format) {
                ModelFormat.LITERTLM -> assertTrue(e.file.endsWith(".litertlm"), e.id)
                ModelFormat.GGUF -> {
                    assertTrue(e.file.endsWith(".gguf"), e.id)
                    assertTrue(e.quant != null, "${e.id}: GGUF entries carry their quant")
                }
            }
        }
    }

    @Test
    fun idsAreUniqueAndWellFormed() {
        val ids = ModelCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }}")
        ids.forEach { assertTrue(Regex("^[a-z0-9][a-z0-9.-]{0,63}$").matches(it), it) }
    }

    @Test
    fun bothFormatsArePresent() {
        val formats = ModelCatalog.entries.map { it.format }.toSet()
        assertEquals(setOf(ModelFormat.LITERTLM, ModelFormat.GGUF), formats)
    }

    @Test
    fun knownPinsAreStable() {
        val qwen = ModelCatalog.byId("qwen3-0.6b")!!
        assertEquals("03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856", qwen.sha256)
        val gguf = ModelCatalog.byId("qwen3-1.7b-gguf")!!
        assertEquals("b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897", gguf.sha256)
        assertEquals(1_107_409_472L, gguf.sizeBytes)
    }
}
