package app.memo.runtime

import app.memo.core.chat.ChatCodecException
import app.memo.core.chat.ChatIds
import app.memo.core.chat.ChatLimits
import app.memo.core.chat.Conversation
import app.memo.core.chat.ConversationCodec
import app.memo.core.chat.ConversationDoc
import app.memo.core.chat.ConversationOrdering
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Conversation storage over the encrypted [Vault]: one entry per conversation (`chat_<id>`)
 * plus one index entry (`chat_index`) with the conversation metadata.
 *
 * - **Writes** run on one background thread, in submission order. [save] and [delete] return
 *   at once; [onResult] is called on that thread with null on success or the failure.
 * - **Reads** ([load], [readDoc]) also go through that thread, so they see every queued write.
 *   They block, so call them off the main thread.
 * - **Corruption is reported, never overwritten.** An entry that fails to read or decode is
 *   listed in [problems] and blocked: [save] for that id is refused. [delete] stays available
 *   as an explicit user action. A corrupt index is reported and left on disk; the list is then
 *   rebuilt from the entries, and the index is not written until a later load reads it cleanly.
 * - **Limits.** Conversations above [ChatLimits.MAX_CONVERSATIONS] are refused, and so are
 *   entries above the codec limits. Nothing is truncated.
 */
class ChatRepository(private val vault: Vault) : AutoCloseable {

