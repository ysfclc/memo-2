package app.memo.core.chat

import app.memo.core.platform.ChatTurn

/**
 * Builds the history sent to the model within a character budget. The system prompt and the
 * newest user turn are always kept. Older turns are dropped first, oldest first, and the kept
 * turns form a contiguous suffix of the conversation.
 */
object ContextBudget {
    /** Rough conversion used across the app: about four characters per token. */
    const val CHARS_PER_TOKEN: Int = 4

    /** Tokens kept free for the chat template and turn markers. */
    const val TEMPLATE_RESERVE_TOKENS: Int = 256

    /** Placeholder text sent for a voice message with neither text nor transcript. */
    const val VOICE_WITHOUT_TRANSCRIPT: String = "[Voice message without a transcript]"

    /** A selection: the turns to send in order, and how many older messages were left out. */
    data class Selection(val turns: List<ChatTurn>, val droppedMessages: Int)

    /**
     * Character budget for the prompt, given the model's context size and the output allowance.
     * Zero or less means the model cannot take any prompt.
     */
    fun budgetChars(contextTokens: Int, maxOutputTokens: Int): Int =
        ((contextTokens - maxOutputTokens - TEMPLATE_RESERVE_TOKENS) * CHARS_PER_TOKEN).coerceAtLeast(0)

    /**
     * [history] is in chronological order and ends with the new user turn. Returns null when the
     * system prompt and the newest user turn alone do not fit, so the caller can refuse the message.
     */
    fun select(system: String?, history: List<ChatTurn>, budgetChars: Int): Selection? {
        require(history.isNotEmpty() && history.last().role == ChatTurn.Role.USER) {
            "history must end with a user turn"
        }
        val systemTurn = system?.takeIf { it.isNotBlank() }?.let { ChatTurn(ChatTurn.Role.SYSTEM, it) }
        val newest = history.last()
        val mandatory = (systemTurn?.text?.length ?: 0) + newest.text.length
        if (mandatory > budgetChars) return null

        var used = mandatory
        var keepFrom = history.size - 1
        for (i in history.size - 2 downTo 0) {
            val cost = history[i].text.length
            if (used + cost > budgetChars) break
            used += cost
            keepFrom = i
        }
        val kept = history.subList(keepFrom, history.size)
        val turns = listOfNotNull(systemTurn) + kept
        return Selection(turns = turns, droppedMessages = keepFrom)
    }

    /**
     * Turns for the model from stored messages. Streaming messages and errors are skipped.
     * Stopped replies keep their partial text. Thinking is never sent back. A voice message
     * with blank text uses its transcript, or the [VOICE_WITHOUT_TRANSCRIPT] placeholder.
     */
    fun historyTurns(messages: List<ChatMessage>): List<ChatTurn> = messages.mapNotNull { m ->
        if (m.status == MessageStatus.STREAMING || m.status == MessageStatus.ERROR) return@mapNotNull null
        val text = turnText(m) ?: return@mapNotNull null
        val role = when (m.role) {
            ChatRole.USER -> ChatTurn.Role.USER
            ChatRole.ASSISTANT -> ChatTurn.Role.ASSISTANT
            ChatRole.SYSTEM -> ChatTurn.Role.SYSTEM
        }
        ChatTurn(role, text)
    }

    /** The text sent to the model for one message, or null when the message is skipped. */
    private fun turnText(m: ChatMessage): String? = when {
        m.text.isNotBlank() -> m.text
        m.voiceClipRef != null -> m.transcript?.takeIf { it.isNotBlank() } ?: VOICE_WITHOUT_TRANSCRIPT
        else -> null
    }
}
