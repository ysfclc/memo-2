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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the one resident model and runs chat requests one at a time, in FIFO order.
 *
 * - One model is resident. Asking for a different model unloads the current one
 *   first, then loads the new one. The runtime for a format is created once, by [factory],
 *   and reused.
 * - Requests run on one worker thread. A request's sink receives its tokens and exactly
 *   one terminal callback. A failure (load, refusal or generation error) ends that request
 *   only, and the next one starts.
 * - [cancel] removes a queued request at once, and cancels the running one through its handle.
 * State observers: the constructor's [onStateChanged] plus every [addListener] listener are
 * invoked on the worker thread after each published change.
 *
 * Callbacks for queued requests that are cancelled are delivered on the thread that called
 * [cancel] or [cancelAll]. Other callbacks run on the runtime's thread.
 *
 * @param factory creates the runtime for a model format. It is called on the worker thread.
 * @param onStateChanged called on the worker thread after each state change.
 */
class InferenceManager(
    private val factory: (ModelFormat) -> LlmRuntime,
    private val onStateChanged: (State) -> Unit = {},
) : AutoCloseable {

    /** What the manager is doing right now. */
    sealed class State {
        object Idle : State()
        data class Loading(val modelId: String) : State()
        data class Generating(val requestId: Long, val modelId: String) : State()
    }

    private class Request(
        val id: Long,
        val model: ModelRef,
        val turns: List<ChatTurn>,
        val params: GenParams,
        val sink: ChatSink,
    ) {
        val cancelled = AtomicBoolean(false)
        @Volatile var handle: RequestHandle? = null
    }

    private val lock = ReentrantLock()
    private val workAvailable = lock.newCondition()
    private val queue = ArrayDeque<Request>()
    private var running: Request? = null
    private var closed = false

    private val nextId = AtomicLong(1)

    /** Listeners beyond [onStateChanged]; invoked on the worker thread after each published change. */
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    // Worker-thread state.
    private val runtimes = HashMap<ModelFormat, LlmRuntime>()
    private var loaded: ModelRef? = null

    @Volatile private var state: State = State.Idle

    // Declared last: the worker must see every field above fully initialised.
    private val worker = Thread(::workerLoop, "memo-inference").apply {
        isDaemon = true
        start()
    }

    /** The current state. Cheap and safe from any thread. */
    fun state(): State = state

    /**
     * Registers [listener] for state changes, invoked on the worker thread. Shared consumers
     * (the chat service, the voice session) each add their own. Returns the manager.
     */
    fun addListener(listener: (State) -> Unit): InferenceManager {
        listeners.add(listener)
        return this
    }

    /** Unregisters a previously added listener. No-op if it was never added. */
    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Queues one chat request and returns its id. Use the id with [cancel].
     *
     * @throws IllegalStateException if the manager is closed.
     */
    fun submit(model: ModelRef, turns: List<ChatTurn>, params: GenParams, sink: ChatSink): Long {
        val id = nextId.getAndIncrement()
        lock.withLock {
            check(!closed) { "inference manager is closed" }
            queue.addLast(Request(id, model, turns, params, sink))
            workAvailable.signalAll()
        }
        return id
    }

    /**
     * Cancels request [requestId]. A queued request is removed and its sink receives
     * [LlmError.Cancelled] immediately. The running request is cancelled through its handle.
     * Returns false if the request is neither queued nor running (it may have finished).
     */
    fun cancel(requestId: Long): Boolean {
        var removed: Request? = null
        var current: Request? = null
        lock.withLock {
            removed = queue.firstOrNull { it.id == requestId }?.also { queue.remove(it) }
            if (removed == null) current = running?.takeIf { it.id == requestId }
        }
        removed?.let {
            it.cancelled.set(true)
            it.sink.onError(LlmError.Cancelled)
            return true
        }
        current?.let {
            cancelRunning(it)
            return true
        }
        return false
    }

    /** Cancels every queued request and the running one. */
    fun cancelAll() {
        val drained: List<Request>
        val current: Request?
        lock.withLock {
            drained = queue.toList()
            queue.clear()
            current = running
        }
        for (r in drained) {
            r.cancelled.set(true)
            r.sink.onError(LlmError.Cancelled)
        }
        current?.let { cancelRunning(it) }
    }

    /** Releases resident runtimes only when no request is active or queued. */
    fun unloadIfIdle(): Boolean = lock.withLock {
        if (closed || running != null || queue.isNotEmpty()) return false
        try {
            runtimes.values.forEach { it.unload() }
        } catch (_: RuntimeException) {
            return false
        }
        loaded = null
        state = State.Idle
        true
    }

    /**
     * Cancels all work, stops the worker and closes every runtime. Blocks until the
     * worker has finished, for up to ten seconds. Do not call from a sink callback.
     */
    override fun close() {
        lock.withLock { closed = true }
        cancelAll()
        lock.withLock { workAvailable.signalAll() }
        worker.join(CLOSE_TIMEOUT_MS)
    }

    private fun cancelRunning(r: Request) {
        r.cancelled.set(true)
        r.handle?.cancel()
    }

    private fun workerLoop() {
        try {
            while (true) {
                val next = lock.withLock {
                    while (queue.isEmpty() && !closed) workAvailable.await()
                    if (queue.isEmpty()) return
                    queue.removeFirst().also { running = it }
                }
                try {
                    runOne(next)
                } catch (e: RuntimeException) {
                    // A sink threw after its request ended. The worker must keep serving the queue.
                } finally {
                    lock.withLock { running = null }
                    publish(State.Idle)
                }
            }
        } finally {
            for (rt in runtimes.values) {
                try {
                    rt.close()
                } catch (e: RuntimeException) {
                    // Closing on shutdown: nothing more can be done with this runtime.
                }
            }
            runtimes.clear()
            loaded = null
        }
    }

    private fun runOne(req: Request) {
        if (req.cancelled.get()) {
            req.sink.onError(LlmError.Cancelled)
            return
        }

        ensureLoaded(req.model)?.let { error ->
            req.sink.onError(error)
            return
        }

        val runtime = runtimes.getValue(req.model.format)
        publish(State.Generating(req.id, req.model.id))

        val done = CountDownLatch(1)
        val wrapper = object : ChatSink {
            override fun onToken(text: String) = req.sink.onToken(text)
            override fun onThinking(text: String) = req.sink.onThinking(text)
            override fun onComplete(stats: GenStats) {
                try {
                    req.sink.onComplete(stats)
                } finally {
                    done.countDown()
                }
            }
            override fun onError(e: LlmError) {
                try {
                    req.sink.onError(e)
                } finally {
                    done.countDown()
                }
            }
        }

        val handle = try {
            runtime.chat(req.turns, req.params, wrapper)
        } catch (e: IllegalStateException) {
            // The runtime refused the request (no model, or another request in flight). This ends only this request.
            req.sink.onError(LlmError.Other("runtime refused the request: ${e.javaClass.simpleName}"))
            return
        }
        req.handle = handle
        // Cancel may have run between dequeuing and publishing the handle. Re-check now.
        if (req.cancelled.get()) handle.cancel()
        done.await()
    }

    /** Makes [model] the resident model. Returns the load error, or null on success. */
    private fun ensureLoaded(model: ModelRef): LlmError? {
        val current = loaded
        if (current != null && current.id == model.id && current.format == model.format) return null

        if (current != null) {
            runtimes[current.format]?.unload()
            loaded = null
        }

        publish(State.Loading(model.id))
        val runtime = runtimes.getOrPut(model.format) { factory(model.format) }
        return when (val result = runtime.load(model)) {
            is LoadResult.Loaded -> {
                loaded = model
                null
            }
            is LoadResult.Failed -> result.error
        }
    }

    private fun publish(next: State) {
        state = next
        onStateChanged(next)
        listeners.forEach { it(next) }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 10_000L
    }
}
