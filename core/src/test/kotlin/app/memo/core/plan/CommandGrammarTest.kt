package app.memo.core.plan

import app.memo.core.domain.DraftKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandGrammarTest {

    private val zone = ZoneId.of("UTC")
    private val now: Long = Instant.parse("2026-10-26T22:13:20Z").toEpochMilli()

    @Test
    fun cancelCommandsParse() {
        assertEquals(CommandGrammar.VoiceCommand.Cancel, CommandGrammar.parse("cancel", now, zone))
        assertEquals(CommandGrammar.VoiceCommand.Cancel, CommandGrammar.parse("   Never mind.  ", now, zone))
        assertEquals(CommandGrammar.VoiceCommand.Cancel, CommandGrammar.parse("forget it", now, zone))
    }

    @Test
    fun reminderInMinutes() {
        val result = CommandGrammar.parse("remind me to call the dentist in 20 min", now, zone)
        assertTrue(result is CommandGrammar.VoiceCommand.Reminder)
        result as CommandGrammar.VoiceCommand.Reminder
        assertEquals("call the dentist", result.title)
        assertEquals(now + 20 * 60_000L, result.dueAt)
    }

    @Test
    fun reminderAtTimeRollsToTomorrowWhenPast() {
        val result = CommandGrammar.parse("remind me to submit the report at 9:30 am", now, zone)
        assertTrue(result is CommandGrammar.VoiceCommand.Reminder)
        result as CommandGrammar.VoiceCommand.Reminder
        assertEquals("submit the report", result.title)
        val expected = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .toLocalDate()
            .atTime(9, 30)
            .atZone(zone)
            .toInstant().toEpochMilli() + 86_400_000L // already-past today -> tomorrow
        assertEquals(expected, result.dueAt)
    }

    @Test
    fun reminderMeridiemHandling() {
        val fivePm = CommandGrammar.parse("remind me to stretch at 5pm", now, zone)
        assertTrue(fivePm is CommandGrammar.VoiceCommand.Reminder)
        fivePm as CommandGrammar.VoiceCommand.Reminder
        assertEquals("stretch", fivePm.title)
        val expected = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .toLocalDate().atTime(17, 0).atZone(zone)
            .toInstant().toEpochMilli() + 86_400_000L // 17:00 already passed at 22:13
        assertEquals(expected, fivePm.dueAt)

        val midnightAs12Am = CommandGrammar.parse("remind me to flip the laundry at 12:30 am", now, zone)
        assertTrue(midnightAs12Am is CommandGrammar.VoiceCommand.Reminder)
        midnightAs12Am as CommandGrammar.VoiceCommand.Reminder
        val expected12 = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .toLocalDate().atTime(0, 30).atZone(zone)
            .toInstant().toEpochMilli() + 86_400_000L
        assertEquals(expected12, midnightAs12Am.dueAt)
    }

    @Test
    fun taskCaptureWithOptionalReminder() {
        val plain = CommandGrammar.parse("add task buy espresso beans", now, zone)
        assertTrue(plain is CommandGrammar.VoiceCommand.CaptureTask)
        plain as CommandGrammar.VoiceCommand.CaptureTask
        assertEquals("buy espresso beans", plain.title)
        assertEquals(0L, plain.dueAt)

        val timed = CommandGrammar.parse("add task prep standup in 45 minutes", now, zone)
        timed as CommandGrammar.VoiceCommand.CaptureTask
        assertEquals("prep standup", timed.title)
        assertEquals(now + 45 * 60_000L, timed.dueAt)

        val todo = CommandGrammar.parse("todo: water the plants", now, zone)
        assertTrue(todo is CommandGrammar.VoiceCommand.CaptureTask)
    }

    @Test
    fun crmPersonCaptureWithOptionalCompany() {
        val withCompany = CommandGrammar.parse("add Maya at Northwind to my crm", now, zone)
        assertTrue(withCompany is CommandGrammar.VoiceCommand.CapturePerson)
        withCompany as CommandGrammar.VoiceCommand.CapturePerson
        assertEquals("Maya", withCompany.name)
        assertEquals("Northwind", withCompany.company)

        val bare = CommandGrammar.parse("add Jonas to crm.", now, zone)
        assertTrue(bare is CommandGrammar.VoiceCommand.CapturePerson)
        bare as CommandGrammar.VoiceCommand.CapturePerson
        assertEquals("Jonas", bare.name)
        assertEquals("", bare.company)
    }

    @Test
    fun draftComposeKinds() {
        assertEquals(
            CommandGrammar.VoiceCommand.ComposeDraft(DraftKind.POST, "why checkpoints beat vibes"),
            CommandGrammar.parse("draft post about why checkpoints beat vibes", now, zone),
        )
        assertTrue(
            CommandGrammar.parse("compose email: asking Hannes for a referral", now, zone)
                is CommandGrammar.VoiceCommand.ComposeDraft,
        )
        assertTrue(
            CommandGrammar.parse("write note ", now, zone) is CommandGrammar.VoiceCommand.ComposeDraft,
        )
    }

    @Test
    fun noiseGoesToTheModelNotTheGrammar() {
        assertNull(CommandGrammar.parse("what should I post today?", now, zone))
        assertNull(CommandGrammar.parse("", now, zone))
        assertNull(CommandGrammar.parse("remind me", now, zone))
        assertNull(CommandGrammar.parse("drafts emails are slow", now, zone))
    }

    @Test
    fun internalWhenResolutionIsDeterministic() {
        assertEquals(now + 5 * 60_000L, CommandGrammar.resolveWhen("text in 5 minutes", now, zone))
        assertEquals(0L, CommandGrammar.resolveWhen("plain text", now, zone))
    }
}
