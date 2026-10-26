package app.memo.core.plan

import app.memo.core.domain.DraftKind
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic intent grammar (Threadline law): spoken/typed commands that
 * can be acted on WITHOUT a model are parsed here first. A hit means zero
 * hallucination surface. Returns null to hand the input to the orchestrator.
 */
object CommandGrammar {

    sealed interface VoiceCommand {
        data object Cancel : VoiceCommand

        /** "remind me to X in 5 minutes" / "remind me about X at 17:30" */
        data class Reminder(val title: String, val dueAt: Long) : VoiceCommand

        /** "add task X" / "todo: X" (optional reminder suffix). */
        data class CaptureTask(val title: String, val dueAt: Long) : VoiceCommand

        /** "add X to my CRM" — optional company via "at <company>". */
        data class CapturePerson(val name: String, val company: String) : VoiceCommand

        /** "draft post about X" / "compose email: X". */
        data class ComposeDraft(val kind: DraftKind, val topic: String) : VoiceCommand
    }

    fun parse(raw: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): VoiceCommand? {
        val input = raw.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty()) return null
        val lowered = input.lowercase().trimEnd('.', '!', '?', ',')

        if (lowered in setOf("cancel", "stop", "never mind", "forget it")) {
            return VoiceCommand.Cancel
        }

        // "remind me ..." (with or without task wording)
        REMINDER_PREFIX.find(input)?.let { match ->
            val rest = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (rest.isEmpty()) return null
            val dueAt = resolveWhen(rest, now, zone)
            return VoiceCommand.Reminder(restTitle(rest), dueAt)
        }

        // "add task ..." / "new task ..." / "todo: ..."
        TASK_PREFIX.find(input)?.let { match ->
            val rest = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (rest.isEmpty()) return null
            val dueAt = resolveWhen(rest, now, zone)
            return VoiceCommand.CaptureTask(restTitle(rest), dueAt)
        }

        // "add Maya to CRM" / "add Maya at Acme to my crm"
        CRM_PERSON.find(input)?.let { match ->
            val namePart = match.groupValues[1].trim().trimEnd('.', '!', '?')
            val companyPart = match.groupValues[2].trim()
            if (namePart.isEmpty()) return null
            return VoiceCommand.CapturePerson(namePart, companyPart)
        }

        // "draft post about X" / "compose email: X" / "write note about X"
        DRAFT_PREFIX.find(input)?.let { match ->
            val kindText = match.groupValues[1].lowercase()
            val topic = match.groupValues[2].trim().trimEnd('.', '!', '?')
            val kind = when (kindText) {
                "post" -> DraftKind.POST
                "comment" -> DraftKind.COMMENT
                "email" -> DraftKind.EMAIL
                "note" -> DraftKind.NOTE
                else -> return null
            }
            return VoiceCommand.ComposeDraft(kind, topic)
        }

        return null
    }

    /** "in 5 minutes"-style suffix into epoch ms; unrecognized suffix -> 0. */
    internal fun resolveWhen(text: String, now: Long, zone: ZoneId): Long {
        whenContains(text, now)?.let { return it }
        whenAtTime(text, now, zone)?.let { return it }
        return 0L
    }

    private fun whenContains(text: String, now: Long): Long? {
        val match = WHEN_IN.find(text) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when {
            unit.startsWith("min") -> 60_000L
            unit.startsWith("hour") || unit.startsWith("hr") -> 3_600_000L
            unit.startsWith("day") -> 86_400_000L
            else -> return null
        }
        val dueAt = now + amount * multiplier
        if (dueAt <= now) return null
        return dueAt
    }

    private fun whenAtTime(text: String, now: Long, zone: ZoneId): Long? {
        val match = WHEN_AT.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        val meridiem = match.groupValues[3].lowercase()
        if (minute !in 0..59) return null
        val hour24 = when (meridiem) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else (hour + 12) % 24
            else -> hour
        }
        if (hour24 !in 0..23) return null
        val nowZoned = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val candidate = nowZoned.toLocalDate()
            .atTime(hour24, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        // Today-or-tomorrow: an already-passed time rolls to the next day.
        return if (candidate <= now) {
            candidate + 86_400_000L
        } else {
            candidate
        }
    }

    /** Strips time suffixes from the title ("remind me to call at 5pm" -> "call"). */
    private fun restTitle(rest: String): String {
        var title = rest
        for (pattern in listOf(WHEN_IN, WHEN_AT)) {
            pattern.find(title)?.let { m -> title = title.replace(m.value, " ") }
        }
        return title.replace(Regex("\\s+"), " ").trim().trimEnd(',', '-', ':').trim()
    }

    private val REMINDER_PREFIX = Regex("(?i)^remind me (?:to |about |for )?(.+)$")
    private val TASK_PREFIX = Regex("(?i)^(?:add (?:a )?(?:new )?(?:task|todo)|new (?:task|todo)|todo)[\\s:]+(.+)$")
    private val CRM_PERSON = Regex("(?i)^add (.+?)(?: at (.+?))? to (?:my )?crm[.!?]?$")
    private val DRAFT_PREFIX = Regex("(?i)^(?:draft|compose|write) (?:a |an )?(post|comment|email|note)(?: about | on | for |)?[\\s:]*(.*)$")
    private val WHEN_IN = Regex("(?i)\\bin (\\d+) (minutes?|mins?|hours?|hrs?|days?)\\b")
    private val WHEN_AT = Regex("(?i)\\bat (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
}
