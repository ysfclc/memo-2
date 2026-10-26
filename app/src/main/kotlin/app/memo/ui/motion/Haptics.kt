package app.memo.ui.motion

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** The haptic vocabulary. Every cue is gated by [MotionSettings.hapticsEnabled]. */
enum class HapticCue { TICK, CONFIRM, REJECT, LONG_PRESS, ATTENTION }

/** Maps a cue to the platform constant. minSdk 30 covers CONFIRM and REJECT. */
fun hapticFeedbackConstant(cue: HapticCue): Int = when (cue) {
    HapticCue.TICK -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.CONFIRM -> HapticFeedbackConstants.CONFIRM
    HapticCue.REJECT -> HapticFeedbackConstants.REJECT
    HapticCue.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
    HapticCue.ATTENTION -> HapticFeedbackConstants.CONTEXT_CLICK
}

/** Plays haptic cues on [view] when [enabled]. Nothing happens when disabled. */
class Haptics(private val view: View, private val enabled: Boolean) {
    fun perform(cue: HapticCue) {
        if (!enabled) return
        view.performHapticFeedback(hapticFeedbackConstant(cue))
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalMotionSettings.current.hapticsEnabled
    return remember(view, enabled) { Haptics(view, enabled) }
}
