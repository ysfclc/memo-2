package app.memo.ui.voice

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.memo.core.voice.SentenceChunker
import app.memo.platform.android.voice.AudioPlayback
import app.memo.platform.android.voice.SherpaLoopPlayback
import app.memo.platform.android.voice.SherpaSpeakEngine
import app.memo.platform.android.voice.SherpaTts
import app.memo.platform.android.voice.VoiceModelInstaller
import app.memo.platform.android.voice.VoiceModels
import app.memo.runtime.LoopPlayback
import app.memo.runtime.SpeakEngine
import app.memo.ui.theme.MemoTheme
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where [ReadAloud] obtains its voice. The device implementation reads the pinned
 * `tts-kokoro-en-v0-19` model through [VoiceModelInstaller]; tests feed fakes, which is what
 * keeps the read-aloud state machine JVM-testable.
 */
interface ReadAloudVoiceSource {

    /** True when the pinned Kokoro model is installed on device (a cheap marker-file check). */
    fun installed(): Boolean

    /**
     * Opens the engine for speaking. Blocking native work: [ReadAloud] calls this only on its
     * own executor, never on the main thread. Throws when the model cannot load despite the
     * install check.
     */
    fun load(): ReadAloudVoice
}

/** One loaded Kokoro voice: the sentence speak port plus its teardown (the model is native). */
interface ReadAloudVoice : AutoCloseable {

    /** Speaks one sentence's audio as synthesized chunks ([SpeakEngine] contract). */
    val speak: SpeakEngine
}

/**
 * Read-aloud (TASKS M2.B1, "read-aloud action on any message"): speaks one chat message
 * through the pinned Kokoro voice, one sentence at a time, through sherpa-onnx only — never
 * the Android system speech engine.
 *
 * A self-contained component: it receives its [ReadAloudVoiceSource] and [LoopPlayback] ports
 * instead of reaching into the runtime. The message text is cut by the shared
 * [SentenceChunker] — the same sentence rules the conversation loop speaks — and the executor
 * renders the plan sentence by sentence; synthesis and the blocking playback write both run
 * there, and every state transition happens there too, like the conversation loop's thread
 * discipline. [state], [available] and [requestId] are Compose-observable — a background
 * snapshot write is thread-safe ([requestId] is set at request time on the caller thread) — so
 * the chat thread's anchor renders them directly.
 *
 * State machine (all transitions on the executor): [State.Idle] → [State.Speaking] →
 * [State.Done], with [State.Unavailable] when the pinned model is missing, [State.Failed] when
 * the engine or playback could not run, and [State.Cancelled] from cancel/focus-loss/teardown;
 * a new read starts from any state. There is no pause (the anchor offers start/stop and the
 * playback port has no pause contract) and no clock (nothing in the machine is time-driven).
 *
 * @param voiceSource the pinned-model checker + Kokoro loader. Its calls run on [executor].
 * @param playback the speaker output. [LoopPlayback.stop] must break a blocking write — that
 *   is how a cancel reaches an in-flight sentence.
 * @param executor the single background thread all engine and machine work runs on.
 */
