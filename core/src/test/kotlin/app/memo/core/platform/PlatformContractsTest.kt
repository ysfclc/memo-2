package app.memo.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val ENVELOPE = """{"target":"example.org","actions":[]}"""

/**
 * Validation tests for the platform data types, plus in-memory fakes that show
 * every interface can be implemented without Android or coroutines.
 */
class PlatformContractsTest {

    // ---- Llm data types ------------------------------------------------

    @Test
    fun modelRefAcceptsCatalogShapeAndRejectsBadFields() {
        val ok = ModelRef("qwen3-0.6b", ModelFormat.LITERTLM, "/data/models/q.litertlm", SHA, 344_671_744L)
        assertEquals("qwen3-0.6b", ok.id)
        assertFailsWith<IllegalArgumentException> {
            ModelRef("Qwen", ModelFormat.GGUF, "/m.gguf", SHA, 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            ModelRef("ok", ModelFormat.GGUF, "/m.gguf", SHA.uppercase(), 1L)
        }
        assertFailsWith<IllegalArgumentException> { ModelRef("ok", ModelFormat.GGUF, " ", SHA, 1L) }
        assertFailsWith<IllegalArgumentException> { ModelRef("ok", ModelFormat.GGUF, "/m", SHA, -1L) }
    }

    @Test
    fun chatTurnRequiresToolNameOnlyForToolRole() {
        assertEquals("search", ChatTurn(ChatTurn.Role.TOOL, "result", "search").toolName)
        assertFailsWith<IllegalArgumentException> { ChatTurn(ChatTurn.Role.TOOL, "result") }
        assertFailsWith<IllegalArgumentException> { ChatTurn(ChatTurn.Role.USER, "hi", "search") }
        assertFailsWith<IllegalArgumentException> { ChatTurn(ChatTurn.Role.TOOL, "x", " ") }
    }

    @Test
    fun genParamsValidatesRanges() {
        assertEquals(700, GenParams.ROUTINE.maxOutputTokens)
        assertFailsWith<IllegalArgumentException> { GenParams(2.5, 40, 0.9, 700, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenParams(Double.NaN, 40, 0.9, 700, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenParams(0.3, 0, 0.9, 700, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenParams(0.3, 40, 0.0, 700, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenParams(0.3, 40, 1.1, 700, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenParams(0.3, 40, 0.9, 0, false, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            GenParams(0.3, 40, 0.9, 700, false, List(9) { "s" })
        }
        assertFailsWith<IllegalArgumentException> { GenParams(0.3, 40, 0.9, 700, false, listOf("")) }
        assertFailsWith<IllegalArgumentException> {
            GenParams(0.3, 40, 0.9, 700, false, listOf("x".repeat(65)))
        }
    }

    @Test
    fun genStatsAndRuntimeInfoValidate() {
        assertFailsWith<IllegalArgumentException> { GenStats(-1, 0, 0L, "litert") }
        assertFailsWith<IllegalArgumentException> { GenStats(1, 1, -5L, "litert") }
        assertFailsWith<IllegalArgumentException> { GenStats(1, 1, 5L, "") }
        assertEquals("litert", GenStats(3, 4, 10L, "litert").backend)
        assertNull(RuntimeInfo("litert", null, 0).loadedModelId)
    }

    @Test
    fun llmErrorsAreExhaustiveSealedVariants() {
        val errors: List<LlmError> = listOf(
            LlmError.OutOfMemory,
            LlmError.ModelLoad("bad header"),
            LlmError.Unsupported("gguf"),
            LlmError.Cancelled,
            LlmError.Other("boom"),
        )
        val names = errors.map { e ->
            when (e) {
                LlmError.OutOfMemory -> "oom"
                is LlmError.ModelLoad -> "load"
                is LlmError.Unsupported -> "unsupported"
                LlmError.Cancelled -> "cancelled"
                is LlmError.Other -> "other"
            }
        }
        assertEquals(listOf("oom", "load", "unsupported", "cancelled", "other"), names)
    }

    // ---- Speech / Vault / Device / Scheduling data types ---------------

    @Test
    fun voiceInfoValidatesLocale() {
        assertEquals("en-US", VoiceInfo("v1", "Amy", "en-US").locale)
        assertFailsWith<IllegalArgumentException> { VoiceInfo("v1", "Amy", "english") }
        assertFailsWith<IllegalArgumentException> { VoiceInfo("", "Amy", "en") }
    }

    @Test
    fun vaultNamesFollowThePattern() {
        assertEquals("workspace.syn.json", VaultName.validate("workspace.syn.json"))
        assertEquals("a".repeat(64), VaultName.validate("a".repeat(64)))
        assertFailsWith<IllegalArgumentException> { VaultName.validate("a".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { VaultName.validate("") }
        assertFailsWith<IllegalArgumentException> { VaultName.validate("../escape") }
        assertFailsWith<IllegalArgumentException> { VaultName.validate("Upper") }
        assertFailsWith<IllegalArgumentException> { VaultName.validate("a/b") }
    }

    @Test
    fun webAndAppTasksValidateTheirFields() {
        val web = WebTask("run-1", "step_1", "linkedin", ENVELOPE, 120_000L)
        assertEquals("linkedin", web.profileId)
        assertFailsWith<IllegalArgumentException> { WebTask("run-1", "step_1", "linkedin", "[]", 120_000L) }
        assertFailsWith<IllegalArgumentException> { WebTask("run-1", "step_1", "linkedin", "{bad", 120_000L) }
        assertFailsWith<IllegalArgumentException> { WebTask("run 1", "step_1", "linkedin", ENVELOPE, 120_000L) }
        assertFailsWith<IllegalArgumentException> { WebTask("run-1", "step_1", "linkedin", ENVELOPE, 999L) }
        assertFailsWith<IllegalArgumentException> { WebTask("run-1", "step_1", "linkedin", ENVELOPE, 300_001L) }

        val app = AppTask("run-1", "step_1", "com.example.mail", ENVELOPE, 300_000L)
        assertEquals("com.example.mail", app.packageName)
        assertFailsWith<IllegalArgumentException> { AppTask("run-1", "step_1", "nodots", ENVELOPE, 60_000L) }
        assertFailsWith<IllegalArgumentException> { AppTask("run-1", "step_1", "com.1bad", ENVELOPE, 60_000L) }
    }

    @Test
    fun externalResultsValidateJsonAndReasons() {
        assertEquals("[]", ExternalResult.Done("out").evidenceJson)
        assertFailsWith<IllegalArgumentException> { ExternalResult.Done("out", "{}") }
        assertFailsWith<IllegalArgumentException> { ExternalResult.Failed(" ") }
        assertFailsWith<IllegalArgumentException> { ExternalResult.NeedsUser("") }
    }

    @Test
    fun installedAppAndNoticeValidate() {
        assertTrue(InstalledApp("com.example.app", "Example", "1.0", true).enabled)
        assertFailsWith<IllegalArgumentException> { InstalledApp("bad", "Example", "1.0", true) }
        assertFailsWith<IllegalArgumentException> { InstalledApp("com.example.app", " ", "1.0", true) }

        val notice = MemoNotice("approve.run-1", "Approve send", "Review the envelope.", NoticeKind.APPROVAL, false)
        assertEquals(NoticeKind.APPROVAL, notice.kind)
        assertFailsWith<IllegalArgumentException> {
            MemoNotice("n", "t", "x".repeat(501), NoticeKind.INFO, true)
        }
        assertFailsWith<IllegalArgumentException> { MemoNotice("n", "t".repeat(121), "b", NoticeKind.INFO, true) }
        assertFailsWith<IllegalArgumentException> { MemoNotice("has space", "t", "b", NoticeKind.INFO, true) }
    }

    @Test
    fun scheduledJobValidatesIdKindRepeatAndPayload() {
        val once = ScheduledJob("digest.daily", "digest", 1_000L, null, false, "{}")
        assertNull(once.repeatEveryMillis)
        val repeating = ScheduledJob("sync", "metrics_sync", 0L, ScheduledJob.MIN_REPEAT_MILLIS, true, """{"k":1}""")
        assertTrue(repeating.requiresNetwork)
        assertFailsWith<IllegalArgumentException> { ScheduledJob("Bad Id", "digest", 0L, null, false, "{}") }
        assertFailsWith<IllegalArgumentException> { ScheduledJob("ok", "1digest", 0L, null, false, "{}") }
        assertFailsWith<IllegalArgumentException> { ScheduledJob("ok", "digest", -1L, null, false, "{}") }
        assertFailsWith<IllegalArgumentException> {
            ScheduledJob("ok", "digest", 0L, ScheduledJob.MIN_REPEAT_MILLIS - 1, false, "{}")
        }
        assertFailsWith<IllegalArgumentException> { ScheduledJob("ok", "digest", 0L, null, false, "[1]") }
    }

    // ---- Fakes implementing every interface -----------------------------

    @Test
    fun fakeLlmRuntimeStreamsCompletesAndCancelsWithoutOverlap() {
        val runtime = FakeLlmRuntime()
        assertIs<LoadResult.Failed>(runtime.load(modelRef(ModelFormat.GGUF)))
        assertIs<LoadResult.Loaded>(runtime.load(modelRef(ModelFormat.LITERTLM)))
        assertEquals("qwen3-0.6b", runtime.info().loadedModelId)

        val sink = RecordingSink()
        val handle = runtime.chat(listOf(ChatTurn(ChatTurn.Role.USER, "hi")), GenParams.ROUTINE, sink)
        assertFailsWith<IllegalStateException> {
            runtime.chat(listOf(ChatTurn(ChatTurn.Role.USER, "again")), GenParams.ROUTINE, RecordingSink())
        }
        runtime.emitToken("he")
        runtime.emitToken("llo")
        runtime.finish()
        assertEquals("hello", sink.text)
        assertEquals(1, sink.completions)

        val sink2 = RecordingSink()
        runtime.chat(listOf(ChatTurn(ChatTurn.Role.USER, "again")), GenParams.ROUTINE, sink2)
        handle.cancel() // stale handle: must not touch the new request
        assertEquals(0, sink2.errors.size)
        runtime.cancelActive()
        assertEquals(listOf<LlmError>(LlmError.Cancelled), sink2.errors)
        runtime.close()
        assertNull(runtime.info().loadedModelId)
    }

    @Test
    fun fakeVaultRoundTripsAndListsSorted() {
        val vault = FakeVault()
        vault.write("runs.json", "[]".toByteArray())
        vault.write("audit.json", "{}".toByteArray())
        vault.write("workspace.syn.json", "{}".toByteArray())
        assertEquals("[]", String(vault.read("runs.json")!!))
        assertEquals(listOf("audit.json", "runs.json", "workspace.syn.json"), vault.list(""))
        assertEquals(listOf("workspace.syn.json"), vault.list("workspace"))
        vault.delete("runs.json")
        vault.delete("runs.json")
        assertNull(vault.read("runs.json"))
        assertFailsWith<IllegalArgumentException> { vault.write("Bad Name", ByteArray(0)) }
        val corrupt: Throwable = VaultException("corrupt entry")
        assertEquals("corrupt entry", corrupt.message)
    }

    @Test
    fun fakeWebAndAppChannelsDeliverExactlyOnceAndCancel() {
        val web = FakeWebChannel()
        val appCh = FakeAppChannel()
        val collected = mutableListOf<Pair<String, ExternalResult>>()
        val callback = object : ExternalCallback {
            override fun deliver(ticket: String, result: ExternalResult) {
                collected += ticket to result
            }
        }

        val webTicket = web.start(WebTask("run-1", "step_1", "linkedin", ENVELOPE, 120_000L), callback)
        val appTicket = appCh.start(AppTask("run-1", "step_2", "com.example.mail", ENVELOPE, 60_000L), callback)
        assertTrue(webTicket.isNotBlank() && appTicket.isNotBlank() && webTicket != appTicket)

        web.reveal(webTicket)
        web.cancel("unknown")
        assertEquals(listOf("reveal:$webTicket", "cancel:unknown"), web.log)

        web.finish(webTicket, ExternalResult.Done("ok", """[{"n":1}]"""))
        appCh.finish(appTicket, ExternalResult.NeedsUser("login required"))
        assertEquals(2, collected.size)
        assertEquals(webTicket, collected[0].first)
        assertIs<ExternalResult.Done>(collected[0].second)
        assertIs<ExternalResult.NeedsUser>(collected[1].second)

        // A finished ticket delivers nothing more: exactly once per ticket.
        web.finish(webTicket, ExternalResult.Failed("late"))
        assertEquals(2, collected.size)
    }

    @Test
    fun fakeSpeechVoiceAndWakeWordImplementTheirContracts() {
        val speechIn = FakeSpeechIn()
        val partials = mutableListOf<String>()
        speechIn.start(object : SpeechListener {
            override fun onPartial(text: String) { partials += text }
            override fun onFinal(text: String) {}
            override fun onLevel(rms: Float) {}
            override fun onError(reason: String) {}
        })
        speechIn.emitPartial("buy milk")
        assertEquals(listOf("buy milk"), partials)
        assertFailsWith<IllegalStateException> {
            speechIn.start(object : SpeechListener {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onLevel(rms: Float) {}
                override fun onError(reason: String) {}
            })
        }
        speechIn.stop()
        assertEquals(0, speechIn.transcribe(ShortArray(16_000), 16_000).length)

        val voiceActivity = object : VoiceActivity {
            override fun isSpeech(frame: ShortArray): Boolean = frame.any { it > 1000 }
        }
        assertTrue(voiceActivity.isSpeech(shortArrayOf(0, 5000)))
        assertFalse(voiceActivity.isSpeech(ShortArray(160)))

        val wake = FakeWakeWord()
        val heard = mutableListOf<String>()
        wake.arm("memo", object : WakeWordListener {
            override fun onDetected(keyword: String) { heard += keyword }
            override fun onError(reason: String) {}
        })
        wake.detect()
        wake.disarm()
        assertEquals(listOf("memo"), heard)
    }

    @Test
    fun fakeSpeechOutStopsPreviousUtterance() {
        val out = FakeSpeechOut()
        val events = mutableListOf<String>()
        val listener = object : SpeechOutListener {
            override fun onStart() { events += "start" }
            override fun onDone() { events += "done" }
            override fun onError(reason: String) { events += "error:$reason" }
        }
        out.speak("first", null, 1.0f, listener)
        out.speak("second", "amy", 1.25f, listener)
        out.stop()
        assertEquals(listOf("start", "error:stopped", "start", "error:stopped"), events)
        assertEquals("amy", out.voices().single().id)
        assertFailsWith<IllegalArgumentException> { out.speakChecked(0f) }
    }

    @Test
    fun fakeDeviceSchedulerAndClockImplementTheirContracts() {
        val clock = object : Clock {
            override fun now(): Long = 1_700_000_000_000L
        }
        assertEquals(1_700_000_000_000L, clock.now())

        val notifier = FakeNotifier()
        notifier.post(MemoNotice("n1", "Title", "Body", NoticeKind.INFO, false))
        notifier.cancel("n1")
        notifier.cancel("missing")
        assertEquals(listOf("post:n1", "cancel:n1", "cancel:missing"), notifier.log)

        val inspector = object : PackageInspector {
            override fun installedPackages(): List<InstalledApp> =
                listOf(InstalledApp("com.example.mail", "Mail", "2.1", true))
            override fun webViewProvider(): String? = "com.google.android.webview"
        }
        assertEquals("com.example.mail", inspector.installedPackages().single().packageName)
        assertEquals("com.google.android.webview", inspector.webViewProvider())

        val scheduler = FakeScheduler()
        scheduler.schedule(ScheduledJob("digest.daily", "digest", 0L, ScheduledJob.MIN_REPEAT_MILLIS, false, "{}"))
        scheduler.cancel("digest.daily")
        assertEquals(listOf("schedule:digest.daily", "cancel:digest.daily"), scheduler.log)
    }

    // ---- Helpers --------------------------------------------------------

    private fun modelRef(format: ModelFormat) =
        ModelRef("qwen3-0.6b", format, "/models/qwen3.bin", SHA, 344_671_744L)

    private fun SpeechOut.speakChecked(speed: Float) {
        speak("x", null, speed, object : SpeechOutListener {
            override fun onStart() {}
            override fun onDone() {}
            override fun onError(reason: String) {}
        })
    }

    private class RecordingSink : ChatSink {
        val text = StringBuilder()
        var completions = 0
        val errors = mutableListOf<LlmError>()
        override fun onToken(text: String) { this.text.append(text) }
        override fun onThinking(text: String) {}
        override fun onComplete(stats: GenStats) { completions++ }
        override fun onError(e: LlmError) { errors += e }
    }

    /** Fake runtime that holds one request until the test finishes or cancels it. */
    private class FakeLlmRuntime : LlmRuntime {
        override val formats: Set<ModelFormat> = setOf(ModelFormat.LITERTLM)
        private var loaded: ModelRef? = null
        private var active: ChatSink? = null
        private var activeId = 0

        override fun load(ref: ModelRef): LoadResult {
            if (ref.format !in formats) return LoadResult.Failed(LlmError.Unsupported(ref.format.name))
            loaded = ref
            return LoadResult.Loaded(info())
        }

        override fun chat(turns: List<ChatTurn>, params: GenParams, sink: ChatSink): RequestHandle {
            check(loaded != null) { "no model loaded" }
            check(active == null) { "one request at a time" }
            active = sink
            val id = ++activeId
            return object : RequestHandle {
                override fun cancel() {
                    if (active != null && id == activeId) cancelActive()
                }
            }
        }

        fun emitToken(text: String) = active?.onToken(text)

        fun finish() {
            val sink = active ?: return
            active = null
            sink.onComplete(GenStats(4, 2, 5L, "fake"))
        }

        fun cancelActive() {
            val sink = active ?: return
            active = null
            sink.onError(LlmError.Cancelled)
        }

        override fun unload() {
            cancelActive()
            loaded = null
        }

        override fun info(): RuntimeInfo = RuntimeInfo("fake", loaded?.id, if (loaded == null) 0 else 4096)

        override fun close() = unload()
    }

    private class FakeVault : Vault {
        private val store = sortedMapOf<String, ByteArray>()

        override fun read(name: String): ByteArray? = store[VaultName.validate(name)]?.copyOf()

        override fun write(name: String, bytes: ByteArray) {
            store[VaultName.validate(name)] = bytes.copyOf()
        }

        override fun delete(name: String) {
            store.remove(VaultName.validate(name))
        }

        override fun list(prefix: String): List<String> = store.keys.filter { it.startsWith(prefix) }
    }

    private class FakeWebChannel : WebChannel {
        val log = mutableListOf<String>()
        private val tasks = mutableMapOf<String, ExternalCallback>()
        private var counter = 0

        override fun start(task: WebTask, callback: ExternalCallback): String {
            val ticket = "web-${++counter}"
            tasks[ticket] = callback
            return ticket
        }

        override fun cancel(ticket: String) {
            log += "cancel:$ticket"
            tasks.remove(ticket)?.deliver(ticket, ExternalResult.Failed("cancelled"))
        }

        override fun reveal(ticket: String) {
            log += "reveal:$ticket"
        }

        fun finish(ticket: String, result: ExternalResult) {
            tasks.remove(ticket)?.deliver(ticket, result)
        }
    }

    private class FakeAppChannel : AppChannel {
        private val tasks = mutableMapOf<String, ExternalCallback>()
        private var counter = 0

        override fun start(task: AppTask, callback: ExternalCallback): String {
            val ticket = "app-${++counter}"
            tasks[ticket] = callback
            return ticket
        }

        override fun cancel(ticket: String) {
            tasks.remove(ticket)
        }

        override fun reveal(ticket: String) {}

        fun finish(ticket: String, result: ExternalResult) {
            tasks.remove(ticket)?.deliver(ticket, result)
        }
    }

    private class FakeSpeechIn : SpeechIn {
        private var listener: SpeechListener? = null

        override fun start(listener: SpeechListener) {
            check(this.listener == null) { "already running" }
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        override fun transcribe(pcm16: ShortArray, sampleRate: Int): String = ""

        fun emitPartial(text: String) = listener?.onPartial(text)
    }

    private class FakeSpeechOut : SpeechOut {
        private var current: SpeechOutListener? = null

        override fun speak(text: String, voiceId: String?, speed: Float, listener: SpeechOutListener) {
            require(speed in 0.25f..4.0f) { "speed must be in 0.25..4.0" }
            current?.onError("stopped")
            current = listener
            listener.onStart()
        }

        override fun stop() {
            current?.onError("stopped")
            current = null
        }

        override fun voices(): List<VoiceInfo> = listOf(VoiceInfo("amy", "Amy", "en-US"))
    }

    private class FakeWakeWord : WakeWord {
        private var keyword: String? = null
        private var listener: WakeWordListener? = null

        override fun arm(keyword: String, listener: WakeWordListener) {
            this.keyword = keyword
            this.listener = listener
        }

        override fun disarm() {
            keyword = null
            listener = null
        }

        fun detect() {
            keyword?.let { listener?.onDetected(it) }
        }
    }

    private class FakeNotifier : Notifier {
        val log = mutableListOf<String>()
        override fun post(n: MemoNotice) { log += "post:${n.id}" }
        override fun cancel(id: String) { log += "cancel:$id" }
    }

    private class FakeScheduler : Scheduler {
        val log = mutableListOf<String>()
        override fun schedule(job: ScheduledJob) { log += "schedule:${job.id}" }
        override fun cancel(id: String) { log += "cancel:$id" }
    }
}
