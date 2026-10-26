package app.memo.ui.nav

/**
 * The root destinations, in bottom-bar order. Pure data, so the back rules are testable on the JVM.
 */
enum class Route(val label: String) {
    LIVE("Live"),
    TALK("Talk"),
    WORK("Work"),
    STUDIO("Studio"),
    BROWSER("Browser"),
    MODELS("Models"),
    SETTINGS("Settings"),
}

/** Where the app is: the selected root tab, and whether a sheet is open above it. */
data class NavState(
    val route: Route = Route.LIVE,
    val sheetOpen: Boolean = false,
)

/** What one back gesture does. */
sealed interface BackAction {
    /** Close the open sheet. The tab stays where it is. */
    data object CloseSheet : BackAction

    /** Select [route]. Used to return from a non-Live root to Live. */
    data class SelectTab(val route: Route) : BackAction

    /** Leave the app. Only Live, with no sheet open, exits. */
    data object Exit : BackAction
}

object NavGraph {

    /** The root a back press from [route] returns to. Null for Live, which is the root that exits. */
    fun backTarget(route: Route): Route? = if (route == Route.LIVE) null else Route.LIVE

    /**
     * An open sheet always closes first. Then a non-Live tab returns to Live, and Live exits.
     */
    fun backAction(state: NavState): BackAction {
        if (state.sheetOpen) return BackAction.CloseSheet
        val target = backTarget(state.route) ?: return BackAction.Exit
        return BackAction.SelectTab(target)
    }

    /** The state after [action] has run. */
    fun afterBack(state: NavState, action: BackAction): NavState = when (action) {
        BackAction.CloseSheet -> state.copy(sheetOpen = false)
        is BackAction.SelectTab -> NavState(route = action.route, sheetOpen = false)
        BackAction.Exit -> state
    }
}
