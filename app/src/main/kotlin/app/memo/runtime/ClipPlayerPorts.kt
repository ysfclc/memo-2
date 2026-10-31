package app.memo.runtime

import app.memo.core.voice.ClipPlayerEvent

/**
 * The narrow port the chat voice bubble consumes. It mirrors [RecorderPorts]: the device
 * wiring is in `platform/android/voice` ([`MediaPlayerClipPlayer`]) and pure logic can be
 * driven with fakes whenever needed. Every method runs on the caller's (main) thread and must
 * be idempotent; the device reports outcomes through [ClipPlayerEvent]s given to
 * [MediaPlayerClipPlayer]'s constructor callback — never by return value.
 *
 * One clip plays at a time: every [start] tears down any previous playback first.
 */
interface ClipPlayerDevice {

    /** Prepares and plays the stored clip [ref] (a bare file name under `voice-messages/`)
     *  from zero. A start that cannot begin is reported as [ClipPlayerEvent.Failed]. */
    fun start(ref: String)

    /** Pauses the running clip; the position reports arrive as [ClipPlayerEvent.PausedAt]
     *  (null when there was nothing playing or the device could not read a position). */
    fun pause()

    /** Continues a paused clip at the same position; confirmed with [ClipPlayerEvent.StartedPlaying]. */
    fun resume()

    /** Tears down current playback (composition disposal, replaced clip). Sends no event. */
    fun release()

    /** The current position in milliseconds, or null when nothing is playing or readable. */
    fun currentPositionMs(): Long?
}