    /** The conversation list plus any problems found while building it. */
    data class Loaded(val conversations: List<Conversation>, val problems: List<String>)

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "memo-chat-store").apply { isDaemon = true } }

    // Guarded by the writer thread.
    private val index = LinkedHashMap<String, Conversation>()
    private val blocked = HashSet<String>()
    private val problemList = ArrayList<String>()
    private var indexWritable = true

    @Volatile
    private var problemsSnapshot: List<String> = emptyList()

    /** Problems found by the last [load]. Empty when everything read cleanly. */
    fun problems(): List<String> = problemsSnapshot

    /** Reads the index, or rebuilds the list from the entries when the index is missing or corrupt. Blocking. */
    fun load(): Loaded = onWriter { loadOnWriter() }

    /** Reads one conversation with its messages. Null when no such entry exists. Blocking. Throws on corruption. */
    fun readDoc(conversationId: String): ConversationDoc? = onWriter { readDocOnWriter(conversationId) }

    /** Stores [doc] in the background. Refused for blocked (corrupt) ids and over the conversation limit. */
    fun save(doc: ConversationDoc, onResult: (Throwable?) -> Unit = {}) {
        submitAsync(onResult) { saveOnWriter(doc) }
    }

    /** Removes a conversation and its entry in the background. Allowed even for corrupt entries. */
    fun delete(conversationId: String, onResult: (Throwable?) -> Unit = {}) {
        submitAsync(onResult) { deleteOnWriter(conversationId) }
    }

    /** Drafts by key (a conversation id, or [NEW_CHAT_DRAFT] for an unsaved chat). Blocking. */
    fun loadDrafts(): Map<String, String> = onWriter { readDraftsOnWriter() }

    /** Stores one draft in the background. An empty [text] removes the draft. */
    fun saveDraft(key: String, text: String, onResult: (Throwable?) -> Unit = {}) {
        submitAsync(onResult) { saveDraftOnWriter(key, text) }
    }

    /** Waits for queued writes, then stops the writer thread. */
    override fun close() {
        writer.shutdown()
        writer.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ writer thread

    private fun loadOnWriter(): Loaded {
        index.clear()
        blocked.clear()
        problemList.clear()
        indexWritable = true

        // Only conversation entries: the index and the drafts entry share the prefix.
        val onDisk = vault.list(ENTRY_PREFIX)
            .map { it.removePrefix(ENTRY_PREFIX) }
            .filter { ChatIds.PATTERN.matches(it) }
            .toSet()

        val fromIndex: List<Conversation>? = try {
            vault.read(INDEX_NAME)?.let { ConversationCodec.decodeIndex(it.toString(Charsets.UTF_8)) }
        } catch (e: VaultException) {
            reportIndexProblem("index could not be read")
            null
        } catch (e: ChatCodecException) {
            reportIndexProblem("index is not valid")
            null
        }

        if (fromIndex != null) {
            fromIndex.filter { it.id in onDisk }.forEach { index[it.id] = it }
            // Entries the index does not know about are added by scanning them.
            (onDisk - index.keys).forEach { id -> scanOne(id) }
        } else {
            onDisk.forEach { id -> scanOne(id) }
        }

        publishProblems()
        return Loaded(ConversationOrdering.sorted(index.values), problemList.toList())
    }

    private fun scanOne(id: String) {
        try {
            val doc = readDocOnWriter(id)
            if (doc != null) index[id] = doc.conversation
        } catch (e: VaultException) {
            // Already reported and blocked by readDocOnWriter.
        } catch (e: ChatCodecException) {
            // Already reported and blocked by readDocOnWriter.
        }
    }

    private fun reportIndexProblem(reason: String) {
        indexWritable = false
        problemList += "conversation list $reason; rebuilt from entries and the index was kept"
    }

    /**
     * Reads and decodes one entry. A corrupt entry is blocked and reported, then the error is
     * rethrown, so the caller can show it. Nothing is written for it afterwards.
     */
    private fun readDocOnWriter(conversationId: String): ConversationDoc? {
        val name = ChatIds.vaultName(conversationId)
        try {
            val bytes = vault.read(name) ?: return null
            return ConversationCodec.decodeDoc(bytes.toString(Charsets.UTF_8))
        } catch (e: VaultException) {
            blockCorrupt(conversationId, "could not be read")
            throw e
        } catch (e: ChatCodecException) {
            blockCorrupt(conversationId, "is not valid")
            throw e
        }
    }

    private fun blockCorrupt(conversationId: String, reason: String) {
        blocked += conversationId
        val message = "conversation $conversationId $reason; it is kept and not overwritten"
        if (message !in problemList) problemList += message
        publishProblems()
    }

    private fun saveOnWriter(doc: ConversationDoc) {
        val id = doc.conversation.id
        if (id in blocked) {
            throw VaultException("conversation $id is corrupt and was not overwritten")
        }
        if (!index.containsKey(id) && index.size >= ChatLimits.MAX_CONVERSATIONS) {
            throw IllegalStateException("conversation limit reached")
        }
        val encoded = ConversationCodec.encodeDoc(doc)
        vault.write(ChatIds.vaultName(id), encoded.toByteArray(Charsets.UTF_8))
        index[id] = doc.conversation
        writeIndex()
    }

    private fun deleteOnWriter(conversationId: String) {
        vault.delete(ChatIds.vaultName(conversationId))
        index.remove(conversationId)
        blocked.remove(conversationId)
        writeIndex()
        publishProblems()
    }

    private var draftsBlocked = false

    private fun readDraftsOnWriter(): Map<String, String> {
        val bytes = try {
            vault.read(DRAFTS_NAME)
        } catch (e: VaultException) {
            blockDrafts("could not be read")
            return emptyMap()
        }
        bytes ?: return emptyMap()
        return try {
            val root = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            root.keys().asSequence().associateWith { root.getString(it) }
        } catch (e: org.json.JSONException) {
            blockDrafts("is not valid")
            emptyMap()
        }
    }

    private fun blockDrafts(reason: String) {
        draftsBlocked = true
        val message = "drafts $reason; they are kept and not overwritten"
        if (message !in problemList) problemList += message
        publishProblems()
    }

    private fun saveDraftOnWriter(key: String, text: String) {
        if (draftsBlocked) throw VaultException("drafts entry is corrupt and was not overwritten")
        val drafts = LinkedHashMap(readDraftsOnWriter())
        if (text.isBlank()) drafts.remove(key) else drafts[key] = text.take(MAX_DRAFT_CHARS)
        if (drafts.size > MAX_DRAFTS) drafts.keys.take(drafts.size - MAX_DRAFTS).forEach { drafts.remove(it) }
        val json = org.json.JSONObject()
        drafts.forEach { (k, v) -> json.put(k, v) }
        vault.write(DRAFTS_NAME, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeIndex() {
        if (!indexWritable) return
        val encoded = ConversationCodec.encodeIndex(index.values)
        vault.write(INDEX_NAME, encoded.toByteArray(Charsets.UTF_8))
    }

    private fun publishProblems() {
        problemsSnapshot = problemList.toList()
    }

    // ------------------------------------------------------------ plumbing

    private fun <T> onWriter(block: () -> T): T {
        val future = writer.submit<T> { block() }
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun submitAsync(onResult: (Throwable?) -> Unit, block: () -> Unit) {
        writer.execute {
            val failure = try {
                block()
                null
            } catch (e: Throwable) {
                e
            }
            onResult(failure)
        }
    }

    companion object {
        /** Vault prefix of every conversation entry. */
        const val ENTRY_PREFIX = "chat_"

        /** Vault name of the conversation index. */
        const val INDEX_NAME = "chat_index"

        /** Vault name of the draft map (conversation id to draft text). */
        const val DRAFTS_NAME = "chat_drafts"

        /** Draft key for a chat that is not saved yet. */
        const val NEW_CHAT_DRAFT = "new"

        const val MAX_DRAFT_CHARS = 20_000
        const val MAX_DRAFTS = 50

        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private val random = SecureRandom()

        /** A new conversation or message id: 16 random bytes, lowercase hex (32 characters). */
        fun newId(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
