package app.memo.core.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Raised when stored chat data does not match the format. Never carries message text. */
class ChatCodecException(message: String) : RuntimeException(message)

/**
 * JSON codec for conversation entries and the conversation index (org.json, strict).
 *
 * Format version 2 adds voice-message fields on `ChatMessage` ([durationMs], [transcript],
 * [transcriptNote]); format version 3 adds [ChatMessage.runId] (a SYSTEM message's workflow run
 * reference). All optional and omitted when null. It is backward compatible by construction:
 * version **1 entries decode in the identical shape they were written in** — the reader accepts
 * 1, 2 and 3; a v1 entry may not carry the v2 keys, a v1 or v2 entry may not carry `runId`, and
 * older entries round-trip through decode/encode unchanged in meaning (an encode rewrites them
 * tagged v3). Parsing is strict: the version must be 1, 2 or 3, every required field must be
 * present with the right JSON type, unknown fields are rejected, enums must match exactly, and
 * every [ChatLimits] bound is checked through the model constructors. Nullable fields are omitted
 * when null.
 */
object ConversationCodec {
    const val VERSION: Int = 3
    private const val LEGACY_VERSION: Int = 1

    private val CONVERSATION_KEYS = setOf("id", "title", "createdAt", "updatedAt", "modelId", "pinned", "archived")
    private val MESSAGE_KEYS = setOf(
        "id", "role", "text", "thinking", "modelId", "voiceClipRef", "durationMs", "transcript", "transcriptNote",
        "runId", "createdAt", "status", "errorText",
    )

    /** The keys only format 2 writes; a v1 entry must not carry them. */
    private val VERSION_2_MESSAGE_KEYS = setOf("durationMs", "transcript", "transcriptNote")

    /** The keys only format 3 writes; a v1 or v2 entry must not carry them. */
    private val VERSION_3_MESSAGE_KEYS = setOf("runId")

    // ------------------------------------------------------------ encode

    fun encodeDoc(doc: ConversationDoc): String {
        val messages = JSONArray()
        doc.messages.forEach { messages.put(messageToJson(it)) }
        val root = JSONObject()
            .put("v", VERSION)
            .put("conversation", conversationToJson(doc.conversation))
            .put("messages", messages)
        return checkedSize(root.toString())
    }

    fun encodeIndex(conversations: Collection<Conversation>): String {
        val array = JSONArray()
        conversations.forEach { array.put(conversationToJson(it)) }
        val root = JSONObject().put("v", VERSION).put("conversations", array)
        return checkedSize(root.toString())
    }

    // ------------------------------------------------------------ decode

    fun decodeDoc(text: String): ConversationDoc {
        val root = parseRoot(text, setOf("v", "conversation", "messages"))
        val version = root.version()
        val conversation = conversationFromJson(root.objectField("conversation"))
        val array = root.arrayField("messages")
        if (array.length() > ChatLimits.MAX_MESSAGES_PER_CONVERSATION) throw ChatCodecException("too many messages")
        val messages = (0 until array.length()).map {
            messageFromJson(array.optJSONObject(it) ?: fail("message must be an object"), version)
        }
        return try {
            ConversationDoc(conversation, messages)
        } catch (e: IllegalArgumentException) {
            throw ChatCodecException("invalid conversation document: ${e.message}")
        }
    }

    fun decodeIndex(text: String): List<Conversation> {
        val root = parseRoot(text, setOf("v", "conversations"))
        val array = root.arrayField("conversations")
        if (array.length() > ChatLimits.MAX_CONVERSATIONS) throw ChatCodecException("too many conversations")
        return (0 until array.length()).map { conversationFromJson(array.optJSONObject(it) ?: fail("entry must be an object")) }
    }

    // ------------------------------------------------------------ helpers

    private fun conversationToJson(c: Conversation): JSONObject = JSONObject()
        .put("id", c.id)
        .put("title", c.title)
        .put("createdAt", c.createdAt)
        .put("updatedAt", c.updatedAt)
        .putOpt("modelId", c.modelId)
        .put("pinned", c.pinned)
        .put("archived", c.archived)

    private fun messageToJson(m: ChatMessage): JSONObject = JSONObject()
        .put("id", m.id)
        .put("role", m.role.name)
        .put("text", m.text)
        .put("thinking", m.thinking)
        .putOpt("modelId", m.modelId)
        .putOpt("voiceClipRef", m.voiceClipRef)
        .putOpt("durationMs", m.durationMs)
        .putOpt("transcript", m.transcript)
        .putOpt("transcriptNote", m.transcriptNote)
        .putOpt("runId", m.runId)
        .put("createdAt", m.createdAt)
        .put("status", m.status.name)
        .put("errorText", m.errorText)

