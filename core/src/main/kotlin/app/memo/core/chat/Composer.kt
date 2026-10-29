package app.memo.core.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A text file attached to the next message. [text] is the decoded content, at most [ChatLimits.MAX_ATTACHMENT_BYTES] bytes as UTF-8. */
data class Attachment(val name: String, val text: String)

/** Outcome of reading a file for attachment. */
sealed interface AttachmentResult {
    data class Ok(val attachment: Attachment) : AttachmentResult
    data class Refused(val reason: String) : AttachmentResult
}

object Attachments {
    const val MAX_NAME_CHARS: Int = 80

    /** Accepts UTF-8 text up to the size limit. Refuses binary content (NUL bytes) and invalid UTF-8. */
    fun fromBytes(name: String, bytes: ByteArray): AttachmentResult {
        if (bytes.size > ChatLimits.MAX_ATTACHMENT_BYTES) {
            return AttachmentResult.Refused("file is larger than 200 KB")
        }
        if (bytes.any { it == 0.toByte() }) {
            return AttachmentResult.Refused("not a text file")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return AttachmentResult.Refused("not UTF-8 text")
        }
        val clean = name.trim().ifEmpty { "attachment" }.take(MAX_NAME_CHARS)
        return AttachmentResult.Ok(Attachment(clean, text))
    }
}

/** What the single composer button does right now. */
enum class PrimaryAction { SEND, STOP, DISABLED }

/**
 * Composer state: draft text, one attachment, and whether a reply is streaming.
 * While generating, the primary action is STOP, and sending is not possible.
 */
data class ComposerState(
    val text: String = "",
    val attachment: Attachment? = null,
    val generating: Boolean = false,
) {
    val canSend: Boolean
        get() = !generating && (text.isNotBlank() || attachment != null)

    val primaryAction: PrimaryAction
        get() = when {
            generating -> PrimaryAction.STOP
            canSend -> PrimaryAction.SEND
            else -> PrimaryAction.DISABLED
        }

    /** Draft text, capped at [MAX_DRAFT_CHARS]. */
    fun withText(value: String): ComposerState = copy(text = value.take(MAX_DRAFT_CHARS))

    /** The message body sent to the model: the attachment in a fenced block, then the typed text. */
    fun outgoingText(): String {
        val typed = text.trim()
        val file = attachment ?: return typed
        val header = "File: ${file.name}\n```\n${file.text}\n```"
        return if (typed.isEmpty()) header else "$header\n\n$typed"
    }

    companion object {
        const val MAX_DRAFT_CHARS: Int = 20_000
    }
}
