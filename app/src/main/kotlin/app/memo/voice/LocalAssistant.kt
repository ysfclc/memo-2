package app.memo.voice

import app.memo.core.CommandGrammar
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.engine.WorkflowEngine
import app.memo.core.prompts.Prompts
import app.memo.core.domain.EvidenceKind
import android.speech.RecognizerIntent
import android.content.Intent

/**
 * Voice-first front-end: captures user speech, parses it through
 * CommandGrammar, and dispatches the resulting VoiceCommand into the
 * engine as the next step.
 * 
 * Commands that produce side effects (Browser, AppAutomation) are only
 * dispatched after a HUMAN_APPROVAL gate has been approved — the engine
 * enforces this.  Pure reason / metric / data_persist steps can be
 * enqueued directly.
 */
class LocalAssistant(
    private val engine: WorkflowEngine,
    private val onCommand: (VoiceCommand) -> Unit,
) {

    /** Start an activity that captures user speech and returns the result. */
    fun startCapture(activity: android.app.Activity) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a memo command")
        }
        activity.startActivityForResult(intent, 42)
    }

    /** Called when the speech recognizer returns.  Parses the best result
     * through CommandGrammar and dispatches the VoiceCommand. */
    fun onActivityResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        data?.getStringArrayListExtra(RecognizerIntent.RESULT_RESONANCE)?.takeIf { it.isNotEmpty() }
            ?.first() let { raw ->
            // Strip trailing punctuation before parsing (the grammar does this).
            val cleaned = raw.trimEnd('.', '!', '?')
            parseAndDispatch(cleaned)
        }
    }

    /** Parse raw speech and dispatch the resulting VoiceCommand into the engine. */
    private fun parseAndDispatch(raw: String) {
        val now = System.currentTimeMillis()
        val zone = android.OS.Build.VERSION.SDK_INT >= android.OS.VERSION_CODES.O
            ? android.text.format.DateFormat.getTimeZone()
            : java.util.TimeZone.getDefault()

        val grammarResult = CommandGrammar.parse(raw, now, zone)

        when (grammarResult) {
            is CommandGrammar.VoiceCommand.Cancel -> {
                // Cancel the current step / run.
                engine.markStepSkipped("current", "user cancelled")
            }
            is CommandGrammar.VoiceCommand.Reminder -> {
                // Reminders are outside the hash‑chain; surface as a notification.
                val cmd = grammarResult as CommandGrammar.VoiceCommand.Reminder
                engine.enqueueMetricSample(
                    "reminder:${cmd.title}",
                    now + cmd.dueAt,
                )
            }
            is CommandGrammar.VoiceCommand.CaptureTask -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.CaptureTask
                val spec = StepSpec(
                    id = "voice-${cmd.title.lowercase().replace(" ", "-")}",
                    kind = StepKind.APP_AUTOMATION,
                    provider = "voice",
                    artifactHint = "voice: ${cmd.title}",
                    requiresGate = false, // pure step, no gate needed
                )
                engine.advance(spec)
            }
            is CommandGrammar.VoiceCommand.CapturePerson -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.CapturePerson
                val spec = StepSpec(
                    id = "voice-person-${cmd.name.lowercase()}",
                    kind = StepKind.REASON,
                    provider = "voice",
                    artifactHint = "voice: ${cmd.name} at ${cmd.company}",
                    requiresGate = false,
                )
                engine.advance(spec)
            }
            is CommandGrammar.VoiceCommand.ComposeDraft -> {
                val cmd = grammarResult as CommandGrammar.VoiceCommand.ComposeDraft
                // Bring up a tiny Compose UI so the user can edit the topic;
                // then re‑submit the plan to the engine.
                onCommand(cmd)
            }
            else -> {
                // Unrecognized — present a short hint and stay in the current step.
                engine.markStepFailed("voice", "unrecognized command: ${raw.take(60)}")
            }
        }
    }
}