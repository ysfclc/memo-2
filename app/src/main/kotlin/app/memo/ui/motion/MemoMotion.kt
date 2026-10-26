package app.memo.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Every animation in the app names one of these families. Raw tween/spring literals live only here. */
enum class MotionFamily {
    /** Presses, toggles, chips. */
    SNAPPY,
    /** Cards, sheets, layout changes. */
    SMOOTH,
    /** Large surfaces and page transitions. */
    GENTLE,
    /** Celebratory moments only (approval done). */
    BOUNCY,
    /** 90 ms. */
    MICRO,
    /** 180 ms. */
    SHORT,
    /** 260 ms. */
    MEDIUM,
    /** 360 ms. */
    LONG,
    /** 230 ms entrance with emphasized deceleration. */
    ENTRANCE,
    /** Ambient loops (breathing, shimmer, halos). Stopped entirely when reduced. */
    AMBIENT,
}

/** Spring parameters from §2i. */
data class SpringToken(val dampingRatio: Float, val stiffness: Float)

/** Motion tokens from BUILD_PLAN §2i. Values are the single source for the app. */
object MemoMotion {
    val snappySpring = SpringToken(dampingRatio = 0.85f, stiffness = 700f)
    val smoothSpring = SpringToken(dampingRatio = 0.9f, stiffness = 380f)
    val gentleSpring = SpringToken(dampingRatio = 1.0f, stiffness = 200f)
    val bouncySpring = SpringToken(dampingRatio = 0.6f, stiffness = 500f)

    const val MICRO_MS: Int = 90
    const val SHORT_MS: Int = 180
    const val MEDIUM_MS: Int = 260
    const val LONG_MS: Int = 360

    /** Entrance duration for cards, rows and the first paint of a screen. */
    const val ENTRANCE_MS: Int = 230
    /** Entrance rise in dp. */
    const val ENTRANCE_RISE_DP: Float = 7f

    /** Per-item delay for staggered first-appear. */
    const val STAGGER_MS: Int = 28
    /** Items past this index share the last stagger slot, so long lists do not wait on each row. */
    const val STAGGER_MAX_ITEMS: Int = 9

    /** One full breath of the presence orb (scale 0.78 to 1.12). */
    const val ORB_BREATH_MS: Int = 1650
    const val ORB_MIN_SCALE: Float = 0.78f
    const val ORB_MAX_SCALE: Float = 1.12f
    /** Slow colour flow inside the orb shader. Four breaths per cycle. */
    const val ORB_FLOW_MS: Int = ORB_BREATH_MS * 4

    /** One full shimmer sweep across a skeleton. */
    const val SHIMMER_MS: Int = 1150

    /** One pulse of a running step node (scale 1.0 to 1.08). */
    const val PULSE_MS: Int = 1100

    /** Pressed scale for MemoButton and other touchables. */
    const val PRESS_SCALE: Float = 0.965f

    /** Emphasized deceleration for entrances. Brief value (0.16, 1, 0.3, 1); see D-025. */
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Emphasized acceleration for exits, from §2i. */
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun springOf(family: MotionFamily): SpringToken = when (family) {
        MotionFamily.SNAPPY -> snappySpring
        MotionFamily.SMOOTH -> smoothSpring
        MotionFamily.GENTLE -> gentleSpring
        MotionFamily.BOUNCY -> bouncySpring
        else -> throw IllegalArgumentException("$family is not a spring family")
    }

    /** Tween duration for a tween family. */
    fun durationMs(family: MotionFamily): Int = when (family) {
        MotionFamily.MICRO -> MICRO_MS
        MotionFamily.SHORT -> SHORT_MS
        MotionFamily.MEDIUM -> MEDIUM_MS
        MotionFamily.LONG -> LONG_MS
        MotionFamily.ENTRANCE -> ENTRANCE_MS
        MotionFamily.AMBIENT -> SHIMMER_MS
        else -> throw IllegalArgumentException("$family is not a tween family")
    }
}

/**
 * The spec for a family, after reduce-motion is applied. Springs and tweens are used when live,
 * and [snap] when reduced. Ambient families must check [resolveMotion] before looping.
 */
fun <T> motionSpec(family: MotionFamily, reduced: Boolean): AnimationSpec<T> =
    when (resolveMotion(family, reduced)) {
        ResolvedMotion.SPRING -> {
            val token = MemoMotion.springOf(family)
            spring(dampingRatio = token.dampingRatio, stiffness = token.stiffness)
        }
        ResolvedMotion.TWEEN -> tween(
            durationMillis = MemoMotion.durationMs(family),
            easing = if (family == MotionFamily.ENTRANCE) MemoMotion.emphasizedDecelerate else FastOutSlowInEasing,
        )
        ResolvedMotion.SNAP, ResolvedMotion.NONE -> snap()
    }

/** Exit spec: emphasized acceleration over a short tween, or snap when reduced. */
fun <T> exitSpec(reduced: Boolean): AnimationSpec<T> =
    if (reduced) snap() else tween(durationMillis = MemoMotion.SHORT_MS, easing = MemoMotion.emphasizedAccelerate)

/** A looping spec for ambient motion. Only call when [resolveMotion] returns [ResolvedMotion.TWEEN]. */
fun <T> ambientLoopSpec(durationMs: Int, mode: RepeatMode = RepeatMode.Restart): InfiniteRepeatableSpec<T> =
    infiniteRepeatable(
        animation = tween(durationMillis = durationMs, easing = LinearEasing),
        repeatMode = mode,
    )
