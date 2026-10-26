package app.memo.runtime

import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.CommandGrammar
import org.json.JSONObject

/**
 * Turns a parsed voice/text command into a typed WorkflowSpec.
 *
 * Every structured capture follows the same shape, so the approval law holds
 * by construction: fill the artifact (REASON) -> show it at a HUMAN_APPROVAL
 * gate -> persist only after approval (DATA_PERSIST).
 */
object Planner {

    fun plan(command: CommandGrammar.VoiceCommand, now: Long): WorkflowSpec? = when (command) {
        is CommandGrammar.VoiceCommand.CapturePerson -> gatedCapture(
            title = "Add ${command.name}",
            artifactType = "person",
            payload = JSONObject()
                .put("fullName", command.name)
                .put("role", "")
                .put("notes", if (command.company.isBlank()) "" else "Company: ${command.company}"),
            now = now,
        )
        is CommandGrammar.VoiceCommand.CaptureTask -> gatedCapture(
            title = "Task: ${command.title}",
            artifactType = "task",
            payload = taskPayload(command.title, command.dueAt),
            now = now,
        )
        is CommandGrammar.VoiceCommand.Reminder -> gatedCapture(
            title = "Reminder: ${command.title}",
            artifactType = "task",
            payload = taskPayload("Remind: ${command.title}", command.dueAt),
            now = now,
        )
        is CommandGrammar.VoiceCommand.ComposeDraft -> gatedCapture(
            title = "Draft: ${command.topic}",
            artifactType = "draft",
            payload = JSONObject()
                .put("kind", command.kind.name)
                .put("title", command.topic.take(120))
                .put("body", outline(command.topic))
                .put("state", "NEW"),
            now = now,
        )
        else -> null
    }

    /**
     * Deterministic outline used until an on-device model is wired in. It is a
     * real, editable starting body (never an empty approval), not generated prose.
     */
    private fun outline(topic: String): String =
        "Topic: ${topic.take(200)}\n\n" +
            "1. Context\n2. The core idea\n3. Constraints\n4. Next step"

    private fun taskPayload(title: String, dueAt: Long): JSONObject = JSONObject()
        .put("title", title.take(140))
        .put("state", "OPEN")
        .put("dueAt", dueAt)
        .put("notes", "")

    private fun gatedCapture(title: String, artifactType: String, payload: JSONObject, now: Long): WorkflowSpec =
        WorkflowSpec(
            id = "wf-${java.lang.Long.toHexString(now)}",
            title = title.take(120),
            createdAt = now,
            steps = listOf(
                StepSpec(
                    id = "fill",
                    kind = StepKind.REASON,
                    title = "Fill $artifactType",
                    prompt = payload.toString(),
                ),
                StepSpec(
                    id = "approve",
                    kind = StepKind.HUMAN_APPROVAL,
                    title = "Approve $artifactType",
                    artifactType = artifactType,
                ),
                StepSpec(
                    id = "save",
                    kind = StepKind.DATA_PERSIST,
                    title = "Save $artifactType",
                    artifactType = artifactType,
                ),
            ),
        )

    fun helpText(): String =
        "Try: \"Add Ada Lovelace to my CRM\", \"Add task call the accountant\", " +
            "\"Remind me to review the deck in 10 minutes\", or \"Draft post about local models\"."
}
