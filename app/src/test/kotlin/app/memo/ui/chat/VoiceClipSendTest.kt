package app.memo.ui.chat

import app.memo.core.chat.ChatRole
import app.memo.core.chat.VoiceClipDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure message-building logic behind `ChatController.send` (TASK G4b-2): a send with a
 * pending [VoiceClipDraft] stores the user message with matching voice fields, a clip-only
 * send (empty body) keeps that clip, and a plain send stays clip-free.
 */
internal class VoiceClipSendTest {

    private val clip = VoiceClipDraft(
        ref = "memo-voice-1729000000000.wav",
        durationMs = 12_500,
        transcript = "Remember the blue folder ",
    )

    @Test
    fun sendWithClipCarriesTheVoiceFields() {
        val message = userMessageWithClip(
            id = "abcd1234",
            body = "Summarize this later",
            createdAt = 1_720_900_000_000,
            clip = clip,
        )
        assertEquals(ChatRole.USER, message.role)
        assertEquals("Summarize this later", message.text)
        assertEquals(clip.ref, message.voiceClipRef)
        assertEquals(clip.durationMs, message.durationMs)
        assertEquals("Remember the blue folder ", message.transcript)
        assertNull(message.transcriptNote)
    }

    @Test
    fun clipOnlySendKeepsTheClipOnAnEmptyBody() {
        val message = userMessageWithClip(
            id = "abcd1234",
            body = "",
            createdAt = 1_720_900_000_000,
            clip = clip,
        )
        assertEquals("", message.text)
        assertEquals(clip.ref, message.voiceClipRef)
        assertEquals(clip.durationMs, message.durationMs)
        assertEquals(clip.transcript, message.transcript)
    }

    @Test
    fun clipWithNoteIsCarriedWithoutATranscript() {
        val noteClip = VoiceClipDraft(
            ref = "memo-voice-1729000000001.wav",
            durationMs = 3_000,
            transcriptNote = "model not installed",
        )
        val message = userMessageWithClip(
            id = "abcd1234",
            body = "",
            createdAt = 1_720_900_000_000,
            clip = noteClip,
        )
        assertEquals(noteClip.ref, message.voiceClipRef)
        assertNull(message.transcript)
        assertEquals("model not installed", message.transcriptNote)
    }

    @Test
    fun plainSendLeavesEveryVoiceFieldNull() {
        val message = userMessageWithClip(
            id = "abcd1234",
            body = "Just text",
            createdAt = 1_720_900_000_000,
            clip = null,
        )
        assertNull(message.voiceClipRef)
        assertNull(message.durationMs)
        assertNull(message.transcript)
        assertNull(message.transcriptNote)
    }
}
