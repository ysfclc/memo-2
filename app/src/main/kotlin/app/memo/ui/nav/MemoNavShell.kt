package app.memo.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.exitSpec
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import kotlinx.coroutines.flow.Flow

/** The icon for each root destination. Labels come from [Route.label]. */
fun Route.icon(): ImageVector = when (this) {
    Route.LIVE -> Icons.Outlined.Home
    Route.TALK -> Icons.Outlined.Mic
    Route.WORK -> Icons.Outlined.Person
    Route.STUDIO -> Icons.Outlined.Edit
    Route.BROWSER -> Icons.Outlined.Public
    Route.MODELS -> Icons.Outlined.Download
    Route.SETTINGS -> Icons.Outlined.Settings
}

/**
 * The app shell: the bottom bar, a shared-transition layout, and animated tab content.
 *
 * Tab changes slide and fade. With reduce-motion they cut. Predictive back scales the content
 * down while the gesture is in progress, and a completed gesture applies [NavGraph.backAction].
 * [onStateChange] is the single place where navigation state changes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MemoNavShell(
    state: NavState,
    onStateChange: (NavState) -> Unit,
    screen: @Composable (Route) -> Unit,
) {
    val reduced = rememberReduceMotion()
    var backProgress by remember { mutableFloatStateOf(0f) }
    val action = NavGraph.backAction(state)

    // Live with no sheet is the only exit. Leaving the handler disabled hands that back to the system.
    PredictiveBackHandler(enabled = action != BackAction.Exit) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { backProgress = it.progress }
            onStateChange(NavGraph.afterBack(state, action))
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Route.entries.forEach { route ->
                    NavigationBarItem(
                        selected = state.route == route,
                        onClick = { onStateChange(NavState(route = route, sheetOpen = false)) },
                        icon = { Icon(route.icon(), contentDescription = route.label) },
                        label = { Text(route.label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { pad ->
        SharedTransitionLayout(Modifier.fillMaxSize().padding(pad)) {
            AnimatedContent(
                targetState = state.route,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - 0.08f * backProgress
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.2f * backProgress
                    },
                transitionSpec = {
                    if (reduced) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(motionSpec<Float>(MotionFamily.ENTRANCE, false)) +
                            slideInHorizontally(motionSpec<IntOffset>(MotionFamily.GENTLE, false)) { it / 12 * direction }) togetherWith
                            (fadeOut(exitSpec<Float>(false)) +
                                slideOutHorizontally(exitSpec<IntOffset>(false)) { -it / 12 * direction })
                    }
                },
                label = "memo-tabs",
            ) { route ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalNavVisibilityScope provides this@AnimatedContent,
                ) {
                    screen(route)
                }
            }
        }
    }
}
