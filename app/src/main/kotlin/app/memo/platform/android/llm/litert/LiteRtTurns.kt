package app.memo.platform.android.llm.litert

import app.memo.core.platform.ChatTurn

/**
 * A chat request split into what LiteRT-LM takes: a system instruction, the
 * earlier turns as conversation history, and the final turn that is sent.
 */
internal data class TurnSplit(
    val system: String?,
    val history: List<ChatTurn>,
    val last: ChatTurn,
)

/**
 * Splits [turns] for a LiteRT-LM conversation. All system turns join into one
 * instruction, in order, separated by a blank line. The final non-system turn
 * is the message sent; it must be a user or tool turn. Returns null when there
 * is no such final turn.
 */
internal fun splitTurns(turns: List<ChatTurn>): TurnSplit? {
    val system = turns.filter { it.role == ChatTurn.Role.SYSTEM }.map { it.text }
    val rest = turns.filter { it.role != ChatTurn.Role.SYSTEM }
    val last = rest.lastOrNull() ?: return null
    if (last.role != ChatTurn.Role.USER && last.role != ChatTurn.Role.TOOL) return null
    return TurnSplit(
        system = system.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        history = rest.dropLast(1),
        last = last,
    )
}
