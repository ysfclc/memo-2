package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSearchTest {

    private val conv = Conversation(
        id = "a1b2c3d4",
        title = "Şehir planı",
        createdAt = 0,
        updatedAt = 0,
        modelId = null,
    )

    private fun msg(id: String, text: String) = ChatMessage(id = id, role = ChatRole.USER, text = text, createdAt = 0)

    @Test
    fun foldRemovesDiacriticsAndLowercases() {
        assertEquals("cafe", ChatSearch.fold("Café"))
        assertEquals("zolw", ChatSearch.fold("Żółw"))
        assertEquals("zolw", ChatSearch.fold("ŻÓŁW"))
    }

    @Test
    fun turkishDottedAndDotlessIFoldToI() {
        assertEquals("istanbul", ChatSearch.fold("İSTANBUL"))
        assertEquals("istanbul", ChatSearch.fold("ıstanbul"))
        assertEquals(ChatSearch.fold("istanbul"), ChatSearch.fold("Istanbul"))
    }

    @Test
    fun polishStrokedLFoldsToL() {
        assertEquals("lodz", ChatSearch.fold("Łódź"))
        assertEquals(ChatSearch.fold("lodz"), ChatSearch.fold("Łodz"))
        assertEquals(ChatSearch.fold("lezak"), ChatSearch.fold("Leżak"))
    }

    @Test
    fun matchesTitleCaseAndDiacriticInsensitive() {
        assertTrue(ChatSearch.matches("SEHIR", conv, emptyList()))
        assertTrue(ChatSearch.matches("sehir", conv, emptyList()))
        assertTrue(ChatSearch.matches("şehir", conv, emptyList()))
    }

    @Test
    fun matchesMessageText() {
        val messages = listOf(msg("m0000001", "Budget for the Łódź trip"))
        assertTrue(ChatSearch.matches("lodz", conv, messages))
        assertFalse(ChatSearch.matches("paris", conv, messages))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(ChatSearch.matches("   ", conv, emptyList()))
    }
}
