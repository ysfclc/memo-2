package app.memo.core.voice

import app.memo.core.chat.ChatLimits

/**
 * Pure state machine for the voice-message clip player ([app.memo.runtime.ClipPlayerDevice]
 * is the thin device adapter around it; the UI reduces events through [reduceClipPlayback]).
 * No Android or Compose types, so transitions are testable on the JVM.
 *
 * Lifecycle: [ClipPlayerState.Idle] -> tap starts a clip -> [ClipPlayerState.Playing] ->
 * tap or device pause -> [ClipPlayerState.Paused] -> tap resumes -> also
 * [ClipPlayerState.Completed] when the clip finishes, and [ClipPlayerState.Failed] for a
 * failed [reason] (missing file, refused focus, decoder error). A tap on Failed or Completed
 * starts (or replays) the clip from zero.
 */
sealed interface ClipPlayerState {
    /** Nothing is playing for this message yet. */
    data object Idle : ClipPlayerState

    /** Playback running. [positionMs] is the position it state represents (0 on start/replay). */
    data class Playing(val positionMs: Long) : ClipPlayerState

    /** Held at [positionMs] (null when the device could not report one yet). */
    data class Paused(val positionMs: Long?) : ClipPlayerState

    /** The clip finished; tapping replays it from the start. */
    data object Completed : ClipPlayerState

    /** Playback failed for [reason] (plain language, from the device adapter); tapping retries. */
    data class Failed(val reason: String) : ClipPlayerState
}

/** Inputs to [reduceClipPlayback]: the user tap and the device adapter's reports. */
sealed interface ClipPlayerEvent {
    /** The user tapped the clip bubble: toggle start/pause/resume per [ClipPlayerState]. */
    data object Tapped : ClipPlayerEvent

    /** The device adapter confirms audio frames are flowing (after start or resume). */
    data object StartedPlaying : ClipPlayerEvent

    /** The device paused. [positionMs] is where it stopped, null when it could not read one. */
    data class PausedAt(val positionMs: Long?) : ClipPlayerEvent

    /** The device finished the clip on its own (completion listener). */
    data object Completed : ClipPlayerEvent

    /** The system stopped playback (audio focus loss). [positionMs] as in [PausedAt]. */
    data class Interrupted(val positionMs: Long?) : ClipPlayerEvent

    /** The device adapter failed; [reason] is shown honestly in the bubble. */
    data class Failed(val reason: String) : ClipPlayerEvent
}

/** What the caller must ask the device adapter to do next; null when nothing. */
sealed interface ClipPlayerCommand {
    /** Prepare and play the clip from zero (also the replay and retry path). */
    data object Start : ClipPlayerCommand

    /** Pause the running clip; the device confirms with [ClipPlayerEvent.PausedAt]. */
    data object Pause : ClipPlayerCommand

    /** Continue a paused clip at the same position. */
    data object Resume : ClipPlayerCommand
}

/**
 * Reduces one [event] against [state]. Returns the next state plus the device command to run
 * (or null). Total over every (state, event) pair: stray device reports after a teardown are
 * absorbed, never crash. The state machine never invents playback: a state change that says
 * "playing" is either confirmed by [ClipPlayerEvent.StartedPlaying] or came from the user tap
 * and is corrected by a failure event if the device cannot start (D-047).
 */
fun reduceClipPlayback(state: ClipPlayerState, event: ClipPlayerEvent): Pair<ClipPlayerState, ClipPlayerCommand?> =
    when (event) {
        ClipPlayerEvent.Tapped -> when (state) {
            is ClipPlayerState.Idle, is ClipPlayerState.Completed, is ClipPlayerState.Failed ->
                ClipPlayerState.Playing(0L) to ClipPlayerCommand.Start
            is ClipPlayerState.Playing -> ClipPlayerState.Paused(state.positionMs) to ClipPlayerCommand.Pause
            is ClipPlayerState.Paused -> ClipPlayerState.Playing(state.positionMs ?: 0L) to ClipPlayerCommand.Resume
        }
        ClipPlayerEvent.StartedPlaying -> if (state is ClipPlayerState.Playing) {
            state to null
        } else {
            ClipPlayerState.Playing(0L) to null
        }
        is ClipPlayerEvent.PausedAt -> when (state) {
            is ClipPlayerState.Playing -> ClipPlayerState.Paused(coalesce(state.positionMs, event.positionMs)) to null
            is ClipPlayerState.Paused -> ClipPlayerState.Paused(coalesce(state.positionMs, event.positionMs)) to null
            is ClipPlayerState.Idle, is ClipPlayerState.Completed, is ClipPlayerState.Failed -> state to null
        }
        ClipPlayerEvent.Completed -> if (state is ClipPlayerState.Playing) ClipPlayerState.Completed to null else state to null
        is ClipPlayerEvent.Interrupted -> if (state is ClipPlayerState.Playing) {
            ClipPlayerState.Paused(coalesce(state.positionMs, event.positionMs)) to null
        } else {
            state to null
        }
        is ClipPlayerEvent.Failed -> ClipPlayerState.Failed(event.reason) to null
    }

/**
 * Pause/stop reports carry the device position when they can; the coalescing keeps the last
 * known position when the device could not read one. Clamped to the recorder's cap so a
 * broken device clock cannot display nonsense lengths.
 */
private fun coalesce(previousMs: Long?, reportedMs: Long?): Long? =
    (reportedMs ?: previousMs)?.coerceIn(0L, ChatLimits.MAX_VOICE_DURATION_MS.toLong())
