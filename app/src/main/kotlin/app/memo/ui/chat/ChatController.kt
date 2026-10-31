package app.memo.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.core.chat.Attachment
import app.memo.core.chat.AttachmentResult
import app.memo.core.chat.Attachments
import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.ChatTitles
import app.memo.core.chat.Conversation
import app.memo.core.chat.ConversationDoc
import app.memo.core.chat.ConversationOrdering
import app.memo.core.chat.ComposerState
import app.memo.core.chat.ModelSettings
import app.memo.core.chat.MessageStatus
import app.memo.core.chat.VoiceClipDraft
import app.memo.core.plan.CommandGrammar
import app.memo.core.plan.Orchestrator
import app.memo.runtime.ChatRepository
import app.memo.runtime.ChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

/**
 * UI state and actions for the chat screen. Blocking storage calls run on [Dispatchers.IO];
 * state is written on the scope's (main) dispatcher.
 *
 * @param scope main-thread scope for the screen's lifetime.
 * @param installedModels the verified models the user can pick.
 * @param settingsFor reads the stored settings of a model (may block).
 * @param runCommand runs a planner command (`MemoRuntime.handleUtterance`) and returns its reply.
 * @param runPlan plans plan-intent text (`MemoRuntime.handlePlanRequest`); the reply arrives later through its callback.
 */
class ChatController(
    private val repo: ChatRepository,
    private val service: ChatService,
    private val scope: CoroutineScope,
    private val installedModels: () -> List<String>,
    private val settingsFor: (String) -> ModelSettings,
    private val runCommand: (String) -> String,
    private val modelEntryFor: (String) -> ModelEntry? = ModelCatalog::byId,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val runPlan: (text: String, modelId: String?, onReply: (String) -> Unit) -> Unit = { _, _, onReply ->
        onReply("Planning is not available.")
    },
) {
    /** A short action the snackbar offers to undo. */
    class Undo(val label: String, val action: () -> Unit)

    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var problems by mutableStateOf<List<String>>(emptyList())
        private set
    var query by mutableStateOf("")
    var showArchived by mutableStateOf(false)

    var doc by mutableStateOf<ConversationDoc?>(null)
        private set
    var composer by mutableStateOf(ComposerState())
        private set
    var modelId by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var undo by mutableStateOf<Undo?>(null)
        private set

    /** Conversations whose deletion is waiting for the undo window to end. */
    private val hidden = HashSet<String>()
    private var deleteJob: Job? = null
    private var draftJob: Job? = null
    private var drafts: Map<String, String> = emptyMap()
    private var textHits by mutableStateOf<Map<String, List<ChatMessage>>>(emptyMap())

    /** The conversation key for drafts: its id, or [ChatRepository.NEW_CHAT_DRAFT]. */
    private val draftKey: String get() = doc?.conversation?.id ?: ChatRepository.NEW_CHAT_DRAFT

    /** Loads the list and the drafts. */
    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            drafts = withContext(Dispatchers.IO) { runCatching { repo.loadDrafts() }.getOrDefault(emptyMap()) }
            conversations = loaded.conversations.filter { it.id !in hidden }
            problems = loaded.problems
            textHits = emptyMap()
            if (doc == null && composer.text.isEmpty()) {
                composer = ComposerState().withText(drafts[ChatRepository.NEW_CHAT_DRAFT].orEmpty())
            }
        }
    }

    /** Opens a stored conversation. Any reply interrupted by a closed app is marked STOPPED first. */
    fun open(id: String) {
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching { repo.readDoc(id)?.let { service.recoverInterrupted(it) } }.getOrNull()
            }
            if (opened == null) {
                notice = "This conversation could not be opened. It is kept as it is."
                return@launch
            }
            doc = opened
            modelId = opened.conversation.modelId?.takeIf { it in installedModels() } ?: defaultModel()
            composer = ComposerState().withText(drafts[id].orEmpty())
            notice = null
        }
    }

    /** Starts an unsaved chat. It is stored when the first message is sent. */
    fun newChat() {
        doc = null
        modelId = defaultModel()
        composer = ComposerState().withText(drafts[ChatRepository.NEW_CHAT_DRAFT].orEmpty())
        notice = null
    }

    fun chooseDefaultIfAbsent() {
        if (modelId == null) modelId = defaultModel()
    }

    fun onTextChanged(value: String) {
        composer = composer.withText(value)
        saveDraftSoon()
    }

    fun setGenerating(generating: Boolean) {
        if (composer.generating != generating) composer = composer.copy(generating = generating)
    }

    fun attach(name: String, bytes: ByteArray) {
        when (val result = Attachments.fromBytes(name, bytes)) {
            is AttachmentResult.Ok -> composer = composer.copy(attachment = result.attachment)
            is AttachmentResult.Refused -> notice = "Could not attach $name: ${result.reason}."
        }
    }

    fun rejectAttachment(name: String, reason: String) {
        notice = "Could not attach $name: $reason."
    }

    fun removeAttachment() {
        composer = composer.copy(attachment = null)
    }

    /** Keeps a freshly recorded clip pending; it goes out with the next send. */
    fun attachVoiceClip(clip: VoiceClipDraft) {
        composer = composer.copy(voiceClip = clip)
    }

    fun removeVoiceClip() {
        composer = composer.copy(voiceClip = null)
    }

    fun send() {
        if (service.streaming.value != null || !composer.canSend) return
        val body = composer.outgoingText()
        val clip = composer.voiceClip
        composer = ComposerState()
        saveDraftNow(draftKey, "")
        submit(doc, body, clip)
    }

    /** Stops the reply in progress. Its partial text is kept as STOPPED. */
    fun stop() {
        service.stop()
    }

    /** Regenerates the last reply from the last user message. */
    fun regenerate() {
        val current = doc ?: return
        val lastUser = current.messages.indexOfLast { it.role == ChatRole.USER }
        if (lastUser < 0) return
        submit(current.copy(messages = current.messages.take(lastUser + 1)), body = null)
    }

    /** Puts a user message back into the composer's flow: it and everything after it are replaced by [text]. */
    fun editAndResend(messageId: String, text: String) {
        val current = doc ?: return
        val index = current.messages.indexOfFirst { it.id == messageId }
        if (index < 0 || current.messages[index].role != ChatRole.USER) return
        submit(current.copy(messages = current.messages.take(index)), body = text)
    }

    fun deleteMessage(messageId: String) {
        val current = doc ?: return
        val updated = current.copy(messages = current.messages.filter { it.id != messageId })
        doc = updated
        saveDoc(updated)
    }

    fun setModel(id: String) {
        modelId = id
        service.preload(id)
    }

    fun setPinned(conversation: Conversation, pinned: Boolean) = updateConversation(conversation) {
        it.copy(pinned = pinned)
    }

    fun setArchived(conversation: Conversation, archived: Boolean) {
        val previous = conversation.archived
        updateConversation(conversation) { it.copy(archived = archived) }
        undo = Undo(if (archived) "Archived" else "Restored") { setArchived(conversation.copy(archived = archived), previous) }
    }

    /** Hides the conversation now; it is deleted when the undo window ends. */
    fun delete(conversation: Conversation) {
        hidden += conversation.id
        conversations = conversations.filter { it.id != conversation.id }
        if (doc?.conversation?.id == conversation.id) doc = null
        deleteJob?.cancel()
        deleteJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            withContext(Dispatchers.IO) { repo.delete(conversation.id) }
            hidden -= conversation.id
            undo = null
        }
        undo = Undo("Conversation deleted") {
            deleteJob?.cancel()
            hidden -= conversation.id
            undo = null
            refresh()
        }
    }

    /** Runs the pending undo action, if any. */
    fun runUndo() {
        val action = undo ?: return
        undo = null
        action.action()
    }

    /** Sets the one-line notice the screen shows until the user dismisses it. */
    fun showNotice(text: String) {
        notice = text
    }

    fun dismissNotice() {
        notice = null
    }

    fun flushDraft() {
        draftJob?.cancel()
        saveDraftNow(draftKey, composer.text)
    }

    /** Visible list: search applied, archived hidden unless asked for. */
    fun visibleConversations(): List<Conversation> {
        val filtered = conversations.filter { c ->
            app.memo.core.chat.ChatSearch.matches(query, c, emptyList()) || matchesStoredText(c)
        }
        return ConversationOrdering.visible(filtered, showArchived)
    }

    // Messages of conversations found only by text are loaded lazily, so the list keeps a simple cache.
    private fun matchesStoredText(c: Conversation): Boolean {
        if (query.isBlank()) return false
        val messages = textHits[c.id] ?: return false
        return app.memo.core.chat.ChatSearch.matches(query, c, messages)
    }

    /** Loads message text for a text search over stored conversations. */
    fun indexForSearch() {
        if (query.isBlank()) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                conversations.associate { c -> c.id to runCatching { repo.readDoc(c.id)?.messages.orEmpty() }.getOrDefault(emptyList()) }
            }
            textHits = loaded
        }
    }

    // ---------------------------------------------------------------- internals

    private fun defaultModel(): String? = installedModels().firstOrNull()

    /**
     * Appends a user message to [base] (or starts a conversation) and generates a reply. A planner
     * command goes to the planner instead, and its reply is stored as a SYSTEM message.
     * [body] null regenerates from [base] as it is.
     */
    private fun submit(base: ConversationDoc?, body: String?, clip: VoiceClipDraft? = null) {
        val now = nowMs()
        val model = modelId
        val start = base ?: ConversationDoc(
            Conversation(
                id = ChatRepository.newId(),
                title = ChatTitles.fromFirstMessage(body.orEmpty()),
                createdAt = now,
                updatedAt = now,
                modelId = model,
            ),
            emptyList(),
        )
        val withUser = if (body == null) start else {
            val user = userMessageWithClip(ChatRepository.newId(), body, now, clip)
            start.copy(
                conversation = start.conversation.copy(updatedAt = now, modelId = model),
                messages = start.messages + user,
            )
        }

        if (body != null && CommandGrammar.parse(body, now, ZoneId.systemDefault()) != null) {
            val reply = runCommand(body)
            val system = ChatMessage(
                ChatRepository.newId(), ChatRole.SYSTEM, reply, createdAt = nowMs(), status = MessageStatus.DONE,
            )
            val done = withUser.copy(messages = withUser.messages + system)
            doc = done
            saveDoc(done)
            return
        }

        if (body != null && Orchestrator.isPlanIntent(body)) {
            doc = withUser
            saveDoc(withUser)
            val conversationId = withUser.conversation.id
            runPlan(body, model) { reply -> scope.launch { appendSystemReply(conversationId, reply) } }
            return
        }

        if (model == null) {
            notice = "Install a model in Models first."
            doc = withUser
            return
        }
        doc = withUser
        saveDoc(withUser)
        generate(withUser, model)
    }

    private fun generate(current: ConversationDoc, model: String) {
        scope.launch {
            val settingsResult = withContext(Dispatchers.IO) { runCatching { settingsFor(model) } }
            val settings = settingsResult.getOrElse {
                notice = "Saved settings for this model could not be read; using defaults."
                ModelSettings.DEFAULT
            }
            val entry = modelEntryFor(model)
            val params = settings.genParams(maxOutputTokens = entry?.outputTokenBudget?.takeIf { it > 0 } ?: DEFAULT_OUTPUT_TOKENS)
            val result = withContext(Dispatchers.IO) {
                service.generate(
                    doc = current,
                    modelId = model,
                    params = params,
                    systemPrompt = settings.systemPrompt.ifBlank { null },
                    contextTokens = settings.contextTokens,
                    onFinished = { finished -> scope.launch { applyFinished(finished) } },
                )
            }
            if (result is ChatService.StartResult.Refused) notice = result.reason
        }
    }

    /**
     * Adds a planner reply as a SYSTEM message. The shown conversation is updated in place;
     * when the user has moved on, the stored conversation gets the reply so it is not lost.
     */
    private suspend fun appendSystemReply(conversationId: String, reply: String) {
        val shown = doc?.takeIf { it.conversation.id == conversationId }
        val target = shown
            ?: withContext(Dispatchers.IO) { runCatching { repo.readDoc(conversationId) }.getOrNull() }
            ?: return
        val message = ChatMessage(
            ChatRepository.newId(), ChatRole.SYSTEM, reply, createdAt = nowMs(), status = MessageStatus.DONE,
        )
        val done = target.copy(messages = target.messages + message)
        if (shown != null) doc = done
        saveDoc(done)
    }

    private fun applyFinished(finished: ConversationDoc) {
        if (doc?.conversation?.id == finished.conversation.id) doc = finished
        upsertConversation(finished.conversation)
    }

    private fun updateConversation(conversation: Conversation, change: (Conversation) -> Conversation) {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { runCatching { repo.readDoc(conversation.id) }.getOrNull() } ?: return@launch
            val updated = stored.copy(conversation = change(stored.conversation))
            withContext(Dispatchers.IO) { repo.save(updated) }
            if (doc?.conversation?.id == updated.conversation.id) doc = doc?.copy(conversation = updated.conversation)
            upsertConversation(updated.conversation)
        }
    }

    private fun upsertConversation(c: Conversation) {
        conversations = ConversationOrdering.sorted(conversations.filter { it.id != c.id } + c)
    }

    private fun saveDoc(d: ConversationDoc) {
        repo.save(d) { failure ->
            if (failure != null) {
                scope.launch { notice = "This conversation could not be saved." }
            } else {
                scope.launch { upsertConversation(d.conversation) }
            }
        }
    }

    private fun saveDraftSoon() {
        val key = draftKey
        val text = composer.text
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            saveDraftNow(key, text)
        }
    }

    private fun saveDraftNow(key: String, text: String) {
        repo.saveDraft(key, text)
    }

    companion object {
        const val UNDO_WINDOW_MS: Long = 5_000L
        const val DRAFT_DEBOUNCE_MS: Long = 400L
        const val DEFAULT_OUTPUT_TOKENS: Int = 700
    }
}

/**
 * The USER message for a send: [clip], when present, carries the voice fields (audio ref,
 * duration, transcript or note) beside the typed body — empty text is a clip-only message.
 * Null [clip] is the plain text send. Pure, so a JVM test can verify the field wiring
 * (app/src/test/.../ui/chat/VoiceClipSendTest.kt).
 */
internal fun userMessageWithClip(id: String, body: String, createdAt: Long, clip: VoiceClipDraft?): ChatMessage =
    if (clip == null) {
        ChatMessage(id, ChatRole.USER, body, createdAt = createdAt)
    } else {
        ChatMessage(
            id,
            ChatRole.USER,
            body,
            voiceClipRef = clip.ref,
            durationMs = clip.durationMs,
            transcript = clip.transcript,
            transcriptNote = clip.transcriptNote,
            createdAt = createdAt,
        )
    }
