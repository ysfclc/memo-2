package app.memo.platform.android.llm.llama

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
import app.memo.platform.android.llm.LlmDefaults
import app.memo.platform.android.llm.ThinkSplitter
import app.memo.platform.android.llm.hasRamHeadroom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * llama.cpp runtime for GGUF models, over [LlamaNative].
 *
 * Every native call runs on one dedicated thread. One model stays resident. The
 * prompt is rendered with the GGUF chat template when the file has one, and with
 * ChatML otherwise. Each request starts from an empty KV cache, so the full turn
 * list is sent every time.
 *
 * Thinking and stop sequences are handled here, on the text the native side
 * delivers. [ThinkSplitter] routes `<think>` blocks, and [StopMatcher] ends the
 * answer at the first stop sequence. The native generation is then stopped by
 * returning false from the token sink.
 *
 * Do not call [unload] or [close] from inside a [ChatSink] callback: both wait
 * for the runtime thread.
 *
 * @param availableRam returns the available RAM in bytes, used by the load preflight.
 * @param threads threads for generation and prompt processing.
 */
class LlamaCppRuntime(
    private val availableRam: () -> Long,
    private val threads: Int = defaultThreadCount(),
) : LlmRuntime {

    override val formats: Set<ModelFormat> = setOf(ModelFormat.GGUF)

    @Volatile private var executorThread: Thread? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memo-llama").also {
            it.isDaemon = true
            executorThread = it
        }
    }

    /** Set and cleared only on the runtime thread. */
    @Volatile private var resident: Resident? = null

    private val active = AtomicReference<Active?>(null)
    private val infoRef = AtomicReference(NONE_INFO)

    private class Resident(val modelId: String, val handle: Long, val contextTokens: Int)

    private class Active(val sink: ChatSink) : RequestHandle {
        val cancelled = AtomicBoolean(false)
        @Volatile var nativeHandle: Long = 0L

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            val h = nativeHandle
            if (h != 0L) LlamaNative.nativeCancel(h)
        }
    }

    private sealed class Outcome {
        class Completed(val stats: GenStats) : Outcome()
        object Cancelled : Outcome()
        class Failed(val error: LlmError) : Outcome()
    }

    override fun load(ref: ModelRef): LoadResult = onExecutor {
        try {
            releaseResident()
            if (ref.format !in formats) {
                return@onExecutor LoadResult.Failed(LlmError.Unsupported("llama.cpp cannot load ${ref.format}"))
            }
            if (LlamaNative.loadError != null) {
                return@onExecutor LoadResult.Failed(LlmError.ModelLoad("native library unavailable"))
            }
            if (!hasRamHeadroom(availableRam(), ref.sizeBytes)) {
                return@onExecutor LoadResult.Failed(LlmError.OutOfMemory)
            }
            LlamaNative.nativeInitBackend()
            val handle = LlamaNative.nativeLoad(ref.path, LlmDefaults.CONTEXT_TOKENS, threads)
            if (handle == 0L) throw LlamaNativeException("LOAD: no model handle returned")
            val ctx = try {
                LlamaNative.nativeContextSize(handle)
            } catch (t: Throwable) {
                LlamaNative.nativeFree(handle)
                throw t
            }
            resident = Resident(ref.id, handle, ctx)
            infoRef.set(RuntimeInfo(BACKEND_LABEL, ref.id, ctx))
            LoadResult.Loaded(infoRef.get())
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) releaseResident()
            LoadResult.Failed(mapLlamaError(t, LlamaPhase.LOAD))
        }
    }

    override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
        check(resident != null) { "no model loaded" }
        val request = Active(sink)
        check(active.compareAndSet(null, request)) { "another request is in flight" }
        try {
            executor.execute { runRequest(request, turns, params) }
        } catch (e: RejectedExecutionException) {
            active.set(null)
            throw IllegalStateException("runtime is closed", e)
        }
        return request
    }

    override fun unload() {
        active.get()?.cancel()
        onExecutor { releaseResident() }
    }

    override fun info(): RuntimeInfo = infoRef.get()

    override fun close() {
        unload()
        executor.shutdown()
    }

    // ---- runtime thread -------------------------------------------------------

    private fun runRequest(request: Active, turns: List<ChatTurn>, params: GenParams) {
        val outcome = try {
            generate(request, turns, params)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) releaseResident()
            Outcome.Failed(mapLlamaError(t, LlamaPhase.GENERATE))
        }
        // Clear the slot before the terminal callback, so the sink may start the next request.
        active.set(null)
        when (outcome) {
            is Outcome.Completed -> request.sink.onComplete(outcome.stats)
            is Outcome.Cancelled -> request.sink.onError(LlmError.Cancelled)
            is Outcome.Failed -> request.sink.onError(outcome.error)
        }
    }

    private fun generate(request: Active, turns: List<ChatTurn>, params: GenParams): Outcome {
        if (request.cancelled.get()) return Outcome.Cancelled
        val res = resident ?: return Outcome.Failed(LlmError.Other("no model loaded"))
        val last = turns.lastOrNull { it.role != ChatTurn.Role.SYSTEM }
        if (last == null || (last.role != ChatTurn.Role.USER && last.role != ChatTurn.Role.TOOL)) {
            return Outcome.Failed(LlmError.Unsupported("the final turn must be a user or tool turn"))
        }

        val messages = toTemplateMessages(turns)
        val templated = LlamaNative.nativeApplyChatTemplate(
            res.handle,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
            true,
        )
        val prompt = templated ?: renderChatMl(messages)
        // A GGUF template normally writes its own special tokens, so BOS is added only for ChatML.
        val addSpecial = templated == null

        val promptTokens = LlamaNative.nativeCountTokens(res.handle, prompt, addSpecial)
        val seed = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
        val sampling = params.toNativeSampling(seed)

        val splitter = ThinkSplitter(params.thinking)
        val stops = StopMatcher(params.stop)
        val failure = AtomicReference<Throwable?>(null)

        fun route(piece: ThinkSplitter.Piece): Boolean {
            if (piece.thinking) {
                request.sink.onThinking(piece.text)
                return true
            }
            val visible = stops.feed(piece.text)
            if (visible.isNotEmpty()) request.sink.onToken(visible)
            return !stops.stopped
        }

        val tokenSink = object : LlamaTokenSink {
            override fun onPiece(text: String): Boolean {
                if (request.cancelled.get()) return false
                return try {
                    var keepGoing = true
                    for (piece in splitter.feed(text)) {
                        if (!route(piece)) {
                            keepGoing = false
                            break
                        }
                    }
                    keepGoing
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    false
                }
            }
        }

        request.nativeHandle = res.handle
        val started = System.nanoTime()
        val sampled = try {
            LlamaNative.nativeGenerate(
                res.handle,
                prompt,
                addSpecial,
                sampling.temperature,
                sampling.topK,
                sampling.topP,
                sampling.seed,
                sampling.maxTokens,
                tokenSink,
            )
        } finally {
            request.nativeHandle = 0L
        }

        failure.get()?.let { return Outcome.Failed(mapLlamaError(it, LlamaPhase.GENERATE)) }
        if (request.cancelled.get()) return Outcome.Cancelled

        if (!stops.stopped) {
            for (piece in splitter.finish()) {
                if (!route(piece)) break
            }
            val rest = stops.finish()
            if (rest.isNotEmpty()) request.sink.onToken(rest)
        }
        if (request.cancelled.get()) return Outcome.Cancelled

        return Outcome.Completed(
            GenStats(
                promptTokens = promptTokens,
                outputTokens = sampled,
                millis = (System.nanoTime() - started) / 1_000_000L,
                backend = BACKEND_LABEL,
            ),
        )
    }

    private fun releaseResident() {
        val res = resident ?: return
        resident = null
        infoRef.set(NONE_INFO)
        LlamaNative.nativeFree(res.handle)
    }

    /** Runs [block] on the runtime thread and waits for it. Runs inline on that thread. */
    private fun <T> onExecutor(block: () -> T): T {
        if (Thread.currentThread() === executorThread) return block()
        try {
            return executor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    companion object {
        const val BACKEND_LABEL = "llama-cpu"
        private val NONE_INFO = RuntimeInfo(backend = "none", loadedModelId = null, contextTokens = 0)

        /** Generation threads: all cores but two, never fewer than two. */
        fun defaultThreadCount(): Int = maxOf(2, Runtime.getRuntime().availableProcessors() - 2)
    }
}
