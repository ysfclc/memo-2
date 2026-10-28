package app.memo.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.HapticCue
import app.memo.ui.motion.rememberHaptics
import app.memo.ui.theme.MemoTheme
import java.util.Locale

/**
 * Pure state machine for [VoiceRecorderBar]. No Compose types, so it is testable on the
 * JVM. The composable mirrors [phase] and [slide] into Compose state for recomposition.
 */
class RecorderGestureState(
    private val cancelFraction: Float = DEFAULT_CANCEL_FRACTION,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
) {
    enum class Phase { IDLE, RECORDING, CANCELED }
    enum class Outcome { SENT, CANCELED, TAP }

    var phase = Phase.IDLE
        private set
    var slide = 0f
        private set

    /** Finger down: recording starts immediately; a quick release becomes [Outcome.TAP]. */
    fun press() {
        phase = Phase.RECORDING
        slide = 0f
    }

    /**
     * Finger moved; [fraction] is the upward travel normalised by the slide span. Past
     * [cancelFraction] the gesture arms cancel; dropping back below disarms it.
     */
    fun drag(fraction: Float) {
        slide = fraction.coerceIn(0f, 1f)
        phase = if (slide >= cancelFraction) Phase.CANCELED else Phase.RECORDING
    }

    /**
     * Finger up after [durationMs]. Slide armed → [Outcome.CANCELED]; hold shorter than
     * [minDurationMs] → [Outcome.TAP] (tap opens the live conversation); otherwise the
     * message is sent. The state resets either way.
     */
    fun release(durationMs: Long): Outcome {
        val outcome = when {
            phase == Phase.CANCELED -> Outcome.CANCELED
            durationMs < minDurationMs -> Outcome.TAP
            else -> Outcome.SENT
        }
        reset()
        return outcome
    }

    fun reset() {
        phase = Phase.IDLE
        slide = 0f
    }

    companion object {
        /** Upward travel (normalised) that arms cancel: half of the two-height slide span. */
        const val DEFAULT_CANCEL_FRACTION = 0.5f

        /** Holds shorter than this are taps, not recordings. */
        const val DEFAULT_MIN_DURATION_MS = 350L

        /** The drag span is twice the bar height, so cancel arms after about one height. */
        const val SLIDE_SPAN_HEIGHTS = 2f
    }
}

/** Pure mm:ss formatting for the recording timer; testable on the JVM. */
fun formatRecorderElapsed(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

/**
 * Hold-to-record bar. Press starts recording (reported once via [onRecordingStarted]);
 * sliding up arms cancel with a reject haptic; releasing sends ([onRecordingSent]) or
 * cancels ([onRecordingCanceled]); a quick tap reports [onTap] — the composer uses that
 * to open the live conversation. [levels] (0..1 meter frames) and [elapsedMs] are driven
 * by the parent while recording.
 */
@Composable
fun VoiceRecorderBar(
    levels: List<Float>,
    elapsedMs: Long,
    onRecordingStarted: () -> Unit,
    onRecordingCanceled: () -> Unit,
    onRecordingSent: (durationMs: Long) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Hold to record · tap for live voice",
    cancelHint: String = "Release to cancel",
    recordingLabel: String = "Recording voice message",
) {
    val colors = MemoTheme.colors
    val haptics = rememberHaptics()
    val gesture = remember { RecorderGestureState() }

    // The pure gesture state is not Compose-observable; mirror what the UI renders here.
    var phase by remember { mutableStateOf(RecorderGestureState.Phase.IDLE) }
    var slide by remember { mutableStateOf(0f) }

    val shape = RoundedCornerShape(percent = 50)
    val background = when (phase) {
        RecorderGestureState.Phase.RECORDING -> colors.accent.copy(alpha = 0.16f)
        RecorderGestureState.Phase.CANCELED -> colors.attention.copy(alpha = 0.16f)
        RecorderGestureState.Phase.IDLE -> colors.surfaceSunken
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(background, shape)
            .semantics { contentDescription = hint }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val pressMs = System.currentTimeMillis()
                    gesture.press()
                    phase = gesture.phase
                    slide = gesture.slide
                    haptics.perform(HapticCue.TICK)
                    onRecordingStarted()
                    val originY = down.position.y
                    val slideSpan =
                        (size.height * RecorderGestureState.SLIDE_SPAN_HEIGHTS).coerceAtLeast(1f)
                    var rejectFired = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            // The pressed pointer left the stream: cannot become a send.
                            gesture.reset()
                            phase = RecorderGestureState.Phase.IDLE
                            slide = 0f
                            onRecordingCanceled()
                            break
                        }
                        val up = !change.pressed
                        gesture.drag((originY - change.position.y) / slideSpan)
                        phase = gesture.phase
                        slide = gesture.slide
                        if (phase == RecorderGestureState.Phase.CANCELED && !rejectFired) {
                            haptics.perform(HapticCue.REJECT)
                            rejectFired = true
                        }
                        change.consume()
                        if (up) {
                            val durationMs = System.currentTimeMillis() - pressMs
                            when (gesture.release(durationMs)) {
                                RecorderGestureState.Outcome.SENT -> {
                                    haptics.perform(HapticCue.CONFIRM)
                                    onRecordingSent(durationMs)
                                }
                                RecorderGestureState.Outcome.CANCELED -> {
                                    haptics.perform(HapticCue.LONG_PRESS)
                                    onRecordingCanceled()
                                }
                                RecorderGestureState.Outcome.TAP -> onTap()
                            }
                            phase = RecorderGestureState.Phase.IDLE
                            slide = 0f
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            RecorderGestureState.Phase.IDLE -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = colors.inkFaint,
                )
                Text(
                    text = hint,
                    style = MemoTheme.typography.label,
                    color = colors.inkMuted,
                )
            }
            RecorderGestureState.Phase.RECORDING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = formatRecorderElapsed(elapsedMs),
                    style = MemoTheme.typography.label,
                    color = colors.ink,
                )
                Waveform(
                    levels = levels,
                    contentDescription = recordingLabel,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    color = colors.accent,
                )
                if (slide > 0f) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = null,
                        tint = colors.attention.copy(
                            alpha = (slide / RecorderGestureState.DEFAULT_CANCEL_FRACTION)
                                .coerceIn(0.2f, 1f),
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            RecorderGestureState.Phase.CANCELED -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowUpward,
                    contentDescription = null,
                    tint = colors.attention,
                )
                Text(
                    text = cancelHint,
                    style = MemoTheme.typography.label,
                    color = colors.attention,
                )
            }
        }
    }
}
