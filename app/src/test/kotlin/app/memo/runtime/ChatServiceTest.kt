package app.memo.runtime

import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ChatRole
import app.memo.core.chat.Conversation
import app.memo.core.chat.ConversationDoc
import app.memo.core.chat.MessageStatus
import app.memo.core.platform.ChatSink
import app.memo.core.platform.ChatTurn
import app.memo.core.platform.GenParams
import app.memo.core.platform.GenStats
import app.memo.core.platform.LlmError
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.LoadResult
import app.memo.core.platform.ModelFormat
import app.memo.core.platform.ModelRef
import app.memo.core.platform.RequestHandle
import app.memo.core.platform.RuntimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A runtime that hands each chat's sink to the test, which then drives the reply by hand. */
private class ScriptedRuntime : LlmRuntime {
    override val formats: Set<ModelFormat> = setOf(ModelFormat.LITERTLM)
    val chats = LinkedBlockingQueue<ChatSink>()
    var cancelled = 0
    private var loaded: ModelRef? = null

    override fun load(ref: ModelRef): LoadResult {
        loaded = ref
        return LoadResult.Loaded(info())
    }

    override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
        check(loaded != null) { "no model" }
        chats.add(sink)
        return object : RequestHandle {
            override fun cancel() {
                cancelled++
                sink.onError(LlmError.Cancelled)
            }
        }
    }

    override fun unload() {
        loaded = null
    }

    override fun info(): RuntimeInfo = RuntimeInfo("scripted", loaded?.id, 4096)

    override fun close() = unload()
}

class ChatServiceTest {

    private val params = GenParams(0.3, 40, 0.9, 700, thinking = false, stop = emptyList())
    private val model = ModelRef(id = "test-model", format = ModelFormat.LITERTLM, path = "/models/x", sha256 = "0".repeat(64), sizeBytes = 10)

    private fun setup(): Triple<ChatRepository, MemoryVault, ScriptedRuntime> {
        val vault = MemoryVault()
        val runtime = ScriptedRuntime()
        return Triple(ChatRepository(vault), vault, runtime)
    }

    private fun userDoc(): ConversationDoc = ConversationDoc(
        Conversation(id = "c0000001", title = "Hi", createdAt = 0, updatedAt = 0, modelId = null),
        listOf(ChatMessage("u0000001", ChatRole.USER, "Hi there", createdAt = 1)),
    )

    private fun service(
        repo: ChatRepository,
        runtime: ScriptedRuntime,
        streamClock: () -> Long = System::currentTimeMillis,
    ) = ChatService(
        repository = repo,
        inference = InferenceManager({ runtime }),
        resolveModel = { id -> if (id == model.id) model else null },
        now = { 100L },
        streamClock = streamClock,
    )

    /** Waits until the reply has been finished and returns the saved assistant message. */
    private fun awaitReply(
        svc: ChatService,
        doc: ConversationDoc,
        start: () -> Unit,
    ): ConversationDoc {
        val done = CountDownLatch(1)
        var finished: ConversationDoc? = null
        val result = svc.generate(doc, model.id, params, "system", onFinished = {
            finished = it
            done.countDown()
        })
        assertTrue("generate should start: $result", result is ChatService.StartResult.Started)
        start()
        assertTrue("reply should finish", done.await(5, TimeUnit.SECONDS))
        return finished!!
    }

    @Test
    fun streamedReplyEndsDoneAndIsSaved() {
        val (repo, _, runtime) = setup()
        val svc = service(repo, runtime)
        val doc = userDoc()
        val finished = awaitReply(svc, doc) {
            val sink = runtime.chats.poll(5, TimeUnit.SECONDS)!!
            sink.onToken("Hel")
            sink.onToken("lo")
            sink.onComplete(GenStats(3, 2, 5, "scripted"))
        }
        val reply = finished.messages.last()
        assertEquals(ChatRole.ASSISTANT, reply.role)
        assertEquals("Hello", reply.text)
        assertEquals(MessageStatus.DONE, reply.status)
        assertEquals("", reply.errorText)
        assertNull(svc.streaming.value)

        val saved = repo.readDoc("c0000001")!!
        assertEquals(finished, saved)
        svc.close()
        repo.close()
    }

