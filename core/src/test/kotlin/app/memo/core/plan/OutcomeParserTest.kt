package app.memo.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeParserTest {

    @Test
    fun verbatimIsWhitespaceInsensitiveButCaseSensitive() {
        val haystack = "Published: 1,204 reactions and 87 comments on the launch post."
        assertTrue(OutcomeParser.containsVerbatim("1,204    reactions", haystack))
        assertTrue(OutcomeParser.containsVerbatim("the launch post.", haystack))
        assertFalse(OutcomeParser.containsVerbatim("1,204 REACTIONS", haystack))
        assertFalse(OutcomeParser.containsVerbatim("reactions and 88", haystack))
        assertFalse(OutcomeParser.containsVerbatim("", haystack))
    }

    @Test
    fun evidenceRequiresContainmentAndCaps() {
        val output = "He said the pilot starts in March — signed, Dana."
        val good = OutcomeParser.verifyEvidence(
            kind = app.memo.core.domain.EvidenceKind.QUOTE,
            quote = "the pilot starts in March",
            source = "https://frontier.example/thread/1",
            output = output,
            now = 42L,
        )
        assertEquals("the pilot starts in March", good?.quote)
        assertEquals(42L, good?.capturedAt)

        val mutated = OutcomeParser.verifyEvidence(
            kind = app.memo.core.domain.EvidenceKind.QUOTE,
            quote = "the pilot starts in june",
            source = "src",
            output = output,
            now = 42L,
        )
        assertNull(mutated) // not verbatim -> no evidence

        val emptySource = OutcomeParser.verifyEvidence(
            kind = app.memo.core.domain.EvidenceKind.QUOTE,
            quote = "the pilot starts in March",
            source = "",
            output = output,
            now = 42L,
        )
        assertNull(emptySource)

        val tooLong = OutcomeParser.verifyEvidence(
            kind = app.memo.core.domain.EvidenceKind.QUOTE,
            quote = "x".repeat(app.memo.core.Limits.MAX_EVIDENCE_CHARS + 1),
            source = "src",
            output = "x".repeat(app.memo.core.Limits.MAX_OUTPUT_CHARS),
            now = 42L,
        )
        assertNull(tooLong)
    }

    @Test
    fun extractParsesCommasSuffixesAndSkipsTimeWords() {
        assertEquals(1204L, OutcomeParser.extractNumber("1,204 reactions")?.value)
        assertEquals(false, OutcomeParser.extractNumber("1,204 reactions")?.approximated)
        assertEquals(1200L, OutcomeParser.extractNumber("grew to 1.2k followers")?.value)
        assertEquals(true, OutcomeParser.extractNumber("1.2k followers")?.approximated)
        assertEquals(3_400_000L, OutcomeParser.extractNumber("3.4M views")?.value)
        assertEquals(12L, OutcomeParser.extractNumber("shake for 12 minutes")?.value)
        assertEquals(false, OutcomeParser.extractNumber("12 minutes")?.approximated)
        assertEquals(0L, OutcomeParser.extractNumber("0 likes")?.value)
        assertNull(OutcomeParser.extractNumber("no numbers"))
    }

    @Test
    fun extractFirstJsonObjectFindsBalancedBlock() {
        val text = "Sure. Here you go: {\"title\":\"Plan\",\"steps\":[{\"id\":\"a\",\"kind\":\"reason\"}]} — hope that helps"
        assertEquals(
            "{\"title\":\"Plan\",\"steps\":[{\"id\":\"a\",\"kind\":\"reason\"}]}",
            OutcomeParser.extractFirstJsonObject(text),
        )
        assertNull(OutcomeParser.extractFirstJsonObject("no json"))
        assertNull(OutcomeParser.extractFirstJsonObject("{\"unbalanced"))
    }

    @Test
    fun locateSkipsBracesInsideStrings() {
        val text = """prefix {"json":"with } brace"} suffix"""
        assertEquals("""{"json":"with } brace"}""", Parsing.locateFirstJsonObject(text))
    }
}
