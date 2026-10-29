package app.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.MessageStatus
import app.memo.runtime.ChatService
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.theme.MemoTheme
import kotlinx.coroutines.launch

@Composable
fun ChatThread(
    controller: ChatController,
    streaming: ChatService.Streaming?,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val doc = controller.doc
    val messages = doc?.messages.orEmpty()
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 || info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    val streamForThisChat = streaming?.takeIf { it.conversationId == doc?.conversation?.id }
    LaunchedEffect(messages.lastOrNull()?.id, streamForThisChat?.text, streamForThisChat?.loading) {
        if (atBottom && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = MemoTheme.spacing.space5,
                vertical = MemoTheme.spacing.space4,
            ),
            verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space4),
        ) {
            if (messages.isEmpty() && streamForThisChat == null) {
                item(key = "empty-chat") { ChatEmptyState(onSuggestion) }
            }
            items(messages, key = { it.id }) { message ->
                ChatMessageRow(
                    message = message,
                    canRegenerate = streaming == null && message.role == ChatRole.ASSISTANT && message.id == messages.lastOrNull()?.id,
                    actionsEnabled = streaming == null,
                    onRegenerate = controller::regenerate,
                    onEdit = { value -> controller.editAndResend(message.id, value) },
                    onDelete = { controller.deleteMessage(message.id) },
                )
            }
            streamForThisChat?.let { current ->
                item(key = "stream-${current.requestId}") {
                    StreamingMessage(current)
                }
            }
        }
        if (!atBottom && listState.layoutInfo.totalItemsCount > 0) {
            MemoButton(
                onClick = { scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(MemoTheme.spacing.space5),
                style = MemoButtonStyle.SECONDARY,
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                Text("Latest")
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = MemoTheme.spacing.space10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3),
    ) {
        Text("What would you like to work through?", style = MemoTheme.typography.title, color = MemoTheme.colors.ink)
        Text("Your conversation stays on this device.", style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
        Spacer(Modifier.height(MemoTheme.spacing.space2))
        listOf("Summarize a note", "Help me plan a task", "Explain a concept").forEach { suggestion ->
            MemoButton(onClick = { onSuggestion(suggestion) }, style = MemoButtonStyle.SECONDARY) { Text(suggestion) }
        }
    }
}

@Composable
private fun StreamingMessage(streaming: ChatService.Streaming) {
    MessageBubble(role = ChatRole.ASSISTANT) {
        if (streaming.thinking.isNotBlank()) {
            Text("Thinking", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
            ChatMarkdown(streaming.thinking)
        }
        if (streaming.text.isNotBlank()) ChatMarkdown(streaming.text)
        if (streaming.loading) Text("Loading model…", style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    canRegenerate: Boolean,
    actionsEnabled: Boolean,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editOpen by remember(message.id) { mutableStateOf(false) }
    var deleteOpen by remember(message.id) { mutableStateOf(false) }
    MessageBubble(message.role) {
        if (message.text.isNotBlank()) ChatMarkdown(message.text)
        if (message.status == MessageStatus.ERROR && message.errorText.isNotBlank()) {
            Text(message.errorText, style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.danger)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (message.status) {
                    MessageStatus.DONE -> message.modelId ?: "Memo"
                    MessageStatus.STOPPED -> "Stopped"
                    MessageStatus.ERROR -> "Could not finish"
                    MessageStatus.STREAMING -> "Streaming"
                },
                modifier = Modifier.weight(1f),
                style = MemoTheme.typography.caption,
                color = MemoTheme.colors.inkFaint,
            )
            if (actionsEnabled) MessageActions(
                message = message,
                canRegenerate = canRegenerate,
                onRegenerate = onRegenerate,
                onEdit = { editOpen = true },
                onDelete = { deleteOpen = true },
            )
        }
    }
    if (editOpen && actionsEnabled) EditMessageDialog(message.text, onDismiss = { editOpen = false }) { updated ->
        editOpen = false
        onEdit(updated)
    }
    if (deleteOpen && actionsEnabled) AlertDialog(
        onDismissRequest = { deleteOpen = false },
        title = { Text("Delete this message?") },
        text = { Text("This message will be removed from the conversation.") },
        confirmButton = {
            TextButton(onClick = { deleteOpen = false; onDelete() }) { Text("Delete", color = MemoTheme.colors.danger) }
        },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Keep") } },
    )
}

@Composable
private fun MessageBubble(role: ChatRole, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val user = role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(MemoTheme.shapes.lg)
                .background(if (user) MemoTheme.colors.accentSoft else MemoTheme.colors.surface)
                .padding(horizontal = MemoTheme.spacing.space5, vertical = MemoTheme.spacing.space4),
            verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3),
            content = content,
        )
    }
}

@Composable
private fun MessageActions(
    message: ChatMessage,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row {
        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy message", tint = MemoTheme.colors.inkMuted)
        }
        if (canRegenerate) IconButton(onClick = onRegenerate) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Regenerate response", tint = MemoTheme.colors.inkMuted)
        }
        if (message.role == ChatRole.USER) IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit and resend", tint = MemoTheme.colors.inkMuted)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete message", tint = MemoTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, minLines = 2, maxLines = 8)
        },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Resend") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
