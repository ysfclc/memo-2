package app.memo.core.catalog

import app.memo.core.platform.ModelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Parses recorded Hugging Face API responses from `core/src/test/resources/hf/`. */
class HfParsersTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/hf/$name")) { "missing fixture $name" }.readText()

    private val qwenRevision = "fed81d17517e6ea64362e917ecf7e5accff12492"
    private val qwenSha = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856"

    @Test
    fun listEndpointYieldsRepoIdsAndTags() {
        val gguf = HfParsers.parseList(fixture("list_gguf_search_qwen3.json"))
        assertEquals(5, gguf.size)
        assertTrue(gguf.all { it.repo.contains('/') && it.downloads >= 0 })

        val litert = HfParsers.parseList(fixture("list_litert_head.json"))
        assertEquals(5, litert.size)
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", litert[0].repo)
        assertTrue(litert[0].tags.contains("litert-lm"))
    }

    @Test
    fun modelEndpointGivesRevisionAndOpenLicence() {
        val info = HfParsers.parseModelInfo(fixture("model_litert_qwen3_0_6b.json"))
        assertEquals("litert-community/Qwen3-0.6B", info.repo)
        assertEquals(qwenRevision, info.revision)
        assertFalse(info.gated)
        assertEquals("apache-2.0", info.license)
    }

    @Test
    fun gatedModelIsFlaggedAndItsLicenceRead() {
        val info = HfParsers.parseModelInfo(fixture("model_litert_llama32_1b_gated.json"))
        assertTrue(info.gated)
        assertEquals("llama3.2", info.license)
        assertEquals("fd0e16d32f23ffc87473750e294fef8d8bf081e1", info.revision)
    }

    @Test
    fun modelWithoutRevisionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            HfParsers.parseModelInfo("""{"id":"litert-community/Qwen3-0.6B","sha":"not-a-sha"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            HfParsers.parseModelInfo("""{"id":"litert-community/Qwen3-0.6B"}""")
        }
    }

    @Test
    fun treeEndpointGivesSizeAndLfsSha256() {
        val files = HfParsers.parseTree(fixture("tree_litert_qwen3_0_6b.json"))
        val target = files.single { it.path == "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm" }
        assertEquals(344_671_744L, target.sizeBytes)
        assertEquals(qwenSha, target.sha256)
        assertNull(files.firstOrNull { it.path == ".gitattributes" }?.sha256)
    }

    @Test
    fun treeFileBecomesPinnedCandidate() {
        val info = HfParsers.parseModelInfo(fixture("model_litert_qwen3_0_6b.json"))
        val files = HfParsers.parseTree(fixture("tree_litert_qwen3_0_6b.json"))
        val entry = HfParsers.candidates(info, files)
            .single { it.file == "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm" }
        assertEquals(ModelFormat.LITERTLM, entry.format)
        assertEquals(qwenSha, entry.sha256)
        assertEquals(344_671_744L, entry.sizeBytes)
        assertEquals(
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/$qwenRevision/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            entry.url,
        )
        assertEquals("0.6B", entry.params)
        assertEquals("Qwen", entry.family)
        assertNull(entry.quant)
        assertEquals("apache-2.0", entry.license)
        assertFalse(entry.gated)
    }

    @Test
    fun gatedTreeMasksShaSoNothingIsPinned() {
        val info = HfParsers.parseModelInfo(fixture("model_litert_llama32_1b_gated.json"))
        val files = HfParsers.parseTree(fixture("tree_litert_llama32_1b_gated.json"))
        val weights = files.single { it.path.endsWith(".litertlm") }
        assertNull(weights.sha256, "masked lfs.oid must not become a sha")
        assertEquals(963_903_488L, weights.sizeBytes)
        assertNull(HfParsers.candidate(info, weights))
        assertTrue(HfParsers.candidates(info, files).isEmpty())
    }

    @Test
    fun fileWithoutLfsIsRejected() {
        val info = HfParsers.parseModelInfo("""{"id":"someone/tiny","sha":"$qwenRevision"}""")
        val tree = """[
            {"type":"file","oid":"abc","size":1234,"path":"tiny.gguf"},
            {"type":"directory","path":"sub"}
        ]"""
        val files = HfParsers.parseTree(tree)
        assertEquals(listOf("tiny.gguf"), files.map { it.path })
        assertNull(files.single().sha256)
        assertNull(HfParsers.candidate(info, files.single()))
    }

    @Test
    fun unsupportedExtensionOrZeroSizeIsRejected() {
        val info = HfParsers.parseModelInfo("""{"id":"someone/tiny","sha":"$qwenRevision"}""")
        val sha = "a".repeat(64)
        assertNull(HfParsers.candidate(info, HfFile("model.safetensors", 10, sha)))
        assertNull(HfParsers.candidate(info, HfFile("model.gguf", 0, sha)))
        assertNotNull(HfParsers.candidate(info, HfFile("model.gguf", 10, sha)))
    }

    @Test
    fun bestGgufCandidatePicksTheRankedQuant() {
        val info = HfParsers.parseModelInfo("""{"id":"unsloth/Qwen3-1.7B-GGUF","sha":"d7f544eead698dbd1f15126ef60b45a1e1933222","license":"x"}""")
        val files = HfParsers.parseTree(fixture("tree_gguf_qwen3_1_7b.json"))
        val best = assertNotNull(HfParsers.bestGgufCandidate(info, files))
        assertEquals("Qwen3-1.7B-Q4_K_M.gguf", best.file)
        assertEquals(ModelFormat.GGUF, best.format)
        assertEquals("Q4_K_M", best.quant)
        assertEquals("1.7B", best.params)
        assertEquals("Qwen", best.family)
        assertEquals(1_107_409_472L, best.sizeBytes)
        assertEquals("b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897", best.sha256)
    }

    @Test
    fun liteRtPickSkipsPlatformBuildsAndTakesTheGenericOne() {
        val info = HfParsers.parseModelInfo(fixture("model_litert_qwen3_0_6b.json"))
        val files = HfParsers.parseTree(fixture("tree_litert_qwen3_0_6b.json"))
        val picked = assertNotNull(HfParsers.bestLitertCandidate(info, files))
        assertEquals("Qwen3-0.6B.litertlm", picked.file)
        assertEquals("c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", picked.sha256)
        assertNull(picked.quant)
        assertEquals(ModelFormat.LITERTLM, picked.format)
    }

    @Test
    fun formatAndUrlHelpers() {
        assertEquals(ModelFormat.LITERTLM, HfParsers.formatOf("a/b.litertlm"))
        assertEquals(ModelFormat.GGUF, HfParsers.formatOf("a/b.gguf"))
        assertNull(HfParsers.formatOf("a/b.bin"))
        assertEquals(
            "https://huggingface.co/x/y/resolve/abc/f.gguf",
            HfParsers.pinnedUrl("x/y", "abc", "f.gguf"),
        )
    }
}