    @Test
    fun cancelKeepsPartialTextAsStopped() {
        val (repo, _, runtime) = setup()
        val svc = service(repo, runtime)
        val finished = awaitReply(svc, userDoc()) {
            val sink = runtime.chats.poll(5, TimeUnit.SECONDS)!!
            sink.onToken("Partly ")
            assertTrue(svc.stop())
        }
        val reply = finished.messages.last()
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertEquals("Partly ", reply.text)
        assertEquals(1, runtime.cancelled)
        assertEquals(MessageStatus.STOPPED, repo.readDoc("c0000001")!!.messages.last().status)
        svc.close()
        repo.close()
    }

    @Test
    fun failureEndsErrorWithFixedReasonAndNoOutputText() {
        val (repo, _, runtime) = setup()
        val svc = service(repo, runtime)
        val finished = awaitReply(svc, userDoc()) {
            val sink = runtime.chats.poll(5, TimeUnit.SECONDS)!!
            sink.onToken("secret output")
            sink.onError(LlmError.Other("internal detail"))
        }
        val reply = finished.messages.last()
        assertEquals(MessageStatus.ERROR, reply.status)
        assertEquals("Generation failed.", reply.errorText)
        assertTrue(!reply.errorText.contains("internal"))
        svc.close()
        repo.close()
    }

    @Test
    fun generateRefusesWhenModelIsNotInstalled() {
        val (repo, _, runtime) = setup()
        val svc = service(repo, runtime)
        val result = svc.generate(userDoc(), "missing-model", params, null) {}
        assertTrue(result is ChatService.StartResult.Refused)
        svc.close()
        repo.close()
    }

    @Test
    fun interruptedStreamingMessagesBecomeStoppedOnLoad() {
        val (repo, _, _) = setup()
        val svc = ChatService(repo, InferenceManager({ ScriptedRuntime() }), { null })
        val doc = ConversationDoc(
            userDoc().conversation,
            listOf(ChatMessage("a0000001", ChatRole.ASSISTANT, "half", createdAt = 2, status = MessageStatus.STREAMING)),
        )
        val fixed = svc.recoverInterrupted(doc)
        assertEquals(MessageStatus.STOPPED, fixed.messages.single().status)
        assertEquals(MessageStatus.STOPPED, repo.readDoc("c0000001")!!.messages.single().status)
        svc.close()
        repo.close()
    }

    @Test
    fun tokenBurstWithinIntervalPublishesOnceAndKeepsAllText() {
        val (repo, _, runtime) = setup()
        var clock = 0L
        val svc = service(repo, runtime, streamClock = { clock })
        val publishes = mutableListOf<String>()
        val finished = awaitReply(svc, userDoc()) {
            val sink = runtime.chats.poll(5, TimeUnit.SECONDS)!!
            repeat(50) { i ->
                sink.onToken("t$i ")
                val shown = svc.streaming.value!!.text
                if (publishes.lastOrNull() != shown) publishes.add(shown)
            }
            sink.onComplete(GenStats(50, 2, 5, "scripted"))
        }
        assertEquals(listOf("t0 "), publishes)
        assertEquals((0 until 50).joinToString("") { "t$it " }, finished.messages.last().text)
        assertNull(svc.streaming.value)
        svc.close()
        repo.close()
    }

    @Test
    fun streamingPublishesAgainOnceIntervalPasses() {
        val (repo, _, runtime) = setup()
        var clock = 0L
        val svc = service(repo, runtime, streamClock = { clock })
        val seen = mutableListOf<String>()
        awaitReply(svc, userDoc()) {
            val sink = runtime.chats.poll(5, TimeUnit.SECONDS)!!
            sink.onToken("a")
            seen += svc.streaming.value!!.text
            clock = 50
            sink.onToken("b")
            seen += svc.streaming.value!!.text
            clock = 150
            sink.onToken("c")
            seen += svc.streaming.value!!.text
            sink.onComplete(GenStats(3, 2, 5, "scripted"))
        }
        assertEquals(listOf("a", "a", "abc"), seen)
        svc.close()
        repo.close()
    }
}
