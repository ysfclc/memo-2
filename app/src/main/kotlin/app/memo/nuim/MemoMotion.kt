package app.memo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MemoMotion(
    modifier: Modifier = Modifier,
    fadeIn: Boolean = true,
    scaleIn: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(tween(180)) + if (scaleIn) scaleIn(initialScale = 0.96f, animationSpec = tween(220)) else fadeIn(tween(0)),
    ) { content() }
}
