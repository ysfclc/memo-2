package app.memo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.memo.core.domain.AgentRun
import app.memo.ui.artifact.ArtifactView
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.MemoCard
import app.memo.ui.components.StepRail
import app.memo.ui.motion.HapticCue
import app.memo.ui.motion.rememberHaptics
import app.memo.ui.theme.MemoTheme

/** What the plan card needs from the runtime; plain lambdas so the thread stays runtime-agnostic. */
class PlanCardHooks(
    val runFor: (String) -> AgentRun?,
    val onRun: (String) -> Unit,
    val onDiscard: (String) -> Unit,
    val onDecide: (runId: String, stepId: String, approve: Boolean, displayedHash: String) -> Unit,
    val onStop: (String) -> Unit,
)

/** The inline plan card: title, live step rail, the gate to decide on, and the run's actions. */
@Composable
fun PlanCard(runId: String, hooks: PlanCardHooks, modifier: Modifier = Modifier) {
    val run = hooks.runFor(runId)
    val type = MemoTheme.typography
    val colors = MemoTheme.colors
    val spacing = MemoTheme.spacing
    if (run == null) {
        Text("This plan is no longer available.", style = type.caption, color = colors.inkMuted, modifier = modifier)
        return
    }
    val state = PlanCards.from(run)
    val haptics = rememberHaptics()
    MemoCard(modifier) {
        Text(state.title, style = type.subtitle, color = colors.ink)
        state.sourceLabel?.let { Text(it, style = type.caption, color = colors.inkMuted) }
        Spacer(Modifier.height(spacing.space3))
        Text(state.statusLine, style = type.caption, color = colors.inkMuted)
        Spacer(Modifier.height(spacing.space3))
        StepRail(state.steps)
        val gate = state.gate
        if (gate != null) {
            Spacer(Modifier.height(spacing.space3))
            ArtifactView(gate.artifactType, gate.artifactJson)
            Text(
                "Approve to let Memo save or send this exactly as shown.",
                style = type.caption,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.height(spacing.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space3)) {
            listOf(
                PlanAction.RUN,
                PlanAction.APPROVE,
                PlanAction.VETO,
                PlanAction.DISCARD,
                PlanAction.STOP,
            ).filter { it in state.actions }.forEach { action ->
                when (action) {
                    PlanAction.RUN -> MemoButton(onClick = { hooks.onRun(runId) }) { Text("Run") }
                    PlanAction.APPROVE -> gate?.let { g ->
                        MemoButton(onClick = {
                            haptics.perform(HapticCue.CONFIRM)
                            hooks.onDecide(runId, g.stepId, true, g.artifactHash)
                        }) { Text("Approve") }
                    }
                    PlanAction.VETO -> gate?.let { g ->
                        MemoButton(
                            onClick = {
                                haptics.perform(HapticCue.REJECT)
                                hooks.onDecide(runId, g.stepId, false, g.artifactHash)
                            },
                            style = MemoButtonStyle.SECONDARY,
                        ) { Text("Veto") }
                    }
                    PlanAction.DISCARD -> MemoButton(
                        onClick = { hooks.onDiscard(runId) },
                        style = MemoButtonStyle.QUIET,
                    ) { Text("Discard") }
                    PlanAction.STOP -> MemoButton(
                        onClick = { hooks.onStop(runId) },
                        style = MemoButtonStyle.QUIET,
                    ) { Text("Stop") }
                }
            }
        }
    }
}
