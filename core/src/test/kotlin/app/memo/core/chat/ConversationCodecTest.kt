package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConversationCodecTest {

    private val conversation = Conversation(
        id = "a1b2c3d4e5",
        title = "Plan the trip",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
        modelId = "qwen-small",
        pinned = true,
        archived = false,
    )

    private val messages = listOf(
        ChatMessage(
            id = "m0000001",
            role = ChatRole.USER,
            text = "Plan the trip \"quoted\" \n with newline and ünïcödé 🙂",
            createdAt = 1_700_000_000_001,
        ),
        ChatMessage(
            id = "m0000002",
            role = ChatRole.ASSISTANT,
            text = "Sure.",
            thinking = "reasoning here",
            modelId = "qwen-small",
            voiceClipRef = "clip-9",
            createdAt = 1_700_000_000_002,
            status = MessageStatus.STOPPED,
        ),
        ChatMessage(
            id = "m0000003",
            role = ChatRole.ASSISTANT,
            text = "",
            createdAt = 1_700_000_000_003,
            status = MessageStatus.ERROR,
            errorText = "out of memory",
        ),
    )

    @Test
    fun documentRoundTripsExactly() {
        val doc = ConversationDoc(conversation, messages)
        val decoded = ConversationCodec.decodeDoc(ConversationCodec.encodeDoc(doc))
        assertEquals(doc, decoded)
    }

    @Test
    fun indexRoundTripsExactly() {
        val other = conversation.copy(id = "ffeeddcc", modelId = null, archived = true)
        val text = ConversationCodec.encodeIndex(listOf(conversation, other))
        assertEquals(listOf(conversation, other), ConversationCodec.decodeIndex(text))
    }

    @Test
    fun nullOptionalFieldsRoundTripAsNull() {
        val doc = ConversationDoc(conversation.copy(modelId = null), listOf(messages[0]))
        val decoded = ConversationCodec.decodeDoc(ConversationCodec.encodeDoc(doc))
        assertNull(decoded.conversation.modelId)
        assertNull(decoded.messages[0].modelId)
    }

    @Test
    fun rejectsFutureVersion() {
        val text = ConversationCodec.encodeDoc(ConversationDoc(conversation, emptyList()))
            .replace("\"v\":2", "\"v\":3")
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(text) }
    }

    @Test
    fun rejectsUnknownFields() {
        val text = ConversationCodec.encodeDoc(ConversationDoc(conversation, emptyList()))
            .replace("\"v\":2", "\"v\":2,\"extra\":true")
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(text) }
    }

    @Test
    fun rejectsWrongTypes() {
        val text = ConversationCodec.encodeDoc(ConversationDoc(conversation, emptyList()))
            .replace("\"pinned\":true", "\"pinned\":\"yes\"")
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(text) }
    }

    @Test
    fun rejectsUnknownEnumValues() {
        val text = ConversationCodec.encodeDoc(ConversationDoc(conversation, messages))
            .replace("\"status\":\"STOPPED\"", "\"status\":\"PAUSED\"")
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(text) }
    }

    @Test
    fun rejectsMissingRequiredField() {
        val text = ConversationCodec.encodeDoc(ConversationDoc(conversation, emptyList()))
            .replace("\"title\":\"Plan the trip\",", "")
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc(text) }
    }

    @Test
    fun rejectsNotJson() {
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeDoc("{not json") }
        assertFailsWith<ChatCodecException> { ConversationCodec.decodeIndex("") }
    }

    @Test
    fun rejectsOversizedEntry() {
        val huge = messages[0].copy(text = "x".repeat(ChatLimits.MAX_MESSAGE_CHARS))
        val bigDoc = ConversationDoc(conversation, List(90) { i -> huge.copy(id = "big%05d".format(i)) })
        assertFailsWith<ChatCodecException> { ConversationCodec.encodeDoc(bigDoc) }
    }
}
