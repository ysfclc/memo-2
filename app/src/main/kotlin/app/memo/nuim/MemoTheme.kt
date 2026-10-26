package app.memo.ui

import androidx.compose.material3.Material3Colors
import androidx.compose.material3.Material3Theme
import androidx.compose.runtime.Composable
import androidx.compose.ui.color.Color
import androidx.compose.ui.text.font.FontWeight
import app.memo.core.redact.ink
import app.memo.core.redact.mint
import app.memo.core.redact.evergreen
import app.memo.core.redact.paperBackground

/**
 * Paper+Mint design system for Memo.
 * Color tokens are defined in `app/src/main/res/values/colors.xml` and
 * mirrored here so the Compose UI can reference them via Material3Theme.
 */
@Composable
fun MemoTheme(content: @Composable () -> Unit) {
    val colors = remember {
        val base = Material3Colors(
            primary = Color(ink),
            secondary = Color(mint),
            surface = Color(paperBackground),
            background = Color(paperBackground),
            error = Color(0xFFB00020),
        )
        // Custom palette extensions so the rest of the app can
        // reference `MemoTheme.colors.*` without pulling in the raw ARGB.
        base.copy(
            onPrimary = Color(0xFFFFFFFF),
            onSurface = Color(ink),
            onSecondary = Color(0xFFFFFFFF),
        )
    }
    Material3Theme(
        colors = colors,
        typography = remember {
            val base = rememberFontFamily(DefaultFont)
            Typography(
                displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, fontFamily = base),
                displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Bold, fontFamily = base),
                displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = base),
                headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = base),
                headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = base),
                headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = base),
                titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = base),
                titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = base),
                titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = base),
                bodyLarge = TextStyle(fontSize = 16.sp, fontFamily = base),
                bodyMedium = TextStyle(fontSize = 14.sp, fontFamily = base),
                bodySmall = TextStyle(fontSize = 12.sp, fontFamily = base),
                labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = base),
                labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Semibold, fontFamily = base),
                labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Semibold, fontFamily = base),
            )
        },
        content = content,
    )
}

/** Default sans‑serif font family used everywhere in the UI. */
private composable fun rememberFontFamily(fallback: String): String {
    // On Android the platform sans‑serif is Roboto; we keep a fallback
    // in case the device does not have Roboto installed.
    return "Roboto, sans-serif"
}

/** Material3 Motion primitives that Memo uses for consistent state changes. */
@Composable
fun MemoMotion(
    inner: @Composable () -> Unit,
) {
    // The real implementation would read from MemoMotion.kt resources;
    // for now we simply pass through.
    inner()
}