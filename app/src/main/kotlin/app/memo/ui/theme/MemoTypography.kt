package app.memo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Semantic type roles. Every size is in sp, so text follows the user's font scale.
 * No px, no fixed dp for text. System sans throughout; no bundled fonts.
 */
data class MemoTypeScale(
    /** 36/40, bold, -1% tracking. Hero numbers and the single largest heading. */
    val display: TextStyle,
    /** 28/34. Screen titles. */
    val headline: TextStyle,
    /** 20/26, semibold. Section and card titles. */
    val title: TextStyle,
    /** 16/22, medium. Row titles and emphasised lines. */
    val subtitle: TextStyle,
    /** 16/24. Reading text. */
    val body: TextStyle,
    /** 14/20. Dense secondary text. */
    val bodySmall: TextStyle,
    /** 13/16, medium. Buttons, chips, tabs. */
    val label: TextStyle,
    /** 12/16. Metadata, timestamps, footnotes. */
    val caption: TextStyle,
    /** 13/18, monospace. Hashes, identifiers, code. */
    val mono: TextStyle,
)

val MemoTypography: MemoTypeScale = MemoTypeScale(
    display = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01f).em,
    ),
    headline = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    subtitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

/** Maps the semantic scale onto Material3 roles so stock components pick up the same type. */
fun MemoTypeScale.toMaterial(): Typography = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = headline,
    headlineLarge = headline,
    headlineMedium = headline,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = subtitle,
    titleSmall = subtitle,
    bodyLarge = body,
    bodyMedium = bodySmall,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = label,
    labelSmall = caption,
)
