package app.memo.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** The kinds of content that move between screens as one shared element. */
enum class SharedKind(val prefix: String) {
    /** A record card, such as a person or a company. */
    RECORD("record"),

    /** A workflow run card. */
    RUN("run"),

    /** A model card. */
    MODEL("model"),

    /** A chat message that turns into a full artifact. */
    MESSAGE_ARTIFACT("message-artifact"),
}

/** The shared-element key for [kind] and [id]. Keys are unique across kinds because of the prefix. */
fun sharedElementKey(kind: SharedKind, id: String): String = "${kind.prefix}/$id"

/** Set by [MemoNavShell]. Null outside the shell, for example in previews, and the modifier then does nothing. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Set by [MemoNavShell] for the tab that is currently animating. */
val LocalNavVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks this element as the shared bounds for [kind] and [id]. When the same key appears on the
 * incoming screen, the element morphs between the two. Outside the shell it has no effect.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedElement(kind: SharedKind, id: String): Modifier = composed {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalNavVisibilityScope.current
    if (shared == null || visibility == null) {
        this
    } else {
        with(shared) {
            this@sharedElement.sharedBounds(
                sharedContentState = rememberSharedContentState(sharedElementKey(kind, id)),
                animatedVisibilityScope = visibility,
            )
        }
    }
}
