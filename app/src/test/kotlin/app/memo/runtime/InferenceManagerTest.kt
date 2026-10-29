package app.memo.runtime

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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InferenceManagerTest {

    /** A chat request the fake runtime is holding open until the test finishes it. */
    private class Pending(val text: String, val sink: ChatSink)

    /** Records every call in [log] and holds each chat open until the test completes it. */
    private class FakeRuntime(
        private val log: MutableList<String>,
        private val failLoadFor: Set<String> = emptySet(),
    ) : LlmRuntime {
        override val formats: Set<ModelFormat> = setOf(ModelFormat.LITERTLM, ModelFormat.GGUF)
        val pendings = LinkedBlockingQueue<Pending>()
        private var loaded: ModelRef? = null

        override fun load(ref: ModelRef): LoadResult {
            log += "load:${ref.id}"
            if (ref.id in failLoadFor) return LoadResult.Failed(LlmError.ModelLoad("bad file"))
            loaded = ref
            return LoadResult.Loaded(info())
        }

        override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
            check(loaded != null) { "no model loaded" }
            val text = turns.last().text
            log += "chat:$text"
            val pending = Pending(text, sink)
            pendings.add(pending)
            return object : RequestHandle {
                override fun cancel() {
                    log += "cancel:$text"
                    sink.onError(LlmError.Cancelled)
                }
            }
        }

        override fun unload() {
            log += "unload:${loaded?.id}"
            loaded = null
        }

        override fun info(): RuntimeInfo = RuntimeInfo("fake", loaded?.id, 128)

        override fun close() = unload()
    }

    /** Records the events a request's sink receives. */
    private class RecordingSink : ChatSink {
        val events = LinkedBlockingQueue<String>()
        override fun onToken(text: String) { events.add("token:$text") }
        override fun onThinking(text: String) { events.add("thinking:$text") }
        override fun onComplete(stats: GenStats) { events.add("complete") }
        override fun onError(e: LlmError) { events.add("error:${e.javaClass.simpleName}") }

        fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: error("no event within 5 s")
    }

    private fun model(id: String, format: ModelFormat = ModelFormat.LITERTLM) =
        ModelRef(id = id, format = format, path = "/models/$id", sha256 = "a".repeat(64), sizeBytes = 1)

    private fun turns(text: String) = listOf(ChatTurn(ChatTurn.Role.USER, text))

    private fun pendingOf(runtime: FakeRuntime): Pending =
        runtime.pendings.poll(5, TimeUnit.SECONDS) ?: error("no chat reached the runtime")

    private fun noPendingWithin(runtime: FakeRuntime, ms: Long): Boolean =
        runtime.pendings.poll(ms, TimeUnit.MILLISECONDS) == null

    private fun complete(p: Pending) {
        p.sink.onComplete(GenStats(0, 1, 1, "fake"))
    }

    /** The worker publishes Idle just after a terminal callback. Waits for that. */
    private fun awaitIdle(manager: InferenceManager) {
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.state() !is InferenceManager.State.Idle && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    @Test
    fun switchingModelsUnloadsThePreviousOneFirst() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val a = RecordingSink()
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, a)
            complete(pendingOf(runtime))
            assertEquals("complete", a.next())

            val b = RecordingSink()
            manager.submit(model("b"), turns("y"), GenParams.ROUTINE, b)
            complete(pendingOf(runtime))
            assertEquals("complete", b.next())

            assertEquals(listOf("load:a", "chat:x", "unload:a", "load:b", "chat:y"), log)
        } finally {
            manager.close()
        }
    }

    @Test
    fun sameModelIsNotReloaded() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val first = RecordingSink()
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, first)
            complete(pendingOf(runtime))
            first.next()

            val second = RecordingSink()
            manager.submit(model("a"), turns("z"), GenParams.ROUTINE, second)
            complete(pendingOf(runtime))
            second.next()

            assertEquals(listOf("load:a", "chat:x", "chat:z"), log)
        } finally {
            manager.close()
        }
    }

    @Test
    fun unloadIfIdleReleasesTheResidentModel() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val sink = RecordingSink()
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, sink)
            complete(pendingOf(runtime))
            assertEquals("complete", sink.next())
            awaitIdle(manager)

            assertTrue(manager.unloadIfIdle())
            assertEquals(listOf("load:a", "chat:x", "unload:a"), log)
        } finally {
            manager.close()
        }
    }

    @Test
    fun unloadIfIdleRefusesWhileGenerationIsActive() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, RecordingSink())
            pendingOf(runtime)

            assertEquals(false, manager.unloadIfIdle())
            assertEquals(listOf("load:a", "chat:x"), log)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requestsRunInFifoOrderOneAtATime() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val sinks = listOf(RecordingSink(), RecordingSink(), RecordingSink())
            sinks.forEachIndexed { i, s -> manager.submit(model("a"), turns("r$i"), GenParams.ROUTINE, s) }

            val first = pendingOf(runtime)
            assertEquals("r0", first.text)
            // The second chat must not start while the first is still open.
            assertTrue(noPendingWithin(runtime, 150))

            complete(first)
            assertEquals("complete", sinks[0].next())
            val second = pendingOf(runtime)
            assertEquals("r1", second.text)
            complete(second)
            assertEquals("complete", sinks[1].next())
            val third = pendingOf(runtime)
            assertEquals("r2", third.text)
            complete(third)
            assertEquals("complete", sinks[2].next())

            assertEquals(listOf("load:a", "chat:r0", "chat:r1", "chat:r2"), log)
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancellingAQueuedRequestRemovesItWithoutRunningIt() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val running = RecordingSink()
            val queued = RecordingSink()
            manager.submit(model("a"), turns("r0"), GenParams.ROUTINE, running)
            val queuedId = manager.submit(model("a"), turns("r1"), GenParams.ROUTINE, queued)
            val first = pendingOf(runtime)

            assertTrue(manager.cancel(queuedId))
            assertEquals("error:Cancelled", queued.next())

            complete(first)
            assertEquals("complete", running.next())

            val third = RecordingSink()
            manager.submit(model("a"), turns("r2"), GenParams.ROUTINE, third)
            assertEquals("r2", pendingOf(runtime).text)
            assertTrue(log.none { it == "chat:r1" })
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancellingTheRunningRequestEndsItAndTheNextRuns() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val running = RecordingSink()
            val next = RecordingSink()
            val runningId = manager.submit(model("a"), turns("r0"), GenParams.ROUTINE, running)
            manager.submit(model("a"), turns("r1"), GenParams.ROUTINE, next)
            pendingOf(runtime)

            assertTrue(manager.cancel(runningId))
            assertEquals("error:Cancelled", running.next())

            assertEquals("r1", pendingOf(runtime).text)
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancelOfAFinishedRequestReturnsFalse() {
        val runtime = FakeRuntime(mutableListOf())
        val manager = InferenceManager({ runtime })
        try {
            val s = RecordingSink()
            val id = manager.submit(model("a"), turns("x"), GenParams.ROUTINE, s)
            complete(pendingOf(runtime))
            assertEquals("complete", s.next())
            awaitIdle(manager)
            assertEquals(false, manager.cancel(id))
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancelAllClearsQueueAndStopsRunning() {
        val runtime = FakeRuntime(mutableListOf())
        val manager = InferenceManager({ runtime })
        try {
            val a = RecordingSink()
            val b = RecordingSink()
            manager.submit(model("a"), turns("r0"), GenParams.ROUTINE, a)
            manager.submit(model("a"), turns("r1"), GenParams.ROUTINE, b)
            pendingOf(runtime)

            manager.cancelAll()
            assertEquals("error:Cancelled", a.next())
            assertEquals("error:Cancelled", b.next())
            assertTrue(noPendingWithin(runtime, 150))
        } finally {
            manager.close()
        }
    }

    @Test
    fun errorInOneRequestDoesNotBlockTheNextOne() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log)
        val manager = InferenceManager({ runtime })
        try {
            val failing = RecordingSink()
            val ok = RecordingSink()
            manager.submit(model("a"), turns("r0"), GenParams.ROUTINE, failing)
            manager.submit(model("a"), turns("r1"), GenParams.ROUTINE, ok)

            pendingOf(runtime).sink.onError(LlmError.Other("boom"))
            assertEquals("error:Other", failing.next())

            complete(pendingOf(runtime))
            assertEquals("complete", ok.next())
        } finally {
            manager.close()
        }
    }

    @Test
    fun loadFailureFailsOnlyThatRequest() {
        val log = mutableListOf<String>()
        val runtime = FakeRuntime(log, failLoadFor = setOf("bad"))
        val manager = InferenceManager({ runtime })
        try {
            val bad = RecordingSink()
            val good = RecordingSink()
            manager.submit(model("bad"), turns("r0"), GenParams.ROUTINE, bad)
            manager.submit(model("a"), turns("r1"), GenParams.ROUTINE, good)

            assertEquals("error:ModelLoad", bad.next())
            complete(pendingOf(runtime))
            assertEquals("complete", good.next())
        } finally {
            manager.close()
        }
    }

    @Test
    fun stateFollowsTheRequestLifecycle() {
        val runtime = FakeRuntime(mutableListOf())
        val manager = InferenceManager({ runtime })
        try {
            assertIs<InferenceManager.State.Idle>(manager.state())
            val s = RecordingSink()
            val id = manager.submit(model("a"), turns("x"), GenParams.ROUTINE, s)
            val p = pendingOf(runtime)
            val generating = manager.state()
            assertEquals(InferenceManager.State.Generating(id, "a"), generating)
            complete(p)
            s.next()
            awaitIdle(manager)
            assertIs<InferenceManager.State.Idle>(manager.state())
            assertNull((manager.state() as? InferenceManager.State.Generating))
        } finally {
            manager.close()
        }
    }

    @Test
    fun submitAfterCloseIsRefused() {
        val manager = InferenceManager({ FakeRuntime(mutableListOf()) })
        manager.close()
        val refused = runCatching { manager.submit(model("a"), turns("x"), GenParams.ROUTINE, RecordingSink()) }
        assertTrue(refused.isFailure)
    }

    @Test
    fun listenersSeeEveryPublishedState() {
        val runtime = FakeRuntime(mutableListOf())
        val manager = InferenceManager({ runtime })
        try {
            val seen = LinkedBlockingQueue<String>()
            manager.addListener { state ->
                seen.add(
                    when (state) {
                        InferenceManager.State.Idle -> "idle"
                        is InferenceManager.State.Loading -> "loading:${state.modelId}"
                        is InferenceManager.State.Generating -> "generating:${state.modelId}"
                    },
                )
            }

            val sink = RecordingSink()
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, sink)
            complete(pendingOf(runtime))
            assertEquals("complete", sink.next())
            awaitIdle(manager)

            // One request: load, generate, then back to idle — each published exactly once.
            assertEquals("loading:a", seen.poll(5, TimeUnit.SECONDS))
            assertEquals("generating:a", seen.poll(5, TimeUnit.SECONDS))
            assertEquals("idle", seen.poll(5, TimeUnit.SECONDS))
        } finally {
            manager.close()
        }
    }

    @Test
    fun removeListenerStopsDelivery() {
        val runtime = FakeRuntime(mutableListOf())
        val manager = InferenceManager({ runtime })
        try {
            val seen = LinkedBlockingQueue<String>()
            val listener: (InferenceManager.State) -> Unit = { seen.add("x") }
            manager.addListener(listener)
            manager.removeListener(listener)

            val sink = RecordingSink()
            manager.submit(model("a"), turns("x"), GenParams.ROUTINE, sink)
            complete(pendingOf(runtime))
            assertEquals("complete", sink.next())
            awaitIdle(manager)

            assertNull(seen.poll(150, TimeUnit.MILLISECONDS), "a removed listener must not receive states")
        } finally {
            manager.close()
        }
    }
}
