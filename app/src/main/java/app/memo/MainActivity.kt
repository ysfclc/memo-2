package app.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memo.ui.MemoTheme
import app.memo.ui.nui.scaffold.ScreenScaffold

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MemoApp() }
    }
}

@Composable
private fun MemoApp() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    MemoTheme {
        ScreenScaffold(
            title = if (selectedIndex == 0) "Live environment" else "Settings",
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { selectedIndex = it },
        ) { padding ->
            if (selectedIndex == 0) {
                LiveEnvironment(Modifier.padding(padding))
            } else {
                SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun LiveEnvironment(modifier: Modifier = Modifier) {
    val runs = listOf(
        RunItem("Project X brainstorm", "Reasoning complete · artifact saved", "Ready", true),
        RunItem("CRM enrichment", "Waiting for missing company detail", "Review", false),
        RunItem("Content measurement", "Scheduled for tomorrow at 09:00", "Queued", false),
    )
    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Good evening", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Memo keeps work moving, and pauses when your approval matters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TrustCard()
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Active workflows", style = MaterialTheme.typography.titleLarge)
                AssistChip(onClick = {}, label = { Text("3 tracked") })
            }
        }
        items(runs) { run -> RunCard(run) }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Quick capture", style = MaterialTheme.typography.titleLarge)
            Text(
                "Use the Talk surface to turn an idea into a checkpointed plan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrustCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Column(Modifier.weight(1f)) {
                Text("Protected workspace", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                Text("No actions are sent without a checkpoint.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f))
            }
            Icon(Icons.Outlined.Lock, contentDescription = "Encrypted", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun RunCard(run: RunItem) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (run.complete) Icons.Outlined.CheckCircle else Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = if (run.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.weight(1f)) {
                Text(run.title, fontWeight = FontWeight.SemiBold)
                Text(run.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(run.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        SettingRow(Icons.Outlined.GraphicEq, "Voice capture", "In-app session enabled; no passive listening")
        SettingRow(Icons.Outlined.Shield, "Approval safety", "Artifacts are bound to their content hash")
        SettingRow(Icons.Outlined.Lock, "Private storage", "Encrypted local workspace and audit trail")
    }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class RunItem(val title: String, val detail: String, val status: String, val complete: Boolean)
