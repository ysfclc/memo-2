package app.memo.core.chat

/** Hard bounds for stored conversations. Writes above these are refused, never truncated. */
object ChatLimits {
    const val MAX_TITLE_CHARS: Int = 120
    const val GENERATED_TITLE_CHARS: Int = 48
    const val MAX_MESSAGE_CHARS: Int = 200_000
    const val MAX_THINKING_CHARS: Int = 200_000
    const val MAX_ERROR_CHARS: Int = 500
    const val MAX_MESSAGES_PER_CONVERSATION: Int = 2_000
    const val MAX_CONVERSATIONS: Int = 500
    const val MAX_ENTRY_BYTES: Int = 16 * 1024 * 1024
    const val MAX_MODEL_ID_CHARS: Int = 64
    const val MAX_VOICE_REF_CHARS: Int = 128

    /** Recorded voice messages are capped at five minutes by the hold-to-record recorder. */
    const val MAX_VOICE_DURATION_MS: Int = 300_000

    /** Whisper output for a five-minute clip stays far below this. */
    const val MAX_TRANSCRIPT_CHARS: Int = 20_000

    /** One line, plain-language reason why a clip has no transcript. */
    const val MAX_TRANSCRIPT_NOTE_CHARS: Int = 200

    /** Attached text files larger than this are refused by the UI. */
    const val MAX_ATTACHMENT_BYTES: Int = 200 * 1024
}

/**
 * Voice-clip references are always bare file names inside the app-private
 * `filesDir/voice-messages/` directory ([app.memo.platform.android.voice.VoiceMessageStore]).
 * [isFilenameSafe] is the join guard: references containing separators or `.`/`..` could point
 * the player outside that directory, so the [ChatMessage] constructor refuses them.
 */
object VoiceClipRefs {
    fun isFilenameSafe(ref: String): Boolean =
        ref.isNotEmpty() && ref != "." && ref != ".." &&
            ref.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
}

/** Who wrote a message. */
enum class ChatRole { USER, ASSISTANT, SYSTEM }

/** Lifecycle of a message. Only [STREAMING] messages can still change. */
enum class MessageStatus { STREAMING, DONE, STOPPED, ERROR }

/** Conversation metadata. Messages live in the same vault entry; the index keeps only this. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val pinned: Boolean = false,
    val archived: Boolean = false,
) {
    init {
        require(ChatIds.PATTERN.matches(id)) { "invalid conversation id" }
        require(title.length <= ChatLimits.MAX_TITLE_CHARS) { "title too long" }
        require(createdAt >= 0 && updatedAt >= 0) { "timestamps must be >= 0" }
        require(modelId == null || (modelId.isNotBlank() && modelId.length <= ChatLimits.MAX_MODEL_ID_CHARS)) {
            "invalid model id"
        }
    }
}

/**
 * One message. [thinking] is the reasoning channel text, empty when the model had none.
 *
 * A voice message carries its audio by [voiceClipRef] (the WAV file name under
 * `filesDir/voice-messages`, kept app-private) and [VoiceClipRefs.isFilenameSafe] is checked
 * against it so the player can join the path safely. [durationMs] is the clip length;
 * [transcript] is the Whisper output — nullable because transcription can fail or be missing.
 * When [transcript] is null, [transcriptNote] holds the plain-language reason (nothing stored
 * when no reason is known). An empty [transcript] means it was transcribed and heard nothing.
 * All voice fields are forbidden without [voiceClipRef]: they describe the clip, not the text.
 * Text-only messages written by older builds keep exactly `voiceClipRef = null` and the
 * defaults, so stored data still reads and renders exactly as before.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val thinking: String = "",
    val modelId: String? = null,
    val voiceClipRef: String? = null,
    val durationMs: Long? = null,
    val transcript: String? = null,
    val transcriptNote: String? = null,
    val createdAt: Long,
    val status: MessageStatus = MessageStatus.DONE,
    val errorText: String = "",
) {
    init {
        require(ChatIds.PATTERN.matches(id)) { "invalid message id" }
        require(text.length <= ChatLimits.MAX_MESSAGE_CHARS) { "message too long" }
        require(thinking.length <= ChatLimits.MAX_THINKING_CHARS) { "thinking too long" }
        require(errorText.length <= ChatLimits.MAX_ERROR_CHARS) { "error text too long" }
        require(errorText.isEmpty() || status == MessageStatus.ERROR) { "errorText requires ERROR status" }
        require(createdAt >= 0) { "createdAt must be >= 0" }
        require(modelId == null || modelId.length <= ChatLimits.MAX_MODEL_ID_CHARS) { "invalid model id" }
        require(voiceClipRef == null || VoiceClipRefs.isFilenameSafe(voiceClipRef)) { "invalid voice ref" }
        require(voiceClipRef == null || voiceClipRef.length <= ChatLimits.MAX_VOICE_REF_CHARS) { "invalid voice ref" }
        require(voiceClipRef != null || (durationMs == null && transcript == null && transcriptNote == null)) {
            "voice fields require a voiceClipRef"
        }
        require(durationMs == null || durationMs in 1..ChatLimits.MAX_VOICE_DURATION_MS) { "invalid duration" }
        require(transcript == null || transcript.length <= ChatLimits.MAX_TRANSCRIPT_CHARS) { "transcript too long" }
        require(transcriptNote == null || transcriptNote.length <= ChatLimits.MAX_TRANSCRIPT_NOTE_CHARS) {
            "transcript note too long"
        }
        require(transcript == null || transcriptNote == null) { "a transcribed clip cannot also carry a note" }
    }
}

/** A conversation with its full message list, as stored in one vault entry. */
data class ConversationDoc(val conversation: Conversation, val messages: List<ChatMessage>) {
    init {
        require(messages.size <= ChatLimits.MAX_MESSAGES_PER_CONVERSATION) { "too many messages" }
        require(messages.map { it.id }.toSet().size == messages.size) { "duplicate message ids" }
    }
}

/** Identifier shape shared by conversations and messages: lowercase hex, 8 to 40 characters. */
object ChatIds {
    val PATTERN = Regex("^[a-z0-9]{8,40}$")

    /** Vault entry name for a conversation. */
    fun vaultName(conversationId: String): String {
        require(PATTERN.matches(conversationId)) { "invalid conversation id" }
        return "chat_$conversationId"
    }
}

/** Pinned first, then most recently updated first. Ties break on id so the order is total. */
object ConversationOrdering {
    fun sorted(conversations: Collection<Conversation>): List<Conversation> =
        conversations.sortedWith(
            compareByDescending<Conversation> { it.pinned }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        )

    /** The list the drawer shows: archived conversations only when [showArchived] is set. */
    fun visible(conversations: Collection<Conversation>, showArchived: Boolean): List<Conversation> =
        sorted(conversations.filter { showArchived || !it.archived })
}
