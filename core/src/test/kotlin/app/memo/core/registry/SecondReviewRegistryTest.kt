package app.memo.core.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONArray

class SecondReviewRegistryTest {

    @Test
    fun unicodeSlugsKeepLettersAndDigitsOfAnyScript() {
        assertEquals("çağrı-yap", NaturalKeys.slug("Çağrı yap"))
        assertEquals("zadzwoń-do-łukasza", NaturalKeys.slug("Zadzwoń do Łukasza"))
        assertEquals("привет-мир", NaturalKeys.slug("Привет мир"))
    }

    @Test
    fun turkishDottedCapitalIFoldsToPlainI() {
        assertEquals("istanbul", NaturalKeys.slug("İstanbul"))
        assertEquals("istanbul", NaturalKeys.slug("İstanbul"))
    }

    @Test
    fun compatibilityFormsAreNormalisedWithNfkc() {
        assertEquals("pay-now", NaturalKeys.slug("Ｐａｙ now"))
    }

    @Test
    fun asciiSlugsAreUnchanged() {
        assertEquals("hello-world", NaturalKeys.slug("Hello World!"))
        assertEquals("a-b", NaturalKeys.slug("a  b"))
        assertEquals("abc-123", NaturalKeys.slug("  ABC 123  "))
    }

    @Test
    fun slugIsCappedAtFortyEightCodePointsWithoutSplittingSurrogates() {
        val slug = NaturalKeys.slug("𝔘".repeat(60))
        assertEquals(48, slug.codePointCount(0, slug.length))
        assertEquals(slug, slug.codePoints().toArray().let { String(it, 0, it.size) })
    }

    @Test
    fun quarantineListIsCappedAndDropsTheOldest() {
        val people = Workspace().people
        // Non-object rows are quarantined one by one: "row 0 ..", "row 1 ..", ...
        val rows = JSONArray()
        repeat(Registry.MAX_QUARANTINED_ROWS + 5) { rows.put(it) }
        people.load(rows)
        val quarantined = people.quarantinedRows()
        assertEquals(Registry.MAX_QUARANTINED_ROWS, quarantined.size)
        assertEquals("row 5 not an object", quarantined.first().reason)
        assertEquals("row 204 not an object", quarantined.last().reason)
    }
}
