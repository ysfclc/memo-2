package app.memo.platform.android.llm.litert

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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LiteRT-LM runtime for `.litertlm` models.
 *
 * All engine and conversation work runs on one dedicated thread, so generation
 * never touches the caller's thread. One engine stays resident. Each request
 * gets a fresh conversation, which is closed when the request ends.
 *
 * Backend choice: the engine starts on GPU. If that init throws, the GPU engine
 * is dropped without `close()` (its init failed) and a CPU engine is created. If
 * the first generation fails on GPU before any output was delivered, the engine
 * is closed and the generation is retried once on CPU. A retry is never made
 * after output has reached the sink, because that would repeat text.
 *
 * Thinking: [GenParams.thinking] is passed to LiteRT-LM and any `<think>` block
 * in the output goes through [ThinkSplitter]. Output past
 * [GenParams.maxOutputTokens] is stopped with `cancelProcess()`.
 *
 * Do not call [unload] or [close] from inside a [ChatSink] callback: both wait
 * for the runtime thread.
 *
 * @param cacheDir engine cache directory; created on load if missing.
 * @param availableRam returns the available RAM in bytes, used by the load preflight.
 */
class LiteRtLmRuntime(
    private val cacheDir: File,
    private val availableRam: () -> Long,
) : LlmRuntime {

    override val formats: Set<ModelFormat> = setOf(ModelFormat.LITERTLM)

    @Volatile private var executorThread: Thread? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memo-litert").also {
            it.isDaemon = true
            executorThread = it
        }
    }

    /** Touched only on the runtime thread, except the volatile read in [chat]. */
    @Volatile private var resident: Resident? = null

    private val active = AtomicReference<Active?>(null)
    private val infoRef = AtomicReference(NONE_INFO)

    /** The loaded engine. Replaced only on the runtime thread. */
    private class Resident(
        val modelId: String,
        val path: String,
        var engine: Engine,
        var onGpu: Boolean,
        var backendLabel: String,
        var firstGenPending: Boolean,
    )

    private class Active(val sink: ChatSink) : RequestHandle {
        val cancelled = AtomicBoolean(false)
        @Volatile var conversation: Conversation? = null

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            val c = conversation ?: return
            try {
                c.cancelProcess()
            } catch (e: IllegalStateException) {
                // The conversation closed between the read and the call. Nothing left to cancel.
            }
        }
    }

    private sealed class Run {
        class Ok(val outputTokens: Int) : Run()
        object Cancelled : Run()
        class Failed(val error: Throwable, val emitted: Boolean) : Run()
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
                return@onExecutor LoadResult.Failed(LlmError.Unsupported("LiteRT-LM cannot load ${ref.format}"))
            }
            if (!hasRamHeadroom(availableRam(), ref.sizeBytes)) {
                return@onExecutor LoadResult.Failed(LlmError.OutOfMemory)
            }
            if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
                throw IllegalStateException("cache directory unavailable")
            }
            val (engine, onGpu) = initWithFallback(ref.path)
            val label = if (onGpu) GPU_LABEL else CPU_LABEL
            resident = Resident(
                modelId = ref.id,
                path = ref.path,
                engine = engine,
                onGpu = onGpu,
                backendLabel = label,
                firstGenPending = true,
            )
            infoRef.set(RuntimeInfo(label, ref.id, LlmDefaults.CONTEXT_TOKENS))
            LoadResult.Loaded(infoRef.get())
        } catch (t: Throwable) {
            LoadResult.Failed(mapLiteRtError(t, LiteRtPhase.LOAD))
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
            Outcome.Failed(mapLiteRtError(t, LiteRtPhase.GENERATE))
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
        if (params.stop.isNotEmpty()) {
            // LiteRT-LM's callback API has no stop-sequence parameter, so the request is refused, not ignored.
            return Outcome.Failed(LlmError.Unsupported("stop sequences are not supported by LiteRT-LM"))
        }
        val res = resident ?: return Outcome.Failed(LlmError.Other("no model loaded"))
        val split = splitTurns(turns)
            ?: return Outcome.Failed(LlmError.Unsupported("the final turn must be a user or tool turn"))

        val started = System.nanoTime()
        var run = runOnce(res, request, split, params)
        if (run is Run.Failed && res.firstGenPending && res.onGpu && !run.emitted &&
            run.error !is OutOfMemoryError
        ) {
            val fallback = fallBackToCpu(res)
            run = if (fallback == null) runOnce(res, request, split, params) else Run.Failed(fallback, false)
        }
        res.firstGenPending = false

        return when (run) {
            is Run.Ok -> Outcome.Completed(
                GenStats(
                    promptTokens = 0,
                    outputTokens = run.outputTokens,
                    millis = (System.nanoTime() - started) / 1_000_000L,
                    backend = res.backendLabel,
                ),
            )
            is Run.Cancelled -> Outcome.Cancelled
            is Run.Failed -> {
                if (run.error is OutOfMemoryError) {
                    releaseResident()
                    Outcome.Failed(LlmError.OutOfMemory)
                } else {
                    Outcome.Failed(mapLiteRtError(run.error, LiteRtPhase.GENERATE))
                }
            }
        }
    }

    private fun runOnce(res: Resident, request: Active, split: TurnSplit, params: GenParams): Run {
        val failure = AtomicReference<Throwable?>(null)
        val emitted = AtomicBoolean(false)
        val capped = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val splitter = ThinkSplitter(params.thinking)

        val conversation = try {
            res.engine.createConversation(
                ConversationConfig(
                    systemInstruction = split.system?.let { Contents.of(it) },
                    initialMessages = split.history.map { toMessage(it) },
                    samplerConfig = SamplerConfig(params.topK, params.topP, params.temperature, randomSeed()),
                    thinkingConfig = ThinkingConfig(params.thinking),
                    maxOutputToken = params.maxOutputTokens,
                ),
            )
        } catch (t: Throwable) {
            return Run.Failed(t, emitted = false)
        }

        request.conversation = conversation
        val baseline = conversation.getTokenCount()
        var outputTokens = 0
        try {
            conversation.sendMessageAsync(
                split.last.text,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        try {
                            if (request.cancelled.get()) return
                            val text = message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            deliver(request, splitter.feed(text), emitted)
                            val produced = conversation.getTokenCount() - baseline
                            if (produced >= params.maxOutputTokens && capped.compareAndSet(false, true)) {
                                conversation.cancelProcess()
                            }
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                            request.cancel()
                        }
                    }

                    override fun onDone() {
                        try {
                            deliver(request, splitter.finish(), emitted)
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        } finally {
                            done.countDown()
                        }
                    }

                    override fun onError(t: Throwable) {
                        failure.compareAndSet(null, t)
                        done.countDown()
                    }
                },
            )
            if (!done.await(MAX_GENERATION_SECONDS, TimeUnit.SECONDS)) {
                return Run.Failed(IllegalStateException("generation timed out"), emitted.get())
            }
            outputTokens = maxOf(0, conversation.getTokenCount() - baseline)
        } catch (t: Throwable) {
            return Run.Failed(t, emitted.get())
        } finally {
            request.conversation = null
            closeQuietly(conversation)
        }

        failure.get()?.let { return Run.Failed(it, emitted.get()) }
        if (request.cancelled.get()) return Run.Cancelled
        return Run.Ok(outputTokens)
    }

    private fun deliver(request: Active, pieces: List<ThinkSplitter.Piece>, emitted: AtomicBoolean) {
        for (piece in pieces) {
            if (request.cancelled.get()) return
            emitted.set(true)
            if (piece.thinking) request.sink.onThinking(piece.text) else request.sink.onToken(piece.text)
        }
    }

    /**
     * Initialises on GPU, then CPU. A GPU engine whose init threw is not closed.
     * A CPU init failure propagates, and that engine is not closed either.
     */
    private fun initWithFallback(path: String): Pair<Engine, Boolean> {
        val gpu = newEngine(path, Backend.GPU())
        try {
            gpu.initialize()
            return gpu to true
        } catch (t: Throwable) {
            // Init threw: per the LiteRT-LM notes, this engine must not be closed. Fall through to CPU.
        }
        val cpu = newEngine(path, Backend.CPU())
        cpu.initialize()
        return cpu to false
    }

    /**
     * Replaces the resident GPU engine with a CPU one. Returns null on success,
     * or the failure. On failure the model is released and stays unloaded.
     */
    private fun fallBackToCpu(res: Resident): Throwable? {
        return try {
            res.engine.close() // this GPU engine initialised, so closing it is safe
            val cpu = newEngine(res.path, Backend.CPU())
            cpu.initialize()
            res.engine = cpu
            res.onGpu = false
            res.backendLabel = CPU_LABEL
            infoRef.set(RuntimeInfo(CPU_LABEL, res.modelId, LlmDefaults.CONTEXT_TOKENS))
            null
        } catch (t: Throwable) {
            releaseResident()
            t
        }
    }

    private fun newEngine(path: String, backend: Backend): Engine = Engine(
        EngineConfig(
            modelPath = path,
            backend = backend,
            maxNumTokens = LlmDefaults.CONTEXT_TOKENS,
            cacheDir = cacheDir.absolutePath,
        ),
    )

    private fun releaseResident() {
        val res = resident ?: return
        resident = null
        infoRef.set(NONE_INFO)
        res.engine.close()
    }

    private fun toMessage(turn: ChatTurn): Message = when (turn.role) {
        ChatTurn.Role.USER -> Message.user(turn.text)
        ChatTurn.Role.ASSISTANT -> Message.model(turn.text)
        ChatTurn.Role.TOOL -> Message.tool(Contents.of(turn.text))
        ChatTurn.Role.SYSTEM -> error("system turns are not history")
    }

    private fun closeQuietly(c: Conversation) {
        try {
            c.close()
        } catch (e: IllegalStateException) {
            // Already closed. Nothing to release.
        }
    }

    private fun randomSeed(): Int = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)

    /** Runs [block] on the runtime thread and waits for it. Runs inline on that thread. */
    private fun <T> onExecutor(block: () -> T): T {
        if (Thread.currentThread() === executorThread) return block()
        try {
            return executor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val GPU_LABEL = "litert-gpu"
        const val CPU_LABEL = "litert-cpu"
        val NONE_INFO = RuntimeInfo(backend = "none", loadedModelId = null, contextTokens = 0)

        /** Upper bound for one generation. It ends the wait only if the engine never reports completion. */
        const val MAX_GENERATION_SECONDS = 900L
    }
}
