package app.memo.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuantRankingTest {

    @Test
    fun rankOrderMatchesThePlan() {
        val order = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q4_0", "Q5_K_S", "Q6_K", "Q8_0")
        val ranks = order.map { QuantRanking.rank(it) }
        assertEquals(ranks.sorted(), ranks, "ranks must increase down the list")
        assertEquals(ranks.distinct().size, ranks.size)
        assertTrue(QuantRanking.rank("IQ4_XS") > QuantRanking.rank("Q8_0"))
        assertTrue(QuantRanking.rank("Q4_K_XL") > QuantRanking.rank("Q8_0"), "unknown labels sort last")
        assertTrue(QuantRanking.rank(null) > QuantRanking.rank("IQ4_NL"))
    }

    @Test
    fun quantLabelIsReadFromTheFileName() {
        assertEquals("Q4_K_M", QuantRanking.quantOf("Qwen3-1.7B-Q4_K_M.gguf"))
        assertEquals("Q4_K_XL", QuantRanking.quantOf("gemma-3-1b-it-UD-Q4_K_XL.gguf"))
        assertEquals("IQ4_NL", QuantRanking.quantOf("Llama-3.2-1B-Instruct-IQ4_NL.gguf"))
        assertEquals("Q8_0", QuantRanking.quantOf("DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf"))
        assertNull(QuantRanking.quantOf("model.gguf"))
    }

    @Test
    fun bestGgufPrefersQ4KMOverEverythingElse() {
        val names = listOf(
            "m-Q8_0.gguf",
            "m-IQ4_XS.gguf",
            "m-Q5_K_M.gguf",
            "m-Q4_K_S.gguf",
            "m-Q4_K_M.gguf",
            "m-Q4_0.gguf",
        )
        assertEquals("m-Q4_K_M.gguf", QuantRanking.bestGguf(names))
    }

    @Test
    fun fallsBackDownTheRankingWhenTheBestIsMissing() {
        assertEquals("m-Q4_K_S.gguf", QuantRanking.bestGguf(listOf("m-Q4_K_S.gguf", "m-Q8_0.gguf")))
        assertEquals("m-Q4_0.gguf", QuantRanking.bestGguf(listOf("m-Q4_0.gguf", "m-Q5_K_S.gguf")))
        assertEquals("m-Q8_0.gguf", QuantRanking.bestGguf(listOf("m-IQ4_NL.gguf", "m-Q8_0.gguf")))
        assertEquals("m-IQ4_NL.gguf", QuantRanking.bestGguf(listOf("m-IQ4_NL.gguf", "m-Q4_K_XL.gguf")))
    }

    @Test
    fun nonGgufFilesAndShardsAreNeverPicked() {
        assertNull(QuantRanking.bestGguf(listOf("README.md", "model.litertlm")))
        assertNull(QuantRanking.bestGguf(listOf("m-Q4_K_M-00001-of-00002.gguf")))
        assertEquals(
            "m-Q8_0.gguf",
            QuantRanking.bestGguf(listOf("m-Q4_K_M-00001-of-00002.gguf", "m-Q8_0.gguf")),
        )
    }
}
