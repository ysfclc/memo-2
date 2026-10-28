package app.memo.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.memo.core.voice.TurnState
import app.memo.platform.android.voice.MicPermission
import app.memo.runtime.VoiceSession
import app.memo.ui.components.EmptyState
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.OrbState
import app.memo.ui.components.PresenceOrb
import app.memo.ui.components.ScreenHeader
import app.memo.ui.theme.MemoTheme

/**
 * The live conversation (TASKS M2.B1 mini-task 3): the orb breathes with the mic level, the
 * user's words show while listening, the spoken sentence shows as the caption while speaking,
 * tapping the speaking orb interrupts, and End closes the conversation. The model and
 * permission gates are stated honestly; nothing pretends to listen.
 */
@Composable
fun LiveConversationScreen(session: VoiceSession, modifier: Modifier = Modifier) {
    // Reading the state property here subscribes the composition to its changes.
    val ui = session.ui
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) session.begin()
    }

    LaunchedEffect(Unit) { session.prepare() }

    val active = ui.turn == TurnState.Listening || ui.turn == TurnState.Thinking ||
        ui.turn == TurnState.Speaking

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader("Talk", "Hands-free conversation. Everything runs on this device.")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        ) {
            when {
                ui.phase == VoiceSession.Phase.Unavailable -> EmptyState(
                    title = "Voice is not ready",
                    body = ui.problem,
                )
                ui.phase == VoiceSession.Phase.Preparing -> EmptyState(
                    title = "Preparing the voice engines",
                    body = "The pinned models are loading. This happens once.",
                )
                permissionDenied -> EmptyState(
                    title = "The microphone is off",
                    body = MicPermission.RATIONALE,
                )
                else -> {
                    PresenceOrb(
                        state = orbState(ui.turn),
                        level = ui.micLevel,
                        contentDescription = "Memo's presence",
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(top = 12.dp),
                    )
                    val status = when (ui.turn) {
                        TurnState.Listening -> ui.partial.ifEmpty { "Listening…" }
                        TurnState.Thinking -> "Thinking…"
                        TurnState.Speaking -> ui.caption
                        TurnState.Idle, TurnState.Ended -> "Hold a conversation, hands free."
                    }
                    Text(
                        text = status,
                        style = MemoTheme.typography.body,
                        color = MemoTheme.colors.ink,
                        textAlign = TextAlign.Center,
                    )
                    when {
                        active -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (ui.turn == TurnState.Speaking) {
                                MemoButton(onClick = session::interrupt, style = MemoButtonStyle.QUIET) {
                                    Text("Interrupt")
                                }
                            }
                            MemoButton(onClick = session::end, style = MemoButtonStyle.QUIET, contentColor = MemoTheme.colors.attention) {
                                Text("End")
                            }
                        }
                        else -> MemoButton(
                            onClick = {
                                if (MicPermission.isGranted(context)) {
                                    session.begin()
                                } else {
                                    permission.launch(MicPermission.PERMISSION)
                                }
                            },
                            style = MemoButtonStyle.PRIMARY,
                        ) {
                            Text("Start talking")
                        }
                    }
                }
            }
        }
    }
}

/** The turn state on the orb: IDLE, LISTENING, THINKING, SPEAKING; ENDED rests as IDLE. */
private fun orbState(turn: TurnState): OrbState = when (turn) {
    TurnState.Idle, TurnState.Ended -> OrbState.IDLE
    TurnState.Listening -> OrbState.LISTENING
    TurnState.Thinking -> OrbState.THINKING
    TurnState.Speaking -> OrbState.SPEAKING
}
