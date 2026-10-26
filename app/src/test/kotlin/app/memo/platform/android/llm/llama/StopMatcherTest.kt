package app.memo.platform.android.llm.llama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopMatcherTest {

    @Test
    fun stopSplitAcrossChunksIsFoundAndNotEmitted() {
        val m = StopMatcher(listOf("END"))
        val out = StringBuilder()
        out.append(m.feed("ab E"))
        out.append(m.feed("N"))
        out.append(m.feed("Dcd"))
        out.append(m.finish())
        assertEquals("ab ", out.toString())
        assertTrue(m.stopped)
    }

    @Test
    fun heldPrefixThatIsNotAStopIsReleased() {
        val m = StopMatcher(listOf("END"))
        val out = StringBuilder()
        out.append(m.feed("E"))
        out.append(m.feed("X"))
        out.append(m.finish())
        assertEquals("EX", out.toString())
        assertFalse(m.stopped)
    }

    @Test
    fun noStopsPassesEverythingThrough() {
        val m = StopMatcher(emptyList())
        assertEquals("abc", m.feed("abc"))
        assertEquals("", m.finish())
    }

    @Test
    fun textAfterStopIsDropped() {
        val m = StopMatcher(listOf("<end>"))
        assertEquals("hi ", m.feed("hi <end>tail"))
        assertEquals("", m.feed("more"))
        assertEquals("", m.finish())
    }

    @Test
    fun earliestOfSeveralStopsWins() {
        val m = StopMatcher(listOf("world", "lo w"))
        assertEquals("hel", m.feed("hello world"))
        assertTrue(m.stopped)
    }
}
