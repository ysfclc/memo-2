package app.memo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.HapticCue
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.MemoMotion
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberHaptics
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoTheme

enum class MemoButtonStyle { PRIMARY, SECONDARY, QUIET }

/**
 * The app button. Presses scale to [MemoMotion.PRESS_SCALE] on a snappy spring and tick once.
 * Disabled buttons fade and do not respond.
 */
@Composable
fun MemoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: MemoButtonStyle = MemoButtonStyle.PRIMARY,
    contentColor: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberReduceMotion()
    val haptics = rememberHaptics()
    val colors = MemoTheme.colors

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) MemoMotion.PRESS_SCALE else 1f,
        animationSpec = motionSpec(MotionFamily.SNAPPY, reduced),
        label = "memo-button-press",
    )
    val container by animateColorAsState(
        targetValue = when (style) {
            MemoButtonStyle.PRIMARY -> colors.accent
            MemoButtonStyle.SECONDARY, MemoButtonStyle.QUIET -> Color.Transparent
        },
        animationSpec = motionSpec(MotionFamily.SHORT, reduced),
        label = "memo-button-container",
    )
    val foreground = contentColor ?: when (style) {
        MemoButtonStyle.PRIMARY -> colors.onAccent
        MemoButtonStyle.SECONDARY -> colors.ink
        MemoButtonStyle.QUIET -> colors.link
    }
    val shape = MemoTheme.shapes.pill

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) 1f else 0.4f)
            .defaultMinSize(minHeight = 48.dp)
            .background(container, shape)
            .then(if (style == MemoButtonStyle.SECONDARY) Modifier.border(1.dp, colors.outlineStrong, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.perform(HapticCue.TICK)
                    onClick()
                },
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            content()
        }
    }
}
