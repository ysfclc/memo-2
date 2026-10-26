package app.memo.voice

import app.memo.core.plan.CommandGrammar
import app.memo.core.plan.PlanProposalParser
import app.memo.core.engine.WorkflowEngine
import app.memo.core.prompts.Prompts
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log

/**
 * Voice-first front-end: captures user speech, parses it through
 * CommandGrammar, and dispatches the resulting VoiceCommand into the
 * engine as the next step.
 *
 * Commands that produce side effects (Browser, AppAutomation) are only
 * dispatched after a HUMAN_APPROVAL gate has been approved — the engine
 * enforces this. Pure reason / metric / data_persist steps can be
 * enqueued directly.
 */
class LocalAssistant(
    private val engine: WorkflowEngine,
    private val onCommand: (CommandGrammar.VoiceCommand) -> Unit,
) {

    /** Start an activity that captures user speech and returns the result. */
    fun startCapture(activity: android.app.Activity) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a memo command")
        }
        activity.startActivityForResult(intent, 42)
    }

    /** Called when the speech recognizer returns. Parses the best result
     * through CommandGrammar and dispatches the VoiceCommand. */
    fun onResponse(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val raw = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.takeIf { it.isNotEmpty() }
            ?.first() ?: return
        parseAndDispatch(raw)
    }

    /** Parse raw speech and dispatch the resulting VoiceCommand into the engine. */
    private fun parseAndDispatch(raw: String) {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()

        val grammarResult = CommandGrammar.parse(raw, now, zone)

        when (grammarResult) {
            is CommandGrammar.VoiceCommand.Cancel -> {
                // Cancel the current step / run.
                engine.markStepFailed("voice", "user cancelled: ${raw.take(60)}")
            }
            is CommandGrammar.VoiceCommand.Reminder -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.Reminder
                // Reminders are outside the hash‑chain; surface as a notification.
                onCommand(cmd)
            }
            is CommandGrammar.VoiceCommand.CaptureTask -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.CaptureTask
                onCommand(cmd)
            }
            is CommandGrammar.VoiceCommand.CapturePerson -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.CapturePerson
                onCommand(cmd)
            }
            is CommandGrammar.VoiceCommand.ComposeDraft -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.ComposeDraft
                onCommand(cmd)
            }
            else -> {
                engine.markStepFailed("voice", "unrecognized command: ${raw.take(60)}")
            }
        }
    }
}