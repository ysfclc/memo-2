package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The voice-message storage shape (M2.B1): [ChatMessage] construction rules for the voice
 * fields, the format-2 codec roundtrip, and the legacy readability guarantee — format-1
 * entries decode exactly as they were written and stay text-only.
 */
class VoiceMessageStorageTest {

    private val conversation = Conversation(
        id = "a1b2c3d4e5",
        title = "Voice chat",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
        modelId = null,
    )

    private val voiceMessage = ChatMessage(
        id = "m0000009",
        role = ChatRole.USER,
        text = "",
        voiceClipRef = "20261030-090000-123",
        durationMs = 42_000,
        transcript = "Tomorrow you have the review at nine.",
        createdAt = 1_700_000_000_500,
        status = MessageStatus.DONE,
    )

    // ------------------------------------------------ model rules

    @Test
    fun voiceFieldsRequireAClipReference() {
        fun withoutClip(
            durationMs: Long? = null,
            transcript: String? = null,
            transcriptNote: String? = null,
        ): ChatMessage = ChatMessage(
            id = "m0000009",
            role = ChatRole.USER,
            text = "",
            voiceClipRef = null,
            durationMs = durationMs,
            transcript = transcript,
            transcriptNote = transcriptNote,
            createdAt = 1_700_000_000_500,
        )
        assertFailsWith<IllegalArgumentException> { withoutClip(durationMs = 1_000) }
        assertFailsWith<IllegalArgumentException> { withoutClip(transcript = "spilled") }
        assertFailsWith<IllegalArgumentException> { withoutClip(transcriptNote = "note") }
    }

    @Test
    fun validVoiceFieldsSpanTheRecordedDomain() {
        // The cap itself is a valid recording (the recorder caps at exactly five minutes).
        val atCap = voiceMessage.copy(durationMs = ChatLimits.MAX_VOICE_DURATION_MS.toLong())
        assertEquals(ChatLimits.MAX_VOICE_DURATION_MS.toLong(), atCap.durationMs)
        // A text-only message needs none of the voice fields — the default path stays null.
        val textOnly = ChatMessage(id = "m0000010", role = ChatRole.USER, text = "typed", createdAt = 1)
        assertNull(textOnly.voiceClipRef)
        assertNull(textOnly.durationMs)
        assertNull(textOnly.transcript)
        assertNull(textOnly.transcriptNote)
    }

