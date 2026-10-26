package app.memo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

/**
 * A count tile. The number rolls when it changes ([DigitRoll]), and [suffix] sits after it
 * unchanged, for marks such as a check or an alert.
 */
@Composable
fun StatTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    val colors = MemoTheme.colors
    Column(
        modifier
            .clip(MemoTheme.shapes.sm)
            .background(colors.surface)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .semantics { contentDescription = "$label $value$suffix" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DigitRoll(value = value, style = MemoTheme.typography.title, color = colors.ink)
            if (suffix.isNotEmpty()) {
                Text(suffix, style = MemoTheme.typography.title, color = colors.ink)
            }
        }
        Text(label, style = MemoTheme.typography.caption, color = colors.inkMuted)
    }
}

/** A number that rolls up or down when it changes. Static when reduce-motion is on. */
@Composable
fun DigitRoll(
    value: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReduceMotion()
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState >= initialState) {
                (slideInVertically(motionSpec<IntOffset>(MotionFamily.MEDIUM, false)) { it / 2 } +
                    fadeIn(motionSpec(MotionFamily.SHORT, false))) togetherWith
                    (slideOutVertically(motionSpec<IntOffset>(MotionFamily.MEDIUM, false)) { -it / 2 } +
                        fadeOut(motionSpec(MotionFamily.SHORT, false)))
            } else {
                (slideInVertically(motionSpec<IntOffset>(MotionFamily.MEDIUM, false)) { -it / 2 } +
                    fadeIn(motionSpec(MotionFamily.SHORT, false))) togetherWith
                    (slideOutVertically(motionSpec<IntOffset>(MotionFamily.MEDIUM, false)) { it / 2 } +
                        fadeOut(motionSpec(MotionFamily.SHORT, false)))
            }
        },
        label = "memo-digit-roll",
    ) { current ->
        Text(current.toString(), style = style, color = color)
    }
}
