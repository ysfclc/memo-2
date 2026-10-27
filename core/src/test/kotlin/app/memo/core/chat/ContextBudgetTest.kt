package app.memo.core.chat

import app.memo.core.platform.ChatTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextBudgetTest {

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun assistant(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    @Test
    fun keepsEverythingWhenItFits() {
        val history = listOf(user("aaaa"), assistant("bbbb"), user("cccc"))
        val sel = ContextBudget.select("sys", history, budgetChars = 1000)!!
        assertEquals(0, sel.droppedMessages)
        assertEquals(ChatTurn.Role.SYSTEM, sel.turns.first().role)
        assertEquals(listOf("sys", "aaaa", "bbbb", "cccc"), sel.turns.map { it.text })
    }

    @Test
    fun dropsOldestFirstAndKeepsSystemAndNewest() {
        val history = listOf(user("1111"), assistant("2222"), user("3333"), assistant("4444"), user("5555"))
        // system 3 chars + newest 4 chars = 7; room for two more 4-char turns: 7 + 4 + 4 = 15.
        val sel = ContextBudget.select("sys", history, budgetChars = 15)!!
        assertEquals(listOf("sys", "3333", "4444", "5555"), sel.turns.map { it.text })
        assertEquals(2, sel.droppedMessages)
    }

    @Test
    fun droppingStopsAtFirstTurnThatDoesNotFit() {
        // The oldest turn is short and would fit, but a newer one does not: the kept turns stay contiguous.
        val history = listOf(user("x"), user("y".repeat(20)), user("z"))
        val sel = ContextBudget.select(null, history, budgetChars = 5)!!
        assertEquals(listOf("z"), sel.turns.map { it.text })
        assertEquals(2, sel.droppedMessages)
    }

    @Test
    fun refusesWhenSystemPlusNewestDoNotFit() {
        assertNull(ContextBudget.select("s".repeat(50), listOf(user("hello")), budgetChars = 20))
    }

    @Test
    fun blankSystemPromptIsOmitted() {
        val sel = ContextBudget.select("   ", listOf(user("hi")), budgetChars = 100)!!
        assertEquals(listOf(ChatTurn.Role.USER), sel.turns.map { it.role })
    }

    @Test
    fun historyMustEndWithUserTurn() {
        val failed = runCatching { ContextBudget.select(null, listOf(assistant("only")), 100) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun budgetFromContextAndOutputAllowance() {
        // 4096 tokens context, 700 output, 256 template reserve: 3140 tokens of prompt, 4 chars each.
        assertEquals(3140 * 4, ContextBudget.budgetChars(contextTokens = 4096, maxOutputTokens = 700))
        assertEquals(0, ContextBudget.budgetChars(contextTokens = 500, maxOutputTokens = 700))
    }

    @Test
    fun historySkipsStreamingAndErrorAndKeepsStopped() {
        val messages = listOf(
            ChatMessage("u0000001", ChatRole.USER, "q", createdAt = 1),
            ChatMessage("a0000001", ChatRole.ASSISTANT, "partial", createdAt = 2, status = MessageStatus.STOPPED),
            ChatMessage("a0000002", ChatRole.ASSISTANT, "", createdAt = 3, status = MessageStatus.ERROR, errorText = "x"),
            ChatMessage("a0000003", ChatRole.ASSISTANT, "live", createdAt = 4, status = MessageStatus.STREAMING),
            ChatMessage("u0000002", ChatRole.USER, "again", createdAt = 5),
        )
        assertEquals(listOf("q", "partial", "again"), ContextBudget.historyTurns(messages).map { it.text })
    }
}
