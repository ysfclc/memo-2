package app.memo.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colour roles for Paper + Evergreen + Mint (D-024, refining D-008).
 *
 * Screens never name a hex value. They read a role through [MemoTheme.colors].
 * Pure data with no Android types, so the palette is testable on the JVM.
 */
data class MemoColorScheme(
    val isDark: Boolean,
    /** App background. */
    val canvas: Color,
    /** Cards and sheets that sit on the canvas. */
    val surface: Color,
    /** Raised cards, menus and sheets. */
    val surfaceRaised: Color,
    /** Inputs and wells pressed into the surface. */
    val surfaceSunken: Color,
    /** Decorative hairlines. Not a contrast target. */
    val outline: Color,
    /** Control boundaries and dividers that must stay visible (3:1 on surface). */
    val outlineStrong: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text. 4.5:1 on canvas and surface. */
    val inkMuted: Color,
    /** Placeholders and disabled text. 3:1 on surface, for large text and icons only. */
    val inkFaint: Color,
    /** Evergreen primary action colour. */
    val accent: Color,
    /** Content on [accent]. */
    val onAccent: Color,
    /** Mint soft fill: chips, selected rows, "active" state. */
    val accentSoft: Color,
    /** Amber: "waiting for you", needs a decision. */
    val attention: Color,
    /** Destructive actions and hard errors. */
    val danger: Color,
    /** Completed and verified states. */
    val success: Color,
    /** Teal links. */
    val link: Color,
    /** Modal dimming behind sheets and dialogs. */
    val scrim: Color,
    /** Keyboard and accessibility focus indicator (3:1 on surface). */
    val focusRing: Color,
) {
    companion object {
        val Light = MemoColorScheme(
            isDark = false,
            canvas = Color(0xFFF4F2EC),
            surface = Color(0xFFFFFEFA),
            surfaceRaised = Color(0xFFFFFFFF),
            surfaceSunken = Color(0xFFECE9E0),
            outline = Color(0xFFE2DED2),
            outlineStrong = Color(0xFF8C9488),
            ink = Color(0xFF222920),
            inkMuted = Color(0xFF626A5E),
            inkFaint = Color(0xFF7A8276),
            accent = Color(0xFF26524A),
            onAccent = Color(0xFFFFFFFF),
            accentSoft = Color(0xFFB2EFCF),
            attention = Color(0xFFB25A24),
            danger = Color(0xFFB3261E),
            success = Color(0xFF1F6B47),
            link = Color(0xFF276B56),
            scrim = Color(0x73000000),
            focusRing = Color(0xFF26524A),
        )

        val Dark = MemoColorScheme(
            isDark = true,
            canvas = Color(0xFF111714),
            surface = Color(0xFF1A221E),
            surfaceRaised = Color(0xFF222C27),
            surfaceSunken = Color(0xFF0C110E),
            outline = Color(0xFF2C3831),
            outlineStrong = Color(0xFF6A7D70),
            ink = Color(0xFFECEFE8),
            inkMuted = Color(0xFFA9B3A7),
            inkFaint = Color(0xFF7F8A7E),
            accent = Color(0xFFB2EFCF),
            onAccent = Color(0xFF0F2A20),
            accentSoft = Color(0xFF24402F),
            attention = Color(0xFFEE9A62),
            danger = Color(0xFFF2877F),
            success = Color(0xFF7FD6A6),
            link = Color(0xFF7FD1B9),
            scrim = Color(0x99000000),
            focusRing = Color(0xFFB2EFCF),
        )
    }
}

/** 4 dp grid. Values are named by step, not by pixel size, so layouts read as intent. */
data class MemoSpacing(
    val space0: Dp = 0.dp,
    val space1: Dp = 2.dp,
    val space2: Dp = 4.dp,
    val space3: Dp = 8.dp,
    val space4: Dp = 12.dp,
    val space5: Dp = 16.dp,
    val space6: Dp = 20.dp,
    val space7: Dp = 24.dp,
    val space8: Dp = 32.dp,
    val space9: Dp = 40.dp,
    val space10: Dp = 56.dp,
)

data class MemoShapes(
    val xs: CornerBasedShape = RoundedCornerShape(8.dp),
    val sm: CornerBasedShape = RoundedCornerShape(12.dp),
    val md: CornerBasedShape = RoundedCornerShape(18.dp),
    val lg: CornerBasedShape = RoundedCornerShape(24.dp),
    val xl: CornerBasedShape = RoundedCornerShape(30.dp),
    val pill: CornerBasedShape = RoundedCornerShape(percent = 50),
)

/** A soft, low-contrast shadow. Alphas scale the ink colour, so light and dark both stay subtle. */
data class MemoShadow(val elevation: Dp, val ambientAlpha: Float, val spotAlpha: Float)

/**
 * Elevation levels. Premium means subtle: levels step up in shadow depth
 * and in tonal surface (see [surfaceAt]), never in saturated colour.
 */
data class MemoElevation(
    val level0: MemoShadow = MemoShadow(0.dp, 0f, 0f),
    val level1: MemoShadow = MemoShadow(1.dp, 0.04f, 0.06f),
    val level2: MemoShadow = MemoShadow(4.dp, 0.05f, 0.08f),
    val level3: MemoShadow = MemoShadow(10.dp, 0.07f, 0.12f),
) {
    fun of(level: Int): MemoShadow = when (level) {
        0 -> level0
        1 -> level1
        2 -> level2
        3 -> level3
        else -> throw IllegalArgumentException("elevation level must be 0..3, was $level")
    }
}

/** Tonal step per level: level 0 sits on the canvas, 1 on surface, 2 and 3 on surfaceRaised. */
fun MemoColorScheme.surfaceAt(level: Int): Color = when (level) {
    0 -> canvas
    1 -> surface
    2, 3 -> surfaceRaised
    else -> throw IllegalArgumentException("elevation level must be 0..3, was $level")
}

fun MemoColorScheme.ambientShadow(shadow: MemoShadow): Color = ink.copy(alpha = shadow.ambientAlpha)

fun MemoColorScheme.spotShadow(shadow: MemoShadow): Color = ink.copy(alpha = shadow.spotAlpha)
