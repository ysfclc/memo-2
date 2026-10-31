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

    // -------------------------------------------------- voice messages (M2.B1)

    private fun voiceDoc(blind: String, ref: String, transcript: String?, note: String?): ConversationDoc {
        val message = ChatMessage(
            id = "m$blind",
            role = ChatRole.USER,
            text = "",
            voiceClipRef = ref,
            durationMs = 24_500,
            transcript = transcript,
            transcriptNote = note,
            createdAt = 42,
        )
        return ConversationDoc(
            Conversation(id = blind, title = "Voice roundtrip", createdAt = 0, updatedAt = 1, modelId = null),
            listOf(message),
        )
    }

    @Test
    fun voiceMessageRoundTripsThroughTheVault() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        val doc = voiceDoc("aaaaaa01", "20261030-090000-123", "Tomorrow at nine.", null)
        saveSync(repo, doc)
        assertEquals(doc, ChatRepository(vault).readDoc("aaaaaa01"))
        repo.close()
    }

    @Test
    fun untranscribedVoiceMessageRoundTripsThroughTheVault() {
        val vault = MemoryVault()
        val repo = ChatRepository(vault)
        val doc = voiceDoc("aaaaaa02", "20261030-090000-456", null, "the Whisper model is not installed")
        saveSync(repo, doc)
        val read = ChatRepository(vault).readDoc("aaaaaa02")!!
        assertEquals(null, read.messages.single().transcript)
        assertEquals("the Whisper model is not installed", read.messages.single().transcriptNote)
        assertEquals(24_500L, read.messages.single().durationMs)
        repo.close()
    }

    /** A raw format-1 entry, written exactly as older builds wrote them, must read as before. */
    @Test
    fun legacyTextOnlyEntryStillDecodesThroughTheRepository() {
        val vault = MemoryVault()
        val legacy =
            "{\"v\":1," +
                "\"conversation\":{\"id\":\"aabbcc01\",\"title\":\"Before voice\",\"createdAt\":1700000000000," +
                "\"updatedAt\":1700000100000,\"modelId\":\"gemma-3-1b\"," +
                "\"pinned\":false,\"archived\":false}," +
                "\"messages\":[" +
                "{\"id\":\"1111aaaa\",\"role\":\"USER\",\"text\":\"note to self\",\"thinking\":\"\"," +
                "\"createdAt\":1700000001000,\"status\":\"DONE\",\"errorText\":\"\"}]}"
        vault.data[ChatRepository.ENTRY_PREFIX + "aabbcc01"] = legacy.toByteArray(Charsets.UTF_8)
        val doc = ChatRepository(vault).readDoc("aabbcc01")!!
        assertEquals(
            Conversation(
                id = "aabbcc01",
                title = "Before voice",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_100_000,
                modelId = "gemma-3-1b",
            ),
            doc.conversation,
        )
        val message = doc.messages.single()
        assertEquals("note to self", message.text)
        assertNull("voiceClipRef", message.voiceClipRef)
        assertNull("durationMs", message.durationMs)
        assertNull("transcript", message.transcript)
        assertNull("transcriptNote", message.transcriptNote)
        // And the entry still saves from this state: encoding re-tags the document as the current version (3)
        // without touching a single field, and a reload reads the same content back.
        val reload = ChatRepository(vault)
        saveSync(reload, doc.copy(conversation = doc.conversation.copy(updatedAt = 2)))
        val reread = ChatRepository(vault).readDoc("aabbcc01")!!
        assertEquals(doc.copy(conversation = doc.conversation.copy(updatedAt = 2)), reread)
        assertEquals("note to self", reread.messages.single().text)
        assertTrue(String(vault.data[ChatRepository.ENTRY_PREFIX + "aabbcc01"]!!, Charsets.UTF_8).contains("\"v\":3"))
    }
}