    private fun conversationFromJson(o: JSONObject): Conversation {
        o.exact(CONVERSATION_KEYS, required = setOf("id", "title", "createdAt", "updatedAt", "pinned", "archived"))
        return try {
            Conversation(
                id = o.str("id"),
                title = o.str("title"),
                createdAt = o.long("createdAt"),
                updatedAt = o.long("updatedAt"),
                modelId = o.optStrOrNull("modelId"),
                pinned = o.bool("pinned"),
                archived = o.bool("archived"),
            )
        } catch (e: IllegalArgumentException) {
            throw ChatCodecException("invalid conversation: ${e.message}")
        }
    }

    private fun messageFromJson(o: JSONObject, version: Int): ChatMessage {
        o.exact(MESSAGE_KEYS, required = setOf("id", "role", "text", "thinking", "createdAt", "status", "errorText"))
        if (version < 2) {
            VERSION_2_MESSAGE_KEYS.forEach { key -> if (o.has(key)) fail("field $key needs format version 2") }
        }
        if (version < 3) {
            VERSION_3_MESSAGE_KEYS.forEach { key -> if (o.has(key)) fail("field $key needs format version 3") }
        }
        return try {
            ChatMessage(
                id = o.str("id"),
                role = enumNamed<ChatRole>(o.str("role")),
                text = o.str("text"),
                thinking = o.str("thinking"),
                modelId = o.optStrOrNull("modelId"),
                voiceClipRef = o.optStrOrNull("voiceClipRef"),
                durationMs = o.optLongOrNull("durationMs"),
                transcript = o.optStrOrNull("transcript"),
                transcriptNote = o.optStrOrNull("transcriptNote"),
                runId = o.optStrOrNull("runId"),
                createdAt = o.long("createdAt"),
                status = enumNamed<MessageStatus>(o.str("status")),
                errorText = o.str("errorText"),
            )
        } catch (e: IllegalArgumentException) {
            throw ChatCodecException("invalid message: ${e.message}")
        }
    }

    private inline fun <reified E : Enum<E>> enumNamed(name: String): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fail("unknown ${E::class.simpleName} value")

    private fun parseRoot(text: String, keys: Set<String>): JSONObject {
        if (text.length > ChatLimits.MAX_ENTRY_BYTES) throw ChatCodecException("entry too large")
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ChatCodecException("entry is not valid JSON")
        }
        root.exact(keys + "v", required = keys + "v")
        root.version()
        return root
    }

    private fun JSONObject.version(): Int {
        val value = get("v")
        if (value !is Int && value !is Long) fail("field must be an integer")
        val version = (value as Number).toInt()
        // Legacy v1 entries where written by older builds: legible forever, never re-tagged.
        if (version !in LEGACY_VERSION..VERSION) fail("unsupported format version")
        return version
    }

    private fun checkedSize(json: String): String {
        if (json.toByteArray(Charsets.UTF_8).size > ChatLimits.MAX_ENTRY_BYTES) {
            throw ChatCodecException("entry exceeds ${ChatLimits.MAX_ENTRY_BYTES} bytes; not written")
        }
        return json
    }

    private fun JSONObject.exact(allowed: Set<String>, required: Set<String>) {
        keys().forEach { key -> if (key !in allowed) fail("unknown field") }
        required.forEach { key -> if (!has(key) || isNull(key)) fail("missing field") }
    }

    private fun JSONObject.str(key: String): String = (get(key) as? String) ?: fail("field must be a string")

    private fun JSONObject.optStrOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return str(key)
    }

    private fun JSONObject.bool(key: String): Boolean = (get(key) as? Boolean) ?: fail("field must be a boolean")

    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        if (value !is Int && value !is Long) fail("field must be an integer")
        return (value as Number).toLong()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        if (value !is Int && value !is Long) fail("field must be an integer")
        return (value as Number).toLong()
    }

    private fun JSONObject.objectField(key: String): JSONObject =
        optJSONObject(key) ?: fail("field must be an object")

    private fun JSONObject.arrayField(key: String): JSONArray =
        optJSONArray(key) ?: fail("field must be an array")

    private fun fail(reason: String): Nothing = throw ChatCodecException(reason)
}
