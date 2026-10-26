package app.memo.ui.nui.scaffold

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.memo.ui.MemoMotion
import app.memo.ui.MemoTheme

@Composable
fun ScreenScaffold(
    title: String = "Memo",
    selectedIndex: Int = 0,
    onSelectedIndexChange: (Int) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    MemoTheme {
        Scaffold(
            topBar = { Text(title) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedIndex == 0,
                        onClick = { onSelectedIndexChange(0) },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = "Live environment") },
                        label = { Text("Live") },
                    )
                    NavigationBarItem(
                        selected = selectedIndex == 1,
                        onClick = { onSelectedIndexChange(1) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            MemoMotion(modifier = Modifier.fillMaxSize().padding(padding)) {
                content(padding)
            }
        }
    }
}
