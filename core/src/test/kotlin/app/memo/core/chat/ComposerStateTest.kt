package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComposerStateTest {

    @Test
    fun emptyComposerIsDisabled() {
        val s = ComposerState()
        assertFalse(s.canSend)
        assertEquals(PrimaryAction.DISABLED, s.primaryAction)
    }

    @Test
    fun whitespaceOnlyIsNotSendable() {
        assertEquals(PrimaryAction.DISABLED, ComposerState(text = "   \n ").primaryAction)
    }

    @Test
    fun textMakesSendAvailable() {
        val s = ComposerState(text = "hello")
        assertTrue(s.canSend)
        assertEquals(PrimaryAction.SEND, s.primaryAction)
    }

    @Test
    fun generatingTurnsSendIntoStopAndBlocksSending() {
        val s = ComposerState(text = "next question", generating = true)
        assertFalse(s.canSend)
        assertEquals(PrimaryAction.STOP, s.primaryAction)
    }

    @Test
    fun attachmentAloneIsSendable() {
        val s = ComposerState(attachment = Attachment("notes.txt", "body"))
        assertTrue(s.canSend)
        assertEquals("File: notes.txt\n```\nbody\n```", s.outgoingText())
    }

    @Test
    fun outgoingTextPutsTypedTextAfterAttachment() {
        val s = ComposerState(text = "  summarise  ", attachment = Attachment("a.md", "x"))
        assertEquals("File: a.md\n```\nx\n```\n\nsummarise", s.outgoingText())
    }

    @Test
    fun draftIsCapped() {
        val s = ComposerState().withText("a".repeat(ComposerState.MAX_DRAFT_CHARS + 50))
        assertEquals(ComposerState.MAX_DRAFT_CHARS, s.text.length)
    }

    @Test
    fun textFileIsAccepted() {
        val result = Attachments.fromBytes("  notes.txt ", "héllo".toByteArray(Charsets.UTF_8))
        assertIs<AttachmentResult.Ok>(result)
        assertEquals(Attachment("notes.txt", "héllo"), result.attachment)
    }

    @Test
    fun fileOverLimitIsRefused() {
        val bytes = ByteArray(ChatLimits.MAX_ATTACHMENT_BYTES + 1) { 'a'.code.toByte() }
        assertIs<AttachmentResult.Refused>(Attachments.fromBytes("big.txt", bytes))
    }

    @Test
    fun fileAtLimitIsAccepted() {
        val bytes = ByteArray(ChatLimits.MAX_ATTACHMENT_BYTES) { 'a'.code.toByte() }
        assertIs<AttachmentResult.Ok>(Attachments.fromBytes("edge.txt", bytes))
    }

    @Test
    fun binaryAndInvalidUtf8AreRefused() {
        assertIs<AttachmentResult.Refused>(Attachments.fromBytes("x.bin", byteArrayOf(1, 0, 2)))
        assertIs<AttachmentResult.Refused>(Attachments.fromBytes("x.txt", byteArrayOf(0xC3.toByte(), 0x28)))
    }
}
