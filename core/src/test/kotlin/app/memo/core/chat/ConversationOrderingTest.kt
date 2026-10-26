package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationOrderingTest {

    private fun conv(id: String, updatedAt: Long, pinned: Boolean = false, archived: Boolean = false) = Conversation(
        id = id,
        title = id,
        createdAt = 0,
        updatedAt = updatedAt,
        modelId = null,
        pinned = pinned,
        archived = archived,
    )

    @Test
    fun pinnedComeFirstThenNewestUpdated() {
        val list = listOf(
            conv("aaaaaaaa", updatedAt = 500),
            conv("bbbbbbbb", updatedAt = 100, pinned = true),
            conv("cccccccc", updatedAt = 900),
            conv("dddddddd", updatedAt = 300, pinned = true),
        )
        // Pinned group first, newest first (dddddddd=300 before bbbbbbbb=100), then the rest newest first.
        assertEquals(
            listOf("dddddddd", "bbbbbbbb", "cccccccc", "aaaaaaaa"),
            ConversationOrdering.sorted(list).map { it.id },
        )
    }

    @Test
    fun pinnedGroupIsOrderedByUpdatedAtBeforeUnpinned() {
        val list = listOf(
            conv("u1111111", updatedAt = 10_000),
            conv("p1111111", updatedAt = 1, pinned = true),
        )
        assertEquals(listOf("p1111111", "u1111111"), ConversationOrdering.sorted(list).map { it.id })
    }

    @Test
    fun equalTimestampsBreakTiesById() {
        val list = listOf(conv("bbbbbbbb", 5), conv("aaaaaaaa", 5))
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), ConversationOrdering.sorted(list).map { it.id })
    }

    @Test
    fun visibleHidesArchivedUnlessAsked() {
        val list = listOf(conv("aaaaaaaa", 1), conv("bbbbbbbb", 2, archived = true))
        assertEquals(listOf("aaaaaaaa"), ConversationOrdering.visible(list, showArchived = false).map { it.id })
        assertEquals(listOf("bbbbbbbb", "aaaaaaaa"), ConversationOrdering.visible(list, showArchived = true).map { it.id })
    }
}
