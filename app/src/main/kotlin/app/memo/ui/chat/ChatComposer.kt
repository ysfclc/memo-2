package app.memo.ui.chat

import android.database.Cursor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.memo.core.chat.ChatLimits
import app.memo.runtime.ChatService
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.theme.MemoTheme
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
fun ChatComposer(controller: ChatController, streaming: ChatService.Streaming?) {
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

    Column(Modifier.fillMaxWidth().padding(horizontal = MemoTheme.spacing.space5, vertical = MemoTheme.spacing.space3)) {
        controller.composer.attachment?.let { attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(attachment.name, modifier = Modifier.weight(1f), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                IconButton(onClick = controller::removeAttachment) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove attachment", modifier = Modifier.size(18.dp))
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
            IconButton(onClick = { picker.launch(arrayOf("text/*", "application/json")) }, enabled = streaming == null) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "Attach text file", tint = MemoTheme.colors.inkMuted)
            }
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
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Outlined.Mic, contentDescription = "Local voice arrives with M2", tint = MemoTheme.colors.inkFaint)
            }
            MemoButton(
                onClick = if (streaming == null) controller::send else controller::stop,
                enabled = streaming != null || controller.composer.canSend,
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
