package app.memo.platform.android.llm.llama

import app.memo.core.platform.ChatTurn
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTemplateFallbackTest {

    @Test
    fun systemUserAssistantRenderAsChatMl() {
        val messages = toTemplateMessages(
            listOf(
                ChatTurn(ChatTurn.Role.SYSTEM, "Be brief."),
                ChatTurn(ChatTurn.Role.USER, "Hi"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "Hello."),
                ChatTurn(ChatTurn.Role.USER, "Name a colour."),
            ),
        )
        val expected =
            "<|im_start|>system\nBe brief.<|im_end|>\n" +
                "<|im_start|>user\nHi<|im_end|>\n" +
                "<|im_start|>assistant\nHello.<|im_end|>\n" +
                "<|im_start|>user\nName a colour.<|im_end|>\n" +
                "<|im_start|>assistant\n"
        assertEquals(expected, renderChatMl(messages))
    }

    @Test
    fun emptyConversationStillOpensAssistantTurn() {
        assertEquals("<|im_start|>assistant\n", renderChatMl(emptyList()))
    }

    @Test
    fun toolTurnBecomesNamedUserMessage() {
        val messages = toTemplateMessages(
            listOf(ChatTurn(ChatTurn.Role.TOOL, "42", toolName = "calc")),
        )
        assertEquals(listOf(TemplateMessage("user", "Result from tool calc:\n42")), messages)
    }
}
