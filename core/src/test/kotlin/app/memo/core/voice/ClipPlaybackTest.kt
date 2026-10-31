package app.memo.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The [reduceClipPlayback] machine on the JVM: start/pause/resume around a tap, device
 * confirmations, completion, focus-loss interruption, honest failures, and stray reports
 * absorbed without changing state.
 */
class ClipPlaybackTest {

    @Test
    fun idleTapStartsTheClipFromZero() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Idle, ClipPlayerEvent.Tapped)
        assertEquals(ClipPlayerState.Playing(0L), state)
        assertEquals(ClipPlayerCommand.Start, command)
    }

    @Test
    fun playingTapPausesOptimistically() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Playing(0L), ClipPlayerEvent.Tapped)
        assertEquals(ClipPlayerState.Paused(0L), state)
        assertEquals(ClipPlayerCommand.Pause, command)
    }

    @Test
    fun pauseReportCarriesTheDevicePosition() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Playing(0L), ClipPlayerEvent.PausedAt(1_234L))
        assertEquals(ClipPlayerState.Paused(1_234L), state)
        assertNull(command)
    }

    @Test
    fun pausedTapResumesAtThePausedPosition() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Paused(1_234L), ClipPlayerEvent.Tapped)
        assertEquals(ClipPlayerState.Playing(1_234L), state)
        assertEquals(ClipPlayerCommand.Resume, command)
    }

    @Test
    fun completionAdvancesAndTapReplaysFromZero() {
        val completed = reduceClipPlayback(ClipPlayerState.Playing(600L), ClipPlayerEvent.Completed).first
        assertEquals(ClipPlayerState.Completed, completed)
        val (replay, replayCommand) = reduceClipPlayback(completed, ClipPlayerEvent.Tapped)
        assertEquals(ClipPlayerState.Playing(0L), replay)
        assertEquals(ClipPlayerCommand.Start, replayCommand)
    }

    @Test
    fun focusLossInterruptsToPaused() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Playing(900L), ClipPlayerEvent.Interrupted(900L))
        assertEquals(ClipPlayerState.Paused(900L), state)
        assertNull(command)
        // The user resumes manually; nothing replays on its own.
    }

    @Test
    fun failureIsReportedHonestAndTapRetriesFromZero() {
        val (failed, _) = reduceClipPlayback(ClipPlayerState.Playing(450L), ClipPlayerEvent.Failed("the recording file is missing"))
        assertEquals(ClipPlayerState.Failed("the recording file is missing"), failed)
        val (retry, retryCommand) = reduceClipPlayback(failed, ClipPlayerEvent.Tapped)
        assertEquals(ClipPlayerState.Playing(0L), retry)
        assertEquals(ClipPlayerCommand.Start, retryCommand)
    }

    @Test
    fun startedPlayingConfirmsPlaybackWithoutChange() {
        val (state, command) = reduceClipPlayback(ClipPlayerState.Playing(0L), ClipPlayerEvent.StartedPlaying)
        assertEquals(ClipPlayerState.Playing(0L), state)
        assertNull(command)
        // A stray confirmation can also surface playback (adapters that signal asynchronously).
        val (surfaced, _) = reduceClipPlayback(ClipPlayerState.Idle, ClipPlayerEvent.StartedPlaying)
        assertEquals(ClipPlayerState.Playing(0L), surfaced)
    }

    @Test
    fun pauseReportKeepsTheLastKnownPositionWhenTheDeviceCannotReadOne() {
        val (state, _) = reduceClipPlayback(ClipPlayerState.Paused(1_000L), ClipPlayerEvent.PausedAt(null))
        assertEquals(ClipPlayerState.Paused(1_000L), state)
        val (fromPlaying, _) = reduceClipPlayback(ClipPlayerState.Playing(2_000L), ClipPlayerEvent.PausedAt(null))
        assertEquals(ClipPlayerState.Paused(2_000L), fromPlaying)
    }

    @Test
    fun brokenDevicePositionsClampToTheRecorderDomain() {
        val (state, _) = reduceClipPlayback(ClipPlayerState.Paused(20_000L), ClipPlayerEvent.PausedAt(-1_000L))
        assertEquals(ClipPlayerState.Paused(0L), state)
    }

    @Test
    fun strayReportsAfterCompletionAreAbsorbed() {
        val completed = ClipPlayerState.Completed
        assertEquals(completed, reduceClipPlayback(completed, ClipPlayerEvent.Completed).first)
        assertEquals(completed, reduceClipPlayback(completed, ClipPlayerEvent.PausedAt(50L)).first)
        assertEquals(completed, reduceClipPlayback(completed, ClipPlayerEvent.Interrupted(null)).first)
    }

    @Test
    fun strayReportsWhileIdleDoNotStartPlayback() {
        val idle = ClipPlayerState.Idle
        assertEquals(idle, reduceClipPlayback(idle, ClipPlayerEvent.PausedAt(50L)).first)
        assertEquals(idle, reduceClipPlayback(idle, ClipPlayerEvent.Completed).first)
        assertEquals(idle, reduceClipPlayback(idle, ClipPlayerEvent.Interrupted(50L)).first)
    }

    @Test
    fun failuresOverrideEveryState() {
        listOf<ClipPlayerState>(
            ClipPlayerState.Idle,
            ClipPlayerState.Playing(1L),
            ClipPlayerState.Paused(1L),
            ClipPlayerState.Completed,
        ).forEach { source ->
            val (state, command) = reduceClipPlayback(source, ClipPlayerEvent.Failed("audio focus was refused"))
            assertEquals(ClipPlayerState.Failed("audio focus was refused"), state)
            assertNull(command)
        }
    }
}
