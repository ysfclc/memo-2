package app.memo.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** User-facing motion and haptics preferences. A later settings package supplies the values. */
data class MotionSettings(
    /** The in-app "reduce motion" switch. */
    val userReduceMotion: Boolean = false,
    /** Gate for every [HapticCue]. */
    val hapticsEnabled: Boolean = true,
)

val LocalMotionSettings = staticCompositionLocalOf { MotionSettings() }

/** Provides [settings] to the subtree. Screens read motion and haptics only through this. */
@Composable
fun ProvideMotionSettings(settings: MotionSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionSettings provides settings, content = content)
}

/** How a motion spec resolves once reduce-motion is decided. Pure, so it is testable on the JVM. */
enum class ResolvedMotion { SPRING, TWEEN, SNAP, NONE }

/**
 * Reduced motion is on when the user asked for it, or when the system animator scale is 0
 * (the "Remove animations" accessibility setting). A scale of 0 is the only value that counts.
 */
fun reducedMotion(animatorScale: Float, userFlag: Boolean): Boolean =
    userFlag || animatorScale <= 0f

/**
 * Collapse rule: with reduced motion every animated spec becomes [ResolvedMotion.SNAP], and
 * ambient loops (orb breathing, shimmer, halos) become [ResolvedMotion.NONE] so they stop.
 */
fun resolveMotion(family: MotionFamily, reduced: Boolean): ResolvedMotion {
    if (reduced) {
        return if (family == MotionFamily.AMBIENT) ResolvedMotion.NONE else ResolvedMotion.SNAP
    }
    return when (family) {
        MotionFamily.SNAPPY, MotionFamily.SMOOTH, MotionFamily.GENTLE, MotionFamily.BOUNCY -> ResolvedMotion.SPRING
        MotionFamily.MICRO, MotionFamily.SHORT, MotionFamily.MEDIUM, MotionFamily.LONG,
        MotionFamily.ENTRANCE, MotionFamily.AMBIENT -> ResolvedMotion.TWEEN
    }
}

/** Stagger index for an item: negative indices are 0, indices above 9 are capped at 9. */
fun staggerIndex(index: Int): Int = index.coerceIn(0, MemoMotion.STAGGER_MAX_ITEMS)

/** Delay before an item's first appearance. Zero when reduced, so nothing waits. */
fun staggerDelayMs(index: Int, reduced: Boolean = false): Int =
    if (reduced) 0 else staggerIndex(index) * MemoMotion.STAGGER_MS

/** Reads the system animator scale, treating "animations disabled" as 0. */
fun systemAnimatorScale(context: Context): Float {
    if (!ValueAnimator.areAnimatorsEnabled()) return 0f
    return runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
}

/** True when every animation in the subtree should collapse to its static fallback. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val userFlag = LocalMotionSettings.current.userReduceMotion
    return remember(context, userFlag) {
        reducedMotion(systemAnimatorScale(context), userFlag)
    }
}
