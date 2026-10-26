package app.memo.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NavGraphTest {

    @Test
    fun everyRouteHasABackTarget() {
        Route.entries.forEach { route ->
            val target = NavGraph.backTarget(route)
            if (route == Route.LIVE) {
                assertNull(target, "Live is the root that exits")
            } else {
                assertEquals(Route.LIVE, target, "route $route returns to Live")
            }
        }
    }

    @Test
    fun backFromTheRootOfLiveMeansExit() {
        assertEquals(BackAction.Exit, NavGraph.backAction(NavState(Route.LIVE, sheetOpen = false)))
    }

    @Test
    fun backFromAnotherRootGoesToLive() {
        Route.entries.filter { it != Route.LIVE }.forEach { route ->
            assertEquals(
                BackAction.SelectTab(Route.LIVE),
                NavGraph.backAction(NavState(route, sheetOpen = false)),
                "route $route",
            )
        }
    }

    @Test
    fun aSheetClosesBeforeTheTabChanges() {
        val talkWithSheet = NavState(Route.TALK, sheetOpen = true)
        assertEquals(BackAction.CloseSheet, NavGraph.backAction(talkWithSheet))

        val afterClose = NavGraph.afterBack(talkWithSheet, BackAction.CloseSheet)
        assertEquals(NavState(Route.TALK, sheetOpen = false), afterClose)
        assertEquals(BackAction.SelectTab(Route.LIVE), NavGraph.backAction(afterClose))
    }

    @Test
    fun aSheetOnLiveClosesInsteadOfExiting() {
        assertEquals(BackAction.CloseSheet, NavGraph.backAction(NavState(Route.LIVE, sheetOpen = true)))
    }

    @Test
    fun selectingLiveFromAnotherTabLeavesNoSheetOpen() {
        val next = NavGraph.afterBack(NavState(Route.BROWSER, sheetOpen = false), BackAction.SelectTab(Route.LIVE))
        assertEquals(NavState(Route.LIVE, sheetOpen = false), next)
    }

    @Test
    fun exitLeavesTheStateUnchanged() {
        val live = NavState(Route.LIVE, sheetOpen = false)
        assertEquals(live, NavGraph.afterBack(live, BackAction.Exit))
    }

    @Test
    fun tabOrderMatchesTheBrief() {
        assertEquals(
            listOf("Live", "Talk", "Work", "Studio", "Browser", "Models", "Settings"),
            Route.entries.map { it.label },
        )
    }

    @Test
    fun sharedElementKeysAreUniqueAcrossKindsAndIds() {
        val keys = SharedKind.entries.flatMap { kind ->
            listOf("a", "b").map { id -> sharedElementKey(kind, id) }
        }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(sharedElementKey(SharedKind.RUN, "x"), sharedElementKey(SharedKind.MODEL, "x"))
        assertEquals("run/x", sharedElementKey(SharedKind.RUN, "x"))
    }
}
