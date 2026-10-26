package app.memo.platform.android.llm.llama

import app.memo.core.platform.ChatTurn

/** One message in the role/content form that chat templates take. */
internal data class TemplateMessage(val role: String, val content: String)

/**
 * Maps chat turns to template messages. System, user and assistant turns keep their
 * role. A tool turn becomes a user message that names the tool, because most GGUF
 * templates only define system, user and assistant roles.
 */
internal fun toTemplateMessages(turns: List<ChatTurn>): List<TemplateMessage> = turns.map { turn ->
    when (turn.role) {
        ChatTurn.Role.SYSTEM -> TemplateMessage("system", turn.text)
        ChatTurn.Role.USER -> TemplateMessage("user", turn.text)
        ChatTurn.Role.ASSISTANT -> TemplateMessage("assistant", turn.text)
        ChatTurn.Role.TOOL -> TemplateMessage("user", "Result from tool ${turn.toolName}:\n${turn.text}")
    }
}

/**
 * ChatML rendering, used when the GGUF file has no chat template. Each message is
 * wrapped in `<|im_start|>role` … `<|im_end|>`, and the prompt ends with an open
 * assistant turn for the model to continue.
 */
internal fun renderChatMl(messages: List<TemplateMessage>): String {
    val sb = StringBuilder()
    for (m in messages) {
        sb.append("<|im_start|>").append(m.role).append('\n')
        sb.append(m.content).append("<|im_end|>\n")
    }
    sb.append("<|im_start|>assistant\n")
    return sb.toString()
}
