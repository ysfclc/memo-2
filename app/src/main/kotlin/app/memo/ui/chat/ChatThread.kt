package app.memo.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.MessageStatus
import app.memo.core.voice.ClipPlayerCommand
import app.memo.core.voice.ClipPlayerEvent
import app.memo.core.voice.ClipPlayerState
import app.memo.core.voice.reduceClipPlayback
import app.memo.platform.android.voice.MediaPlayerClipPlayer
import app.memo.runtime.ChatService
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.motion.HapticCue
import app.memo.ui.motion.rememberHaptics
import app.memo.ui.theme.MemoTheme
import app.memo.ui.voice.ReadAloud
import app.memo.ui.voice.ReadAloudMessageButton
import app.memo.ui.voice.ReadAloudMessageLine
import app.memo.ui.voice.rememberReadAloud
import app.memo.ui.voice.formatRecorderElapsed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatThread(
    controller: ChatController,
    streaming: ChatService.Streaming?,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val readAloud = rememberReadAloud()
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
                    readAloud = readAloud,
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
    readAloud: ReadAloud,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editOpen by remember(message.id) { mutableStateOf(false) }
    var deleteOpen by remember(message.id) { mutableStateOf(false) }
    MessageBubble(message.role) {
        if (message.voiceClipRef != null) VoiceMessageBubble(message)
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
                readAloud = readAloud,
                onRegenerate = onRegenerate,
                onEdit = { editOpen = true },
                onDelete = { deleteOpen = true },
            )
        }
        ReadAloudMessageLine(readAloud, message.id)
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

// ------------------------------------------------------------------
// Voice message bubble (M2.B1): audio playback + transcript.
// ------------------------------------------------------------------

/** Playhead refresh cadence while a clip is playing. */
private const val PLAYHEAD_TICK_MS = 200L

/** Segments of the clip progress bar. Uniform heights: they show progress, not audio data. */
private const val CLIP_PROGRESS_BARS = 24

/**
 * The voice-message rendering: tap toggles play/pause (the pure [reduceClipPlayback] machine
 * drives the [MediaPlayerClipPlayer] adapter), the bar row shows real playback progress, and
 * the transcript is shown or honestly explained ([ChatMessage.transcriptNote], "nothing was
 * heard" for an empty transcript, "no transcript" when the reason is unknown). Playback
 * failures surface their reason with a retry on the next tap.
 */
