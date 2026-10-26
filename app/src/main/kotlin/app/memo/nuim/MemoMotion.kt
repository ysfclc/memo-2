package app.memo.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Memo motion primitives — subtle scale, fade, and slide animations
 * that give the UI a paper‑like feel without breaking the checkpoint‑driven
 * feel of the underlying engine.
 */
@Composable
fun MemoMotion(
    modifier: Modifier = Modifier,
    fadeIn: Boolean = true,
    scaleIn: Boolean = true,
) {
    if (fadeIn) {
        modifier.alpha(if (scaleIn) tween(200f) else 1f)
    }
    if (scaleIn) {
        modifier.scale(if (fadeIn) tween(250f) else 1f)
    }
}