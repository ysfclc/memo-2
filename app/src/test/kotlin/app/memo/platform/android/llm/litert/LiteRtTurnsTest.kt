package app.memo.platform.android.llm.litert

import app.memo.core.platform.ChatTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiteRtTurnsTest {

    @Test
    fun systemTurnsJoinAndHistoryExcludesLast() {
        val turns = listOf(
            ChatTurn(ChatTurn.Role.SYSTEM, "be brief"),
            ChatTurn(ChatTurn.Role.USER, "hi"),
            ChatTurn(ChatTurn.Role.ASSISTANT, "hello"),
            ChatTurn(ChatTurn.Role.SYSTEM, "in English"),
            ChatTurn(ChatTurn.Role.USER, "how are you"),
        )
        val split = splitTurns(turns)!!
        assertEquals("be brief\n\nin English", split.system)
        assertEquals(turns.subList(1, 3), split.history)
        assertEquals("how are you", split.last.text)
    }

    @Test
    fun noSystemTurnGivesNullInstruction() {
        val split = splitTurns(listOf(ChatTurn(ChatTurn.Role.USER, "x")))!!
        assertNull(split.system)
        assertEquals(emptyList(), split.history)
    }

    @Test
    fun finalAssistantTurnIsRejected() {
        val turns = listOf(
            ChatTurn(ChatTurn.Role.USER, "x"),
            ChatTurn(ChatTurn.Role.ASSISTANT, "y"),
        )
        assertNull(splitTurns(turns))
    }

    @Test
    fun emptyRequestIsRejected() {
        assertNull(splitTurns(listOf(ChatTurn(ChatTurn.Role.SYSTEM, "only system"))))
    }

    @Test
    fun finalToolTurnIsAccepted() {
        val split = splitTurns(
            listOf(
                ChatTurn(ChatTurn.Role.USER, "search"),
                ChatTurn(ChatTurn.Role.TOOL, "result", toolName = "search"),
            ),
        )!!
        assertEquals(ChatTurn.Role.TOOL, split.last.role)
    }
}
