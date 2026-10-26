package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleTest {

    @Test
    fun blankMessageGetsDefaultTitle() {
        assertEquals(ChatTitles.EMPTY, ChatTitles.fromFirstMessage("   \n\t "))
    }

    @Test
    fun shortMessageIsKeptAndWhitespaceCollapsed() {
        assertEquals("Hello there world", ChatTitles.fromFirstMessage("  Hello \n  there\tworld  "))
    }

    @Test
    fun longMessageCutsAtWordBoundary() {
        val text = "Write a short note to my landlord about the broken boiler and ask when it can be fixed"
        val title = ChatTitles.fromFirstMessage(text)
        assertTrue(title.length <= ChatLimits.GENERATED_TITLE_CHARS)
        assertEquals("Write a short note to my landlord about the", title)
        assertTrue(text.startsWith(title))
    }

    @Test
    fun exactLengthIsKept() {
        val text = "a".repeat(ChatLimits.GENERATED_TITLE_CHARS)
        assertEquals(text, ChatTitles.fromFirstMessage(text))
    }

    @Test
    fun wordLongerThanLimitIsHardCut() {
        val text = "x".repeat(200)
        assertEquals("x".repeat(ChatLimits.GENERATED_TITLE_CHARS), ChatTitles.fromFirstMessage(text))
    }

    @Test
    fun neverSplitsSurrogatePair() {
        // 47 ASCII characters, then an emoji (two UTF-16 units) starting at index 47, then more text.
        val text = "a".repeat(47) + "😀" + "bbbb"
        val title = ChatTitles.fromFirstMessage(text)
        assertTrue(title.length <= ChatLimits.GENERATED_TITLE_CHARS)
        assertEquals("a".repeat(47), title)
    }

    @Test
    fun titleAlwaysFitsLimitForMixedInput() {
        val samples = listOf(
            "Ünïcode 🙂 with emoji and spaces that go on for quite a while to exceed the limit",
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen",
        )
        for (s in samples) {
            assertTrue(ChatTitles.fromFirstMessage(s).length <= ChatLimits.GENERATED_TITLE_CHARS)
        }
    }
}
