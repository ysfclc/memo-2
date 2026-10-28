package app.memo.runtime

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.memo.core.platform.ModelRef
import app.memo.core.voice.TurnState
import app.memo.platform.android.voice.SherpaAsr
import app.memo.platform.android.voice.SherpaTts
import app.memo.platform.android.voice.SherpaVad
import app.memo.platform.android.voice.VoiceModelInstaller
import app.memo.platform.android.voice.VoiceModels
import java.io.File
import java.util.concurrent.Executors

/**
 * The Talk tab's hands-free session (TASKS M2.B1 mini-task 3): it checks the pinned voice
 * models and the active chat model, builds the engines off the main thread, wires
 * [VoiceLoop.forDevice] with the chat joined to [InferenceManager] (one resident model for
 * chat UI and voice), and publishes the small [ui] state the live screen renders.
 *
 * Lifecycle: the session lives as long as [MemoRuntime]. [prepare] builds the engines once
 * (they stay open; a conversation reuses them); [begin]/[end]/[interrupt] drive the loop;
 * [dispose] tears everything down. State writes come back on loop/delivery threads — the
 * Compose snapshot write is thread-safe, the screen renders whatever arrives.
 */
class VoiceSession(
    private val appContext: Context,
    private val inference: InferenceManager,
    private val chatModel: () -> ModelRef?,
) {

    enum class Phase { Idle, Preparing, Ready, Unavailable }

    /** What the live screen renders. */
    data class Ui(
        val phase: Phase = Phase.Idle,
        val turn: TurnState = TurnState.Idle,
        val partial: String = "",
        val caption: String = "",
        val micLevel: Float = 0f,
        val problem: String = "",
    )

    var ui by mutableStateOf(Ui())
        private set

    private val engineExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "memo-voice-engines") }

    private var engines: Engines? = null
    private var loop: VoiceLoop? = null
    private var prepared = false

    private data class Engines(val asr: SherpaAsr, val tts: SherpaTts, val vad: SherpaVad) : AutoCloseable {
        override fun close() {
            runCatching { asr.close() }
            runCatching { tts.close() }
            runCatching { vad.close() }
        }
    }

    private val listener = object : VoiceLoop.Listener {
        override fun onTurnState(state: TurnState) {
            ui = ui.copy(turn = state)
        }

        override fun onPartialTranscript(text: String) {
            ui = ui.copy(partial = text)
        }

        override fun onMicLevel(rms: Float) {
            ui = ui.copy(micLevel = rms)
        }

        override fun onSpeakingSentence(text: String) {
            ui = ui.copy(caption = text)
        }

        override fun onEnded(reason: VoiceLoop.EndReason) {
            ui = ui.copy(turn = TurnState.Idle, partial = "", caption = "", micLevel = 0f)
        }
    }

    /**
     * Builds the engines and the loop once. Safe to call again while preparing or after a
     * failure; [ui] reports Preparing / Ready / Unavailable with the reason.
     */
    fun prepare() {
        synchronized(this) {
            if (prepared) return
            prepared = true
        }
        ui = ui.copy(phase = Phase.Preparing, problem = "")
        engineExecutor.execute {
            val chatRef = chatModel()
            if (chatRef == null) {
                fail("No chat model is active. Pick one in Models, then come back to Talk.")
                return@execute
            }
            val built = runCatching { buildEngines() }
            built.fold(
                onSuccess = { engines ->
                    val loop = VoiceLoop.forDevice(
                        context = appContext,
                        llm = InferenceChatRuntime(inference, chatRef),
                        asr = engines.asr,
                        tts = engines.tts,
                        vad = engines.vad,
                        systemPrompt = { SYSTEM_PROMPT },
                    )
                    loop.setListener(listener)
                    this.loop = loop
                    this.engines = engines
                    ui = ui.copy(phase = Phase.Ready)
                },
                onFailure = { e ->
                    Log.w(TAG, "voice engines unavailable: $e")
                    fail("The pinned voice models are not installed. Download them in Models.")
                },
            )
        }
    }

    /** Opens the conversation: IDLE → LISTENING and the mic starts. False when it could not. */
    fun begin(): Boolean {
        val loop = loop ?: return false
        return loop.startConversation()
    }

    /** The user asked to end the conversation. */
    fun end() {
        loop?.requestEnd()
    }

    /** The user tapped a speaking answer: barge in. */
    fun interrupt() {
        loop?.interrupt()
    }

    /** Closes the loop and the engines. For app shutdown and tests; the tab never calls this. */
    fun dispose() {
        loop?.close()
        loop = null
        engines?.close()
        engines = null
        synchronized(this) { prepared = false }
        ui = Ui()
    }

    private fun fail(problem: String) {
        ui = ui.copy(phase = Phase.Unavailable, problem = problem)
    }

    /** Runs on the engine executor: disk checks and native model loads stay off the main thread. */
    private fun buildEngines(): Engines {
        val installer = VoiceModelInstaller(File(appContext.filesDir, "voice"))
        val asrModel = VoiceModels.entries.first { it.id == ASR_MODEL_ID }
        val ttsModel = VoiceModels.entries.first { it.id == TTS_MODEL_ID }
        val vadModel = VoiceModels.entries.first { it.id == VAD_MODEL_ID }
        val missing = listOf(asrModel, ttsModel, vadModel).filterNot { installer.isInstalled(it) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("missing voice models: ${missing.joinToString { it.id }}")
        }
        return Engines(
            asr = SherpaAsr(installer.dirFor(asrModel)),
            tts = SherpaTts.kokoro(installer.dirFor(ttsModel)),
            vad = SherpaVad(File(installer.dirFor(vadModel), vadModel.requiredFiles.first())),
        )
    }

    private companion object {
        const val TAG = "MemoVoice"
        const val ASR_MODEL_ID = "asr-zipformer-en-20m-mobile"
        const val TTS_MODEL_ID = "tts-kokoro-en-v0-19"
        const val VAD_MODEL_ID = "vad-silero-v5"

        /**
         * The conversation persona. Spoken answers want short sentences; the transcript of what
         * was said stays in the loop's history until the M2.B1 chat-store integration.
         */
        const val SYSTEM_PROMPT =
            "You are Memo, a private on-device assistant. " +
                "Answer as you would speak: short sentences, no lists, no markdown, no links. " +
                "If you are unsure, say so plainly."
    }
}
