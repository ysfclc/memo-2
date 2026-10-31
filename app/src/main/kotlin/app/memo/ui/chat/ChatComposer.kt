package app.memo.ui.chat

import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.memo.core.chat.ChatLimits
import app.memo.core.chat.VoiceClipDraft
import app.memo.platform.android.voice.AudioCapture
import app.memo.platform.android.voice.MicPermission
import app.memo.runtime.ChatService
import app.memo.runtime.VoiceMessageRecorder
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.theme.MemoTheme
import app.memo.ui.voice.VoiceRecorderBar
import app.memo.ui.voice.formatRecorderElapsed
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
fun ChatComposer(
    controller: ChatController,
    streaming: ChatService.Streaming?,
    voiceMessages: VoiceMessageRecorder,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "attachment.txt"
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { readBounded(it, ChatLimits.MAX_ATTACHMENT_BYTES + 1) }
                    ?: throw IllegalStateException("file could not be opened")
            }.onSuccess { controller.attach(name, it) }
                .onFailure { controller.rejectAttachment(name, "file could not be opened") }
        }
    }

    var voiceMode by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var levels by remember { mutableStateOf(emptyList<Float>()) }
    var elapsedMs by remember { mutableStateOf(0L) }
    // The recorder's callbacks arrive on its executor thread; state writes and controller
    // notices hop through the main handler so the composition observes them in order.
    val main = remember { Handler(Looper.getMainLooper()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) voiceMode = true
    }

    fun exitVoiceMode() {
        voiceMode = false
        levels = emptyList()
        elapsedMs = 0L
    }

    /**
     * Keeps a finished recording pending as the composer's voice clip. A clip that breaks the
     * stored bounds is refused with a notice — never a crash. Returns whether it was attached.
     */
    fun attachDraftClip(ref: String, durationMs: Long, transcript: String? = null, note: String? = null): Boolean {
        val clip = runCatching {
            VoiceClipDraft(
                ref,
                durationMs.coerceIn(1L, ChatLimits.MAX_VOICE_DURATION_MS.toLong()),
                transcript = transcript,
                transcriptNote = note,
            )
        }.getOrNull()
        if (clip == null) {
            controller.showNotice("Could not attach the recording.")
            return false
        }
        controller.attachVoiceClip(clip)
        return true
    }

    fun beginRecording() {
        val started = voiceMessages.start { frameLevels, frameMs ->
            levels = frameLevels
            elapsedMs = frameMs
        }
        if (started != AudioCapture.Start.Started) {
            exitVoiceMode()
            controller.showNotice("The microphone is off.")
        }
    }

    fun cancelRecording() {
        voiceMessages.cancel()
        exitVoiceMode()
    }

    fun releaseRecording() {
        val finishing = voiceMessages.finish { recorded ->
            main.post {
                exitVoiceMode()
                when (recorded) {
                    is VoiceMessageRecorder.Recorded.Message -> {
                        attachDraftClip(
                            ref = recorded.file.name,
                            durationMs = recorded.durationMs,
                            transcript = recorded.transcript,
                        )
                        if (recorded.transcript.isBlank()) {
                            controller.showNotice("Recording kept, but nothing was transcribed.")
                        } else {
                            controller.onTextChanged(recorded.transcript)
                        }
                    }
                    is VoiceMessageRecorder.Recorded.Untranscribed -> {
                        val attached = recorded.file != null && attachDraftClip(
                            ref = recorded.file.name,
                            durationMs = recorded.durationMs,
                            note = recorded.reason.take(ChatLimits.MAX_TRANSCRIPT_NOTE_CHARS),
                        )
                        if (recorded.file == null) {
                            controller.showNotice("Audio kept; transcription unavailable: ${recorded.reason}.")
                        } else if (attached) {
                            controller.showNotice("Audio attached; transcription unavailable: ${recorded.reason}.")
                        }
                    }
                    VoiceMessageRecorder.Recorded.Empty -> controller.showNotice("Recording too short; nothing stored.")
                }
            }
        }
        if (finishing) exitVoiceMode()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = MemoTheme.spacing.space5, vertical = MemoTheme.spacing.space3)) {
        controller.composer.attachment?.let { attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(attachment.name, modifier = Modifier.weight(1f), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                IconButton(onClick = controller::removeAttachment) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove attachment", modifier = Modifier.size(18.dp))
                }
            }
        }
        controller.composer.voiceClip?.let { clip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MemoTheme.colors.inkMuted,
                )
                Text(
                    "Voice message · ${formatRecorderElapsed(clip.durationMs)}",
                    modifier = Modifier.weight(1f).padding(horizontal = MemoTheme.spacing.space2),
                    style = MemoTheme.typography.caption,
                    color = MemoTheme.colors.inkMuted,
                )
                IconButton(onClick = controller::removeVoiceClip) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove voice message", modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MemoTheme.shapes.lg)
                .background(MemoTheme.colors.surfaceSunken)
                .padding(horizontal = MemoTheme.spacing.space3, vertical = MemoTheme.spacing.space2),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2),
        ) {
            IconButton(onClick = { picker.launch(arrayOf("text/*", "application/json")) }, enabled = streaming == null && !voiceMode) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "Attach text file", tint = MemoTheme.colors.inkMuted)
            }
            if (voiceMode) {
                // Unlike the Talk tab, a quick tap here returns to typing instead of the live
                // conversation: the Text tab's recording mode is hold-to-record only.
                if (permissionDenied) {
                    Column(Modifier.weight(1f)) {
                        Text("The microphone is off", style = MemoTheme.typography.body, color = MemoTheme.colors.ink)
                        Text(
                            MicPermission.RATIONALE,
                            style = MemoTheme.typography.caption,
                            color = MemoTheme.colors.inkMuted,
                        )
                    }
                } else {
                    VoiceRecorderBar(
                        levels = levels,
                        elapsedMs = elapsedMs,
                        onRecordingStarted = { beginRecording() },
                        onRecordingCanceled = { cancelRecording() },
                        onRecordingSent = { releaseRecording() },
                        // A tap also pressed first, so stop that capture before leaving:
                        // never leave the Text tab with a still-running recorder.
                        onTap = {
                            if (voiceMessages.isRecording) voiceMessages.cancel()
                            exitVoiceMode()
                        },
                        modifier = Modifier.weight(1f),
                        hint = "Hold to record · tap to type instead",
                    )
                }
            } else {
                BasicTextField(
                    value = controller.composer.text,
                    onValueChange = controller::onTextChanged,
                    modifier = Modifier.weight(1f).padding(vertical = MemoTheme.spacing.space4),
                    textStyle = MemoTheme.typography.body.copy(color = MemoTheme.colors.ink),
                    maxLines = 6,
                    enabled = streaming == null,
                    decorationBox = { inner ->
                        if (controller.composer.text.isEmpty()) {
                            Text("Message Memo", style = MemoTheme.typography.body, color = MemoTheme.colors.inkFaint)
                        }
                        inner()
                    },
                )
            }
            IconButton(
                onClick = {
                    if (voiceMode) {
                        if (voiceMessages.isRecording) voiceMessages.cancel()
                        exitVoiceMode()
                    } else if (MicPermission.isGranted(context)) {
                        voiceMode = true
                    } else {
                        permission.launch(MicPermission.PERMISSION)
                    }
                },
                enabled = streaming == null,
            ) {
                Icon(
                    if (voiceMode) Icons.Outlined.Close else Icons.Outlined.Mic,
                    contentDescription = if (voiceMode) "Exit voice recording" else "Record a voice message",
                    tint = MemoTheme.colors.inkMuted,
                )
            }
            MemoButton(
                onClick = if (streaming == null) controller::send else controller::stop,
                enabled = !voiceMode && (streaming != null || controller.composer.canSend),
                style = MemoButtonStyle.PRIMARY,
            ) {
                Icon(
                    if (streaming == null) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Stop,
                    contentDescription = if (streaming == null) "Send message" else "Stop response",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(MemoTheme.spacing.space2))
        Text(
            "Your chats stay on this device.",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MemoTheme.typography.caption,
            color = MemoTheme.colors.inkFaint,
        )
    }
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream(limit)
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