class ReadAloud(
    private val voiceSource: ReadAloudVoiceSource,
    private val playback: LoopPlayback,
    private val executor: Executor,
) {

    /** What the caller renders for a read. */
    sealed interface State {

        /** Waiting for the next read request. */
        data object Idle : State

        /** [sentence] (0-based index within the message's plan) is being spoken. */
        data class Speaking(val index: Int, val sentence: String) : State

        /** The pinned Kokoro model is missing; the caller shows [reason] verbatim. */
        data class Unavailable(val reason: String) : State

        /** This read failed (engine load, focus refusal, playback lost); the caller shows why. */
        data class Failed(val reason: String) : State

        /** Audio stopped before the message finished (cancel, focus loss, teardown). */
        data object Cancelled : State

        /** Every sentence of the message was spoken. */
        data object Done : State
    }

    private val stateField = mutableStateOf<State>(State.Idle)

    /** The render state; every write happens on [executor]. */
    val state: State
        get() = stateField.value

    private val availableField = mutableStateOf(false)

    /**
     * True when the pinned Kokoro model is present on this device. False until the first
     * [probe] (or a read's own check); the anchor greys the button on false instead of hiding
     * it, so installing the model in Models leads to a retry.
     */
    val available: Boolean
        get() = availableField.value

    private val requestIdField = mutableStateOf<String?>(null)

    /**
     * The caller key of the latest read request (the chat message id). The anchor keeps its
     * one-line status on the bubble the button was pressed on — a screen-wide machine state
     * must not leak onto every bubble.
     */
    val requestId: String?
        get() = requestIdField.value

    /** Volatile: set from any thread (cancel, teardown), read on the executor. */
    @Volatile private var stopPlayback = false

    /** Volatile: guards [probe]/[speak]/[cancel] against calls after the screen is gone. */
    @Volatile private var closed = false

    /** Loaded engine: executor thread only. */
    private var engine: ReadAloudVoice? = null

    /** True from [speak] acceptance until the read's terminal block; guarded by [requestGate]. */
    private var busy = false
    private val requestGate = Any()

    /** The plan feed is the shared sentence chunker (one push+flush per read). */
    private val chunker = SentenceChunker()

    /**
     * Cheap availability probe for the anchor's icon — one [executor] job that only checks the
     * install. Not a gate for [speak], which re-checks per read (the model may be downloaded
     * in Models between the probe and the press).
     */
    fun probe() {
        if (closed) return
        executor.execute { availableField.value = voiceSource.installed() }
    }

    /**
     * Reads [text] through the pinned Kokoro voice. One read at a time: a request while one is
     * in flight is dropped — the anchor's button is the stop control first, so the way to stop
     * a read is [cancel], and starting again afterwards is [speak] from the new state.
     *
     * @param requestId the caller's key for this read (the chat message id); [state] of the
     *   Unavailable/Failed render stays bound to the bubble that asked.
     */
    fun speak(text: String, requestId: String) {
        if (closed) return
        if (text.isBlank()) return
        synchronized(requestGate) {
            if (busy) return
            busy = true
        }
        requestIdField.value = requestId
        executor.execute { runRead(text) }
    }

    /**
     * Stops the current read. Safe from any thread, at any time, repeatedly: [LoopPlayback.stop]
     * breaks the in-flight blocking write at once, and nothing is queued here — the read
     * observes the flag between sentences and inside every chunk, and its terminal block lands
     * the machine on [State.Cancelled]. A cancel with no read in flight is a no-op.
     */
    fun cancel() {
        if (closed) return
        stopPlayback = true
        playback.stop()
    }

    /** Audio focus was lost (the platform playback hook): the speech cannot be heard → cancel. */
    fun onFocusLost() = cancel()

    /**
     * Full teardown, owned by the chat screen's DisposableEffect. The engine is released on the
     * executor, ordered after whatever read is aborting, so a generate call never races the
     * native model teardown.
     */
    fun close() {
        if (closed) return
        closed = true
        stopPlayback = true
        playback.stop()
        executor.execute {
            runCatching { engine?.close() }
            engine = null
        }
    }

    /** The read request itself; [executor] thread only, one at a time ([speak] is the gate). */
    private fun runRead(text: String) {
        stopPlayback = false
        stateField.value = State.Idle
        if (!voiceSource.installed()) {
            availableField.value = false
            releaseRequest()
            stateField.value = State.Unavailable(UNAVAILABLE_REASON)
            return
        }
        availableField.value = true
        val voice = ensureEngine()
        if (voice == null) { // [ensureEngine] already posted the reason.
            releaseRequest()
            return
        }

        val sentences = sentencePlan(text)
        var failure: String? = null
        for ((index, sentence) in sentences.withIndex()) {
            if (stopPlayback) break
            stateField.value = State.Speaking(index, sentence)
            playSentence(voice, sentence) { reason -> failure = reason }
            if (stopPlayback || failure != null) break
        }

        releaseRequest()
        stateField.value = when {
            stopPlayback -> State.Cancelled // covers the empty plan too: stopping wins over done
            failure != null -> State.Failed(failure)
            else -> State.Done
        }
    }

    private fun releaseRequest() {
        synchronized(requestGate) { busy = false }
    }

    /** Loads or reuses the Kokoro engine; null means the machine already showed the reason. */
    private fun ensureEngine(): ReadAloudVoice? {
        engine?.let { return it }
        return try {
            voiceSource.load().also { engine = it }
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            stateField.value = if (stopPlayback) {
                State.Cancelled // cancelled while the engine was loading: not a failure
            } else {
                State.Failed("$LOAD_FAILED_PREFIX$detail")
            }
            null
        }
    }

    /**
     * The speakable plan for [text]: the whole message through the shared [SentenceChunker],
     * so paragraph breaks, markdown and abbreviations come out exactly as SentenceChunkerTest
     * pins them. A blank-after-cleaning message has an empty plan and reads as Done.
     */
    private fun sentencePlan(text: String): List<String> {
        val plan = ArrayList<String>()
        plan += chunker.push(text)
        plan += chunker.flush()
        return plan
    }

    /**
     * One sentence: streaming synthesis straight into [Playback], chunk by chunk. Reports every
     * reason the engine must stop early (playback refused, playback stopped) as a failure.
     */
    private fun playSentence(voice: ReadAloudVoice, sentence: String, onRefused: (String) -> Unit) {
        voice.speak.generate(sentence) { samples ->
            if (stopPlayback) return@generate true
            if (!playback.isOpen) {
                when (val opened = playback.open(voice.speak.sampleRate)) {
                    is AudioPlayback.Open.Unavailable -> {
                        onRefused(opened.reason)
                        return@generate true
                    }
                    AudioPlayback.Open.Opened -> {}
                }
            }
            if (playback.write(samples) < 0) {
                onRefused(PLAYBACK_STOPPED_REASON)
                return@generate true
            }
            false
        }
    }

    companion object {

        /**
         * The caller's [State.Unavailable] message, verbatim: where the pinned voice comes from
         * and what to do about it.
         */
        const val UNAVAILABLE_REASON = "Read-aloud needs the Kokoro voice model. Download it in Models."

        /** The engine could not load despite the install check; the detail follows the prefix. */
        const val LOAD_FAILED_PREFIX = "Could not load the Kokoro voice: "

        /** The write stopped mid-chunk without a cancel (track lost); reported as a failure. */
        const val PLAYBACK_STOPPED_REASON = "Playback stopped mid-sentence."
    }
}

