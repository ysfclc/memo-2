package app.memo.ui.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import app.memo.core.catalog.ModelEntry
import app.memo.core.platform.ModelFormat
import app.memo.ui.components.EmptyState
import app.memo.ui.components.MemoCard
import app.memo.ui.components.MemoSheet
import app.memo.ui.components.Pill
import app.memo.ui.theme.MemoTheme

@Composable
fun ChatHistorySheet(controller: ChatController, onDismiss: () -> Unit) {
    LaunchedEffect(controller.query) {
        delay(250)
        controller.indexForSearch()
    }
    MemoSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your conversations", style = MemoTheme.typography.title, color = MemoTheme.colors.ink)
                Text("Stored in your encrypted vault", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close history") }
        }
        OutlinedTextField(
            value = controller.query,
            onValueChange = { controller.query = it },
            modifier = Modifier.fillMaxWidth().padding(top = MemoTheme.spacing.space3),
            singleLine = true,
            placeholder = { Text("Search conversations") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { controller.showArchived = !controller.showArchived }) {
                Text(if (controller.showArchived) "Hide archived" else "Show archived")
            }
            Spacer(Modifier.weight(1f))
            Text("${controller.visibleConversations().size}", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
        }
        val conversations = controller.visibleConversations()
        if (conversations.isEmpty()) {
            EmptyState(
                title = if (controller.query.isBlank()) "No conversations yet" else "No matches",
                body = if (controller.query.isBlank()) "Start a new chat and it will appear here." else "Try another search.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    var menuOpen by remember(conversation.id) { mutableStateOf(false) }
                    MemoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable { controller.open(conversation.id); onDismiss() },
                            ) {
                                Text(conversation.title, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                                Text(
                                    if (conversation.archived) "Archived" else if (conversation.pinned) "Pinned" else "Conversation",
                                    style = MemoTheme.typography.caption,
                                    color = MemoTheme.colors.inkMuted,
                                )
                            }
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Conversation actions") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (conversation.pinned) "Unpin" else "Pin") },
                                    onClick = { menuOpen = false; controller.setPinned(conversation, !conversation.pinned) },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (conversation.archived) "Restore" else "Archive") },
                                    onClick = { menuOpen = false; controller.setArchived(conversation, !conversation.archived) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { menuOpen = false; controller.delete(conversation) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatModelPickerSheet(
    models: List<ModelEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MemoSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Choose a model", modifier = Modifier.weight(1f), style = MemoTheme.typography.title, color = MemoTheme.colors.ink)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close model picker") }
        }
        Text("Verified models installed on this device", style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
        Spacer(Modifier.height(MemoTheme.spacing.space3))
        if (models.isEmpty()) {
            EmptyState("No local models installed", "Install a verified model in Models to start a private chat.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3)) {
                listOf(ModelFormat.LITERTLM, ModelFormat.GGUF).forEach { format ->
                    val group = models.filter { it.format == format }
                    if (group.isNotEmpty()) {
                        item(key = "format-${format.name}") {
                            Text(if (format == ModelFormat.GGUF) "GGUF" else "LiteRT-LM", style = MemoTheme.typography.label, color = MemoTheme.colors.inkMuted)
                        }
                        items(group, key = { it.id }) { entry ->
                            MemoCard(onClick = { onSelect(entry.id); onDismiss() }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.displayName, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                                        Text(entry.description, style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                                    }
                                    if (entry.id == selectedId) Pill("Selected", emphasis = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(MemoTheme.spacing.space3))
        Text("Frontier models will use Memo’s in-app browser in M4.", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
    }
}