    @Test
    fun voiceFieldLimitsAreEnforced() {
        assertFailsWith<IllegalArgumentException> { voiceMessage.copy(durationMs = 0L) }
        assertFailsWith<IllegalArgumentException> {
            voiceMessage.copy(durationMs = (ChatLimits.MAX_VOICE_DURATION_MS + 1).toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            voiceMessage.copy(transcript = "x".repeat(ChatLimits.MAX_TRANSCRIPT_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            voiceMessage.copy(transcript = null, transcriptNote = "x".repeat(ChatLimits.MAX_TRANSCRIPT_NOTE_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            voiceMessage.copy(transcriptNote = "engine not installed")
        }
    }

    @Test
    fun clipReferencesCannotEscapeTheVoiceDirectory() {
        listOf("../w.wav", "sub/dir/x.wav", "C:\\data.wav", "..", ".", "\u0000.wav").forEach { ref ->
            assertFailsWith<IllegalArgumentException>("ref must be refused: $ref") { voiceMessage.copy(voiceClipRef = ref) }
        }
        assertFailsWith<IllegalArgumentException> {
            voiceMessage.copy(voiceClipRef = "x".repeat(ChatLimits.MAX_VOICE_REF_CHARS + 1))
        }
        // The maximum length stays a valid bare file name.
        voiceMessage.copy(voiceClipRef = "x".repeat(ChatLimits.MAX_VOICE_REF_CHARS))
    }

    // ------------------------------------------------ format 2 codec

    @Test
    fun transcribedVoiceMessageRoundTrips() {
        val decoded = ConversationCodec.decodeDoc(ConversationCodec.encodeDoc(ConversationDoc(conversation, listOf(voiceMessage))))
        assertEquals(ConversationDoc(conversation, listOf(voiceMessage)), decoded)
        assertNull(decoded.messages[0].transcriptNote)
    }

    @Test
    fun untranscribedVoiceMessageRoundTripsWithItsNote() {
        val untranscribed = voiceMessage.copy(transcript = null, transcriptNote = "the Whisper model is not installed")
        val decoded = ConversationCodec.decodeDoc(
            ConversationCodec.encodeDoc(ConversationDoc(conversation, listOf(untranscribed))),
        )
        assertEquals(untranscribed, decoded.messages.single())
    }

    @Test
    fun silentVoiceMessageKeepsItsEmptyTranscript() {
        val message = voiceMessage.copy(transcript = "")
        val decoded = ConversationCodec.decodeDoc(
            ConversationCodec.encodeDoc(ConversationDoc(conversation, listOf(message))),
        )
        assertEquals("", decoded.messages.single().transcript)
    }

    // ------------------------------------------------ legacy readability

    /**
     * A conversation entry exactly as format 1 wrote it. The decoded content must be identical,
     * so stored text renders the same as it did before voice messages existed
     * ([app.memo.runtime.ChatRepositoryTest] mirrors this through the real storage path).
     */
    @Test
    fun legacyVersionOneEntryDecodesUnchanged() {
        val legacy =
            "{\"v\":1," +
                "\"conversation\":{\"id\":\"a1b2c3d4e5\",\"title\":\"Old chat\",\"createdAt\":1700000000000," +
                "\"updatedAt\":1700000100000,\"modelId\":\"qwen-small\",\"pinned\":false,\"archived\":false}," +
                "\"messages\":[" +
                "{\"id\":\"m1111111\",\"role\":\"USER\",\"text\":\"hello\",\"thinking\":\"\",\"createdAt\":1700000001000," +
                "\"status\":\"DONE\",\"errorText\":\"\"}," +
                "{\"id\":\"m2222222\",\"role\":\"ASSISTANT\",\"text\":\"hi\",\"thinking\":\"thoughts\"," +
                "\"modelId\":\"qwen-small\",\"createdAt\":1700000002000,\"status\":\"STOPPED\",\"errorText\":\"\"}]}"
        val decoded = ConversationCodec.decodeDoc(legacy)
        assertEquals(
            listOf(
                ChatMessage(id = "m1111111", role = ChatRole.USER, text = "hello", createdAt = 1_700_000_001_000),
                ChatMessage(
                    id = "m2222222",
                    role = ChatRole.ASSISTANT,
                    text = "hi",
                    thinking = "thoughts",
                    modelId = "qwen-small",
                    createdAt = 1_700_000_002_000,
                    status = MessageStatus.STOPPED,
                ),
            ),
            decoded.messages,
        )
        assertNull(decoded.messages[0].voiceClipRef)
        assertNull(decoded.messages[1].durationMs)
        assertNull(decoded.messages[1].transcript)
        assertNull(decoded.messages[1].transcriptNote)
    }

    @Test
    fun legacyIndexDecodesUnchanged() {
        val legacy =
            "{\"v\":1,\"conversations\":[{\"id\":\"a1b2c3d4e5\",\"title\":\"Old chat\"," +
                "\"createdAt\":1700000000000,\"updatedAt\":1700000100000,\"modelId\":null," +
                "\"pinned\":false,\"archived\":false}]}"
        val decoded = ConversationCodec.decodeIndex(legacy)
        assertEquals(
            listOf(
                Conversation(
                    id = "a1b2c3d4e5",
                    title = "Old chat",
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_100_000,
                    modelId = null,
                ),
            ),
            decoded,
        )
    }

    @Test
    fun v1EntryMayNotCarryVoiceFields() {
        val smuggled =
            "{\"v\":1," +
                "\"conversation\":{\"id\":\"a1b2c3d4e5\",\"title\":\"t\",\"createdAt\":1700000000000," +
                "\"updatedAt\":1700000100000,\"modelId\":null,\"pinned\":false,\"archived\":false}," +
                "\"messages\":[" +
                "{\"id\":\"m1111111\",\"role\":\"USER\",\"text\":\"\",\"thinking\":\"\",\"voiceClipRef\":\"clip-1\"," +
                "\"durationMs\":1000,\"createdAt\":1700000001000,\"status\":\"DONE\",\"errorText\":\"\"}]}"
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(smuggled) }
    }
}
