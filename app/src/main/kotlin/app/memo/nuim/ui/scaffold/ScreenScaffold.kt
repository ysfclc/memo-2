package app.memo.ui.nui.scaffold

import app.memo.ui.MemoTheme
import app.memo.ui.MemoMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Top‑level scaffold that provides the Memo paper‑style background,
 * bottom navigation, and a place for modal sheets (approvals, model picker).
 */
@Composable
fun ScreenScaffold(
    title: String = "Memo",
    navItems: @Composable ((Int) -> Unit)? = null,
    content: @Composable (@nav: Int) -> Unit,
) {
    val scaffoldState = rememberScaffoldState(
        bottomBarIndex = 0,
    )

    MemoTheme {
        MemoMotion {
            Scaffold(
                scaffoldState = scaffoldState,
                bottomBar = {
                    // Placeholder for bottom navigation; will be wired in M4.
                    androidx.compose.material3.BottomNavigation {
                        items(
                            items = 4,
                            content = { navItem ->
                                androidx.compose.material3.BamberNavItem(
                                    icon = { /* icon */ },
                                    label = { /* label */ ),
                                    selected = false,
                                    onClick = { },
                                )
                            },
                        )
                    }
                },
            ) { padding ->
                // Page content area with paper‑style padding.
                Modifier.padding(padding).fillMaxSize()
            }
        }
    }
}