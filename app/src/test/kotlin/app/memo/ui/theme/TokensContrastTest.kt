package app.memo.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.x relative luminance of an sRGB colour. */
internal fun relativeLuminance(c: Color): Double {
    fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
}

/** WCAG 2.x contrast ratio, from 1.0 to 21.0. */
internal fun contrastRatio(fg: Color, bg: Color): Double {
    val a = relativeLuminance(fg)
    val b = relativeLuminance(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

class TokensContrastTest {

    private class Check(val name: String, val fg: Color, val bg: Color, val minimum: Double)

    private fun textPairs(c: MemoColorScheme): List<Check> = listOf(
        Check("ink/canvas", c.ink, c.canvas, 4.5),
        Check("ink/surface", c.ink, c.surface, 4.5),
        Check("inkMuted/surface", c.inkMuted, c.surface, 4.5),
        Check("inkMuted/canvas", c.inkMuted, c.canvas, 4.5),
        Check("onAccent/accent", c.onAccent, c.accent, 4.5),
        Check("attention/surface", c.attention, c.surface, 4.5),
        Check("attention/canvas", c.attention, c.canvas, 4.5),
        Check("danger/surface", c.danger, c.surface, 4.5),
        Check("link/surface", c.link, c.surface, 4.5),
        Check("ink/accentSoft", c.ink, c.accentSoft, 4.5),
    )

    /** Icons, control boundaries and focus indicators need 3:1 (WCAG 1.4.11). */
    private fun nonTextPairs(c: MemoColorScheme): List<Check> = listOf(
        Check("outlineStrong/surface", c.outlineStrong, c.surface, 3.0),
        Check("focusRing/surface", c.focusRing, c.surface, 3.0),
        Check("attention/surface (icon)", c.attention, c.surface, 3.0),
        Check("accent/surface (icon)", c.accent, c.surface, 3.0),
        Check("inkFaint/surface (large text and icons only)", c.inkFaint, c.surface, 3.0),
    )

    private fun assertPairs(pairs: List<Check>, scheme: String) {
        for (p in pairs) {
            val ratio = contrastRatio(p.fg, p.bg)
            assertTrue(
                ratio >= p.minimum,
                "$scheme ${p.name}: ${"%.2f".format(ratio)}:1 is below ${p.minimum}:1",
            )
        }
    }

    @Test
    fun contrastMathMatchesWcagReferencePoints() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.0001)
    }

    @Test
    fun lightTextPairsMeetAa() {
        assertPairs(textPairs(MemoColorScheme.Light), "light")
    }

    @Test
    fun darkTextPairsMeetAa() {
        assertPairs(textPairs(MemoColorScheme.Dark), "dark")
    }

    @Test
    fun lightNonTextPairsMeetThreeToOne() {
        assertPairs(nonTextPairs(MemoColorScheme.Light), "light")
    }

    @Test
    fun darkNonTextPairsMeetThreeToOne() {
        assertPairs(nonTextPairs(MemoColorScheme.Dark), "dark")
    }

    @Test
    fun schemesDeclareTheirMode() {
        assertEquals(false, MemoColorScheme.Light.isDark)
        assertEquals(true, MemoColorScheme.Dark.isDark)
    }
}