/**
 * The ChatThread anchor's owner: one [ReadAloud] per chat screen. The Kokoro engine loads on
 * first use and is released here on dispose (it is per-dialogue, not resident — the voice tab's
 * runtime owns no shared TTS surface to borrow), which makes the first read of a visit pay the
 * native model load once.
 */
@Composable
fun rememberReadAloud(): ReadAloud {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor { Thread(it, "memo-read-aloud") } }
    val readAloud = remember(executor) {
        var owned: ReadAloud? = null
        val playback = SherpaLoopPlayback(context) { owned?.onFocusLost() }
        ReadAloud(KokoroVoiceSource(context), playback, executor).also { owned = it }
    }
    DisposableEffect(executor) {
        onDispose {
            readAloud.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(readAloud) { readAloud.probe() }
    return readAloud
}

/**
 * The read-aloud icon button in a chat message's actions row (user and assistant, D-051) (the anchor's control):
 * starts a read; while it is speaking it is the stop control; greyed while the pinned model is
 * missing. Blank messages render nothing — there is nothing to read.
 */
@Composable
fun ReadAloudMessageButton(readAloud: ReadAloud, messageId: String, text: String) {
    if (text.isBlank()) return
    val colors = MemoTheme.colors
    val speaking = readAloud.state is ReadAloud.State.Speaking
    IconButton(
        onClick = {
            if (speaking) readAloud.cancel() else readAloud.speak(text, messageId)
        },
    ) {
        Icon(
            imageVector = if (speaking) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = if (speaking) "Stop reading aloud" else "Read message aloud",
            tint = when {
                speaking -> colors.accent
                readAloud.available -> colors.inkMuted
                else -> colors.inkFaint
            },
        )
    }
}

/**
 * The caller's render of the machine's [ReadAloud.State.Unavailable] / [ReadAloud.State.Failed]:
 * an honest one-line status under the bubble of the message the read was asked for
 * ([requestId] binds it to that bubble). Nothing while the read is idle, speaking, cancelled
 * or done — the button carries those.
 */
@Composable
fun ReadAloudMessageLine(readAloud: ReadAloud, requestId: String) {
    if (readAloud.requestId != requestId) return
    val colors = MemoTheme.colors
    when (val state = readAloud.state) {
        is ReadAloud.State.Unavailable -> Text(
            text = state.reason,
            style = MemoTheme.typography.caption,
            color = colors.inkFaint,
        )
        is ReadAloud.State.Failed -> Text(
            text = state.reason,
            style = MemoTheme.typography.caption,
            color = colors.danger,
        )
        ReadAloud.State.Idle, ReadAloud.State.Cancelled, ReadAloud.State.Done -> {}
        is ReadAloud.State.Speaking -> {}
    }
}

/**
 * The device [ReadAloudVoiceSource]: the pinned `tts-kokoro-en-v0-19` model checked through
 * [VoiceModelInstaller.isInstalled] and opened through [SherpaTts.kokoro] — the same
 * installation pattern [app.memo.runtime.VoiceSession.buildEngines] uses for its own engines.
 */
private class KokoroVoiceSource(context: Context) : ReadAloudVoiceSource {

    private val installer = VoiceModelInstaller(File(context.applicationContext.filesDir, "voice"))

    override fun installed(): Boolean = installer.isInstalled(KOKORO)

    /** Blocking native load; [ReadAloud] calls this only on its executor. */
    override fun load(): ReadAloudVoice = KokoroVoice(SherpaTts.kokoro(installer.dirFor(KOKORO)))

    private class KokoroVoice(private val tts: SherpaTts) : ReadAloudVoice {
        override val speak = SherpaSpeakEngine(tts)
        override fun close() = tts.close()
    }

    private companion object {
        val KOKORO = VoiceModels.entries.first { it.id == KOKORO_MODEL_ID }
        const val KOKORO_MODEL_ID = "tts-kokoro-en-v0-19"
    }
}
