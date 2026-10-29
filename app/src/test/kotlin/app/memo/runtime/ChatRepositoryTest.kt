package app.memo.runtime

import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.ChatIds
import app.memo.core.chat.Conversation
import app.memo.core.chat.ConversationDoc
import app.memo.core.platform.Vault
import app.memo.core.platform.VaultException
import app.memo.core.platform.VaultName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-memory [Vault] for tests. Thread-safe. */
internal class MemoryVault : Vault {
    val data = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    val corrupt = HashSet<String>()

    override fun read(name: String): ByteArray? {
        VaultName.validate(name)
        if (name in corrupt) throw VaultException("corrupt $name")
        return data[name]
    }

    override fun write(name: String, bytes: ByteArray) {
        VaultName.validate(name)
        data[name] = bytes
    }

    override fun delete(name: String) {
        data.remove(name)
    }

    override fun list(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }.sorted()
}

class ChatRepositoryTest {

    private fun doc(id: String, updatedAt: Long = 1): ConversationDoc = ConversationDoc(
        Conversation(id = id, title = "Title $id", createdAt = 0, updatedAt = updatedAt, modelId = null),
        listOf(ChatMessage(id = "m$id", role = ChatRole.USER, text = "hi", createdAt = 0)),
    )

    private fun saveSync(repo: ChatRepository, d: ConversationDoc) {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        repo.save(d) { error = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        if (error != null) throw error!!
    }

    @Test
    fun savedConversationsAreListedAndReadBack() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        saveSync(repo, doc("aaaaaaaa"))
        saveSync(repo, doc("bbbbbbbb"))
        val loaded = ChatRepository(vault).load()
        assertEquals(setOf("aaaaaaaa", "bbbbbbbb"), loaded.conversations.map { it.id }.toSet())
        assertTrue(loaded.problems.isEmpty())
        assertEquals(doc("aaaaaaaa"), ChatRepository(vault).readDoc("aaaaaaaa"))
        repo.close()
    }

    @Test
    fun entryNamesFollowVaultRules() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        saveSync(repo, doc("c0ffee00"))
        assertNotNull(vault.data["chat_c0ffee00"])
        assertNotNull(vault.data[ChatRepository.INDEX_NAME])
        repo.close()
    }

    @Test
    fun corruptEntryIsReportedAndNeverOverwritten() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        saveSync(repo, doc("dddddddd"))
        val before = vault.data["chat_dddddddd"]!!.copyOf()
        vault.corrupt += "chat_dddddddd"

        val reader = ChatRepository(vault)
        reader.load()
        // Opening the corrupt entry fails loudly and records the problem.
        try {
            reader.readDoc("dddddddd")
            fail("reading a corrupt entry must throw")
        } catch (expected: VaultException) {
            // expected
        }
        assertTrue(reader.problems().any { it.contains("dddddddd") })

        try {
            saveSync(reader, doc("dddddddd", updatedAt = 99))
            fail("save over a corrupt entry must be refused")
        } catch (expected: VaultException) {
            // expected
        }
        assertEquals(before.toList(), vault.data["chat_dddddddd"]!!.toList())
        repo.close()
        reader.close()
    }

    @Test
    fun deleteRemovesEntryAndIndexRow() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        saveSync(repo, doc("eeeeeeee"))
        val latch = CountDownLatch(1)
        repo.delete("eeeeeeee") { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNull(vault.data["chat_eeeeeeee"])
        assertTrue(ChatRepository(vault).load().conversations.isEmpty())
        repo.close()
    }

    @Test
    fun conversationIdsAreValidVaultNames() {
        val name = ChatIds.vaultName("0123456789abcdef0123456789abcdef")
        VaultName.validate(name)
        assertEquals(37, name.length)
    }

    @Test
    fun newIdsAreValidConversationIds() {
        repeat(20) {
            assertTrue(ChatIds.PATTERN.matches(ChatRepository.newId()))
        }
    }

    @Test
    fun firstDraftSaveDoesNotOverwriteCorruptDraftsEntry() {
        val vault = MemoryVault()
        val original = byteArrayOf(0x7b, 0x6e, 0x6f, 0x74, 0x2d, 0x6a, 0x73, 0x6f, 0x6e, 0x7d)
        vault.data[ChatRepository.DRAFTS_NAME] = original.copyOf()
        val repo = ChatRepository(vault)
        val latch = CountDownLatch(1)
        var failure: Throwable? = null

        repo.saveDraft(ChatRepository.NEW_CHAT_DRAFT, "new draft") {
            failure = it
            latch.countDown()
        }

        assertTrue("draft save should finish", latch.await(5, TimeUnit.SECONDS))
        assertNotNull("corrupt drafts must refuse the write", failure)
        assertEquals(original.toList(), vault.data[ChatRepository.DRAFTS_NAME]?.toList())
        repo.close()
    }
}
