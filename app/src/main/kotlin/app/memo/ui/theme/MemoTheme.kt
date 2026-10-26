package app.memo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalMemoColors = staticCompositionLocalOf { MemoColorScheme.Light }
val LocalMemoSpacing = staticCompositionLocalOf { MemoSpacing() }
val LocalMemoShapes = staticCompositionLocalOf { MemoShapes() }
val LocalMemoElevation = staticCompositionLocalOf { MemoElevation() }
val LocalMemoTypography = staticCompositionLocalOf { MemoTypography }

/** Read tokens from the nearest [MemoTheme]. Screens use these and nothing else. */
object MemoTheme {
    val colors: MemoColorScheme
        @Composable @ReadOnlyComposable get() = LocalMemoColors.current

    val spacing: MemoSpacing
        @Composable @ReadOnlyComposable get() = LocalMemoSpacing.current

    val shapes: MemoShapes
        @Composable @ReadOnlyComposable get() = LocalMemoShapes.current

    val elevation: MemoElevation
        @Composable @ReadOnlyComposable get() = LocalMemoElevation.current

    val typography: MemoTypeScale
        @Composable @ReadOnlyComposable get() = LocalMemoTypography.current
}

/**
 * The app theme. Brand colours always; dynamic colour is deliberately not used.
 * Also sets the status and navigation bar icon appearance for edge-to-edge
 * to match the resolved light or dark palette.
 */
@Composable
fun MemoTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) MemoColorScheme.Dark else MemoColorScheme.Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    val shapes = MemoShapes()
    CompositionLocalProvider(
        LocalMemoColors provides colors,
        LocalMemoSpacing provides MemoSpacing(),
        LocalMemoShapes provides shapes,
        LocalMemoElevation provides MemoElevation(),
        LocalMemoTypography provides MemoTypography,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = MemoTypography.toMaterial(),
            shapes = Shapes(
                extraSmall = shapes.xs,
                small = shapes.sm,
                medium = shapes.md,
                large = shapes.lg,
                extraLarge = shapes.xl,
            ),
            content = content,
        )
    }
}

/** Maps the semantic roles onto Material3 so stock components (switches, nav bar, sliders) match. */
private fun MemoColorScheme.toMaterial(): ColorScheme {
    val m3 = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = ink,
            secondary = link,
            onSecondary = onAccent,
            secondaryContainer = accentSoft,
            onSecondaryContainer = ink,
            tertiary = attention,
            onTertiary = canvas,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = ink,
            secondary = link,
            onSecondary = onAccent,
            secondaryContainer = accentSoft,
            onSecondaryContainer = ink,
            tertiary = attention,
            onTertiary = surface,
        )
    }
    return m3.copy(
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = inkMuted,
        surfaceTint = accent,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        outline = outlineStrong,
        outlineVariant = outline,
        error = danger,
        onError = surface,
        errorContainer = danger,
        onErrorContainer = surface,
        inverseSurface = ink,
        inverseOnSurface = canvas,
        scrim = scrim,
    )
}
