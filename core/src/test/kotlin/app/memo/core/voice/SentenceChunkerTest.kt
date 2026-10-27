package app.memo.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SentenceChunkerTest {

    private fun chunkWhole(text: String): List<String> {
        val c = SentenceChunker()
        return c.push(text) + c.flush()
    }

    @Test
    fun splitsOnTerminatorsFollowedByWhitespace() {
        assertEquals(
            listOf("It works.", "Really!", "Yes?"),
            chunkWhole("It works. Really! Yes?"),
        )
    }

    @Test
    fun endOfBufferIsNotABoundaryUntilFlush() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.push("Hello."))
        assertEquals(listOf("Hello."), c.push(" World"))
        assertEquals(listOf("World"), c.flush())
    }

    @Test
    fun streamedTokensGiveTheSameSentencesAsWholeText() {
        val text = "Dr. Smith went home. He was tired! Was it 3.14 km? Yes."
        val whole = chunkWhole(text)
        val c = SentenceChunker()
        val streamed = ArrayList<String>()
        for (ch in text) streamed += c.push(ch.toString())
        streamed += c.flush()
        assertEquals(whole, streamed)
    }

    @Test
    fun abbreviationsDoNotEndSentences() {
        assertEquals(
            listOf("Dr. Smith is in.", "Mr. Jones too."),
            chunkWhole("Dr. Smith is in. Mr. Jones too."),
        )
        assertEquals(
            listOf("Przyjdę np. jutro."),
            chunkWhole("Przyjdę np. jutro."),
        )
        assertEquals(
            listOf("Bu örn. bir cümle.", "Sonra gel."),
            chunkWhole("Bu örn. bir cümle. Sonra gel."),
        )
    }

    @Test
    fun singleCapitalInitialDoesNotEndSentence() {
        assertEquals(listOf("Written by J. Kowalski."), chunkWhole("Written by J. Kowalski."))
    }

    @Test
    fun decimalsDoNotEndSentences() {
        assertEquals(listOf("Pi is 3.14 exactly."), chunkWhole("Pi is 3.14 exactly."))
        assertEquals(listOf("Temperature 21.5 today."), chunkWhole("Temperature 21.5 today."))
    }

    @Test
    fun dotsInsideWordsDoNotEndSentences() {
        assertEquals(listOf("See example.com for details."), chunkWhole("See example.com for details."))
        assertEquals(listOf("Use e.g. this one."), chunkWhole("Use e.g. this one."))
    }

    @Test
    fun emojiAreRemovedAndDoNotSplit() {
        assertEquals(listOf("Great job!", "Keep going."), chunkWhole("Great job! 🎉 Keep going. 😀"))
        assertEquals(emptyList(), chunkWhole("🎉🚀"))
    }

    @Test
    fun polishAndTurkishPunctuationSplits() {
        assertEquals(
            listOf("Powiedział: „Dobrze”.", "Idę do domu."),
            chunkWhole("Powiedział: „Dobrze”. Idę do domu."),
        )
        assertEquals(
            listOf("Merhaba!", "Nasılsın?", "İyiyim."),
            chunkWhole("Merhaba! Nasılsın? İyiyim."),
        )
    }

    @Test
    fun closingQuotesStayWithTheSentence() {
        assertEquals(listOf("He said \"stop.\"", "Then left."), chunkWhole("He said \"stop.\" Then left."))
    }

    @Test
    fun lineBreakEndsASentence() {
        assertEquals(listOf("First line", "Second line"), chunkWhole("First line\nSecond line"))
    }

    @Test
    fun flushReturnsTrailingTextWithoutTerminator() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.push("Done."))
        assertEquals(listOf("Done."), c.push(" "))
        c.push("and then")
        assertEquals(listOf("and then"), c.flush())
        assertEquals(emptyList(), c.flush())
    }

    @Test
    fun overLongSentenceIsCutAtSpace() {
        val c = SentenceChunker(maxChars = 20)
        val out = c.push("alpha beta gamma delta epsilon zeta eta theta")
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.length <= 20 })
        assertEquals("alpha beta gamma delta epsilon zeta eta theta", (out + c.flush()).joinToString(" "))
    }

    @Test
    fun markdownMarksAreRemovedForSpeech() {
        assertEquals(listOf("This is bold and code."), chunkWhole("This is **bold** and `code`."))
    }

    @Test
    fun resetDropsPendingText() {
        val c = SentenceChunker()
        c.push("half a sen")
        c.reset()
        assertEquals(emptyList(), c.flush())
    }

    @Test
    fun rejectsTinyLimit() {
        assertFailsWith<IllegalArgumentException> { SentenceChunker(maxChars = 4) }
    }
}