@Composable
private fun VoiceMessageBubble(message: ChatMessage) {
    val ref = message.voiceClipRef ?: return
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val events = remember(ref) { Channel<ClipPlayerEvent>(Channel.CONFLATED) }
    var state by remember(ref) { mutableStateOf<ClipPlayerState>(ClipPlayerState.Idle) }
    var playheadMs by remember(ref) { mutableLongStateOf(0L) }
    val player = remember(ref) { MediaPlayerClipPlayer(context) { events.trySend(it) } }

    DisposableEffect(ref) { onDispose { player.release() } }
    LaunchedEffect(player) {
        for (event in events) {
            val (next, command) = reduceClipPlayback(state, event)
            state = next
            when (command) {
                ClipPlayerCommand.Start -> player.start(ref)
                ClipPlayerCommand.Pause -> player.pause()
                ClipPlayerCommand.Resume -> player.resume()
                null -> Unit
            }
        }
    }
    // One live playhead read per state selection: ticks while playing, one read for the pause
    // position. Device failures to report a position simply keep the last known one.
    LaunchedEffect(state) {
        if (state is ClipPlayerState.Playing) {
            playheadMs = player.currentPositionMs() ?: playheadMs
            while (state is ClipPlayerState.Playing) {
                delay(PLAYHEAD_TICK_MS)
                playheadMs = player.currentPositionMs() ?: playheadMs
            }
        } else if (state is ClipPlayerState.Paused) {
            playheadMs = player.currentPositionMs() ?: playheadMs
        }
    }

    val durationMs = message.durationMs
    val progress = when {
        state is ClipPlayerState.Completed -> 1f
        else -> if (durationMs != null && durationMs > 0L) playheadMs.toFloat() / durationMs else 0f
    }
    val failed = state as? ClipPlayerState.Failed
    val label = when (state) {
        is ClipPlayerState.Playing -> "Pause voice message"
        is ClipPlayerState.Paused -> "Resume voice message"
        is ClipPlayerState.Completed -> "Replay voice message"
        is ClipPlayerState.Failed -> "Retry playing the voice message"
        is ClipPlayerState.Idle -> "Play voice message"
    }
    val stateCaption = when (state) {
        is ClipPlayerState.Playing -> "Playing"
        is ClipPlayerState.Paused -> "Paused"
        is ClipPlayerState.Completed -> "Played in full"
        is ClipPlayerState.Failed -> "Playback failed"
        is ClipPlayerState.Idle -> "Not playing"
    }

    Column(verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3),
            modifier = Modifier
                .clip(MemoTheme.shapes.md)
                .clickable(role = Role.Button, onClickLabel = label) {
                    haptics.perform(HapticCue.TICK)
                    events.trySend(ClipPlayerEvent.Tapped)
                }
                .padding(vertical = MemoTheme.spacing.space2)
                .semantics { stateDescription = "$stateCaption voice message" },
        ) {
            Icon(
                imageVector = if (state is ClipPlayerState.Playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = if (failed != null) MemoTheme.colors.danger else MemoTheme.colors.accent,
            )
            ClipProgressBar(
                fraction = progress,
                failed = failed != null,
                modifier = Modifier.weight(1f).height(28.dp),
            )
            if (durationMs != null) {
                Text(
                    text = formatRecorderElapsed(durationMs),
                    style = MemoTheme.typography.label,
                    color = MemoTheme.colors.inkMuted,
                )
            }
        }
        TranscriptLine(message.transcript, message.transcriptNote)
        failed?.let { playbackFailed ->
            Text(
                text = "Playback failed (${playbackFailed.reason})",
                style = MemoTheme.typography.caption,
                color = MemoTheme.colors.danger,
            )
        }
    }
}

/** One honest transcript line: the text, or exactly why there is none. */
@Composable
private fun TranscriptLine(transcript: String?, note: String?) {
    when {
        transcript == null && note != null -> Text(
            text = "No transcript ($note)",
            style = MemoTheme.typography.bodySmall,
            color = MemoTheme.colors.inkMuted,
        )
        transcript == null -> Text(
            text = "No transcript",
            style = MemoTheme.typography.bodySmall,
            color = MemoTheme.colors.inkMuted,
        )
        transcript.isEmpty() -> Text(
            text = "Transcribed; nothing was heard in the recording.",
            style = MemoTheme.typography.bodySmall,
            color = MemoTheme.colors.inkMuted,
        )
        else -> Text(
            text = transcript,
            style = MemoTheme.typography.bodySmall,
            color = MemoTheme.colors.inkMuted,
        )
    }
}

/**
 * The clip progress bar: uniform segments, played ones in the accent (danger tint when a
 * retry is needed). It reads as playback progress, never as recorded waveform data — the
 * rendering has no per-message amplitudes to show.
 */
@Composable
private fun ClipProgressBar(fraction: Float, failed: Boolean, modifier: Modifier = Modifier) {
    val colors = MemoTheme.colors
    Canvas(modifier) {
        val slot = size.width / CLIP_PROGRESS_BARS
        if (slot <= 0f) return@Canvas
        val barWidth = slot * 0.55f
        val filled = (fraction * CLIP_PROGRESS_BARS).toInt().coerceIn(0, CLIP_PROGRESS_BARS)
        val radius = CornerRadius(barWidth / 2f)
        val barHeight = size.height * 0.45f
        val top = (size.height - barHeight) / 2f
        for (index in 0 until CLIP_PROGRESS_BARS) {
            drawRoundRect(
                color = when {
                    index < filled && failed -> colors.danger
                    index < filled -> colors.accent
                    else -> colors.inkFaint
                },
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, top),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}


@Composable
private fun MessageActions(
    message: ChatMessage,
    canRegenerate: Boolean,
    readAloud: ReadAloud,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row {
        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy message", tint = MemoTheme.colors.inkMuted)
        }
        ReadAloudMessageButton(readAloud, message.id, message.text)
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
