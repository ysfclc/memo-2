package app.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepStatus
import app.memo.runtime.MemoRuntime
import app.memo.ui.components.EmptyState
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.MemoCard
import app.memo.ui.components.Pill
import app.memo.ui.components.ScreenHeader
import app.memo.ui.components.StatTile
import app.memo.ui.chat.ChatScreen
import app.memo.ui.nav.MemoNavShell
import app.memo.ui.nav.NavState
import app.memo.ui.nav.Route
import app.memo.ui.nav.SharedKind
import app.memo.ui.nav.sharedElement
import app.memo.ui.theme.MemoTheme
import app.memo.ui.voice.LiveConversationScreen
import org.json.JSONObject

/** The root of the app. Navigation state lives here; the shell owns animation and back handling. */
@Composable
fun MemoRoot(runtime: MemoRuntime) {
    var route by rememberSaveable { mutableStateOf(Route.LIVE) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snap by runtime.snapshot

    MemoNavShell(
        state = NavState(route = route, sheetOpen = sheetOpen),
        onStateChange = { next ->
            route = next.route
            sheetOpen = next.sheetOpen
        },
    ) { current ->
        when (current) {
            Route.LIVE -> LiveScreen(
                runs = snap.runs,
                auditCount = snap.auditCount,
                auditOk = snap.auditOk,
                draftCount = snap.drafts.size,
                onApprove = { run, step -> runtime.decide(run, step, true) },
                onVeto = { run, step -> runtime.decide(run, step, false) },
                onStopAll = { runtime.stopEverything() },
                message = snap.lastReply,
            )
            Route.TALK -> TalkScreen(runtime)
            Route.WORK -> WorkScreen(snap.people, snap.companies, snap.tasks)
            Route.STUDIO -> StudioScreen(snap.drafts)
            Route.BROWSER -> BrowserScreen()
            Route.MODELS -> ModelsScreen(runtime)
            Route.SETTINGS -> SettingsScreen(
                auditCount = snap.auditCount,
                auditOk = snap.auditOk,
                quarantined = snap.quarantined,
                storageError = snap.storageError,
                recoveryPending = snap.recoveryPending,
                onConfirmRecovery = { runtime.confirmRecovery() },
                onStopAll = { runtime.stopEverything() },
            )
        }
    }
}

fun humanize(name: String): String = name.lowercase().replace('_', ' ')

private fun pretty(json: String): String =
    runCatching { JSONObject(json).toString(2) }.getOrDefault(json)

// ------------------------------------------------------------------- Live

@Composable
fun LiveScreen(
    runs: List<AgentRun>,
    auditCount: Int,
    auditOk: Boolean,
    draftCount: Int,
    onApprove: (String, String) -> Unit,
    onVeto: (String, String) -> Unit,
    onStopAll: () -> Unit,
    message: String,
) {
    val waiting = runs.flatMap { run ->
        run.steps.filter { it.status == StepStatus.WAITING_USER && it.spec.kind == StepKind.HUMAN_APPROVAL }
            .map { run to it }
    }
    val active = runs.count { it.status == RunStatus.RUNNING || it.status == RunStatus.WAITING_USER }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Live", "What Memo is doing, and what waits for you.")
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile("Running", active, Modifier.weight(1f))
            StatTile("Approvals", waiting.size, Modifier.weight(1f))
            StatTile("Drafts", draftCount, Modifier.weight(1f))
            StatTile("Audit", auditCount, Modifier.weight(1f), suffix = if (auditOk) " ✓" else " !")
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(message, modifier = Modifier.padding(horizontal = 20.dp), color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (waiting.isNotEmpty()) {
                item { Text("Waiting for you", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink) }
                items(waiting, key = { (run, step) -> run.id + step.spec.id }) { (run, step) ->
                    MemoCard {
                        Text(run.spec.title, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                        Spacer(Modifier.height(4.dp))
                        Text("Review the filled artifact before it is saved.", color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MemoTheme.colors.canvas)
                                .padding(12.dp),
                        ) {
                            Text(
                                step.artifact.ifBlank { "(nothing filled yet)" }.let(::pretty),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MemoTheme.colors.ink,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MemoButton(
                                onClick = { onApprove(run.id, step.spec.id) },
                                enabled = step.artifact.isNotBlank(),
                                style = MemoButtonStyle.PRIMARY,
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Approve")
                            }
                            MemoButton(
                                onClick = { onVeto(run.id, step.spec.id) },
                                style = MemoButtonStyle.SECONDARY,
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Veto")
                            }
                        }
                    }
                }
            }

            item { Text("Recent runs", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink) }
            if (runs.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Say or type a command in Talk.",
                        color = MemoTheme.colors.inkMuted,
                        fontSize = 14.sp,
                    )
                }
            }
            items(runs.sortedByDescending { it.createdAt }.take(30), key = { it.id }) { run ->
                MemoCard(Modifier.sharedElement(SharedKind.RUN, run.id)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            run.spec.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MemoTheme.typography.subtitle,
                            color = MemoTheme.colors.ink,
                        )
                        Pill(humanize(run.status.name), emphasis = run.status == RunStatus.COMPLETED)
                    }
                    if (run.failure.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(run.failure, color = MemoTheme.colors.attention, fontSize = 13.sp)
                    }
                }
            }

            if (active > 0) {
                item {
                    MemoButton(onClick = onStopAll, style = MemoButtonStyle.QUIET, contentColor = MemoTheme.colors.attention) {
                        Text("Stop all active runs")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- Talk

// ------------------------------------------------------------------- Talk

// The Talk tab is voice-first (plan: voice and text with Memo). It opens on the live
// conversation; the typed chat surface is one switch away on the same tab.

@Composable
fun TalkScreen(runtime: MemoRuntime) {
    var textMode by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MemoButton(
                onClick = { textMode = false },
                modifier = Modifier.weight(1f),
                style = if (textMode) MemoButtonStyle.QUIET else MemoButtonStyle.PRIMARY,
            ) { Text("Voice") }
            MemoButton(
                onClick = { textMode = true },
                modifier = Modifier.weight(1f),
                style = if (textMode) MemoButtonStyle.PRIMARY else MemoButtonStyle.QUIET,
            ) { Text("Text") }
        }
        Box(Modifier.weight(1f)) {
            if (textMode) ChatScreen(runtime) else LiveConversationScreen(session = runtime.voice)
        }
    }
}

// ------------------------------------------------------------------- Work

@Composable
fun WorkScreen(
    people: List<app.memo.core.domain.Person>,
    companies: List<app.memo.core.domain.Company>,
    tasks: List<app.memo.core.domain.Task>,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("Work", "People, companies and tasks you approved.") }
        item { SectionTitle("People (${people.size})") }
        if (people.isEmpty()) item { EmptyLine("No people yet. Try \"Add … to my CRM\".") }
        items(people, key = { it.fullName + it.phone }) { p ->
            MemoCard(Modifier.padding(horizontal = 20.dp).sharedElement(SharedKind.RECORD, p.fullName + p.phone)) {
                Text(p.fullName, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                if (p.notes.isNotBlank()) Text(p.notes, color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
            }
        }
        item { SectionTitle("Companies (${companies.size})") }
        if (companies.isEmpty()) item { EmptyLine("Companies appear here once captured.") }
        items(companies, key = { it.domain }) { c ->
            MemoCard(Modifier.padding(horizontal = 20.dp).sharedElement(SharedKind.RECORD, c.domain)) {
                Text(c.displayName.ifBlank { c.domain }, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
            }
        }
        item { SectionTitle("Tasks (${tasks.size})") }
        if (tasks.isEmpty()) item { EmptyLine("No tasks yet. Try \"Add task …\".") }
        items(tasks, key = { it.title + it.createdAt }) { t ->
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, modifier = Modifier.weight(1f), style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                    Pill(humanize(t.state.name))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 20.dp), style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 20.dp), color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
}

// ----------------------------------------------------------------- Studio

@Composable
fun StudioScreen(drafts: List<app.memo.core.domain.ContentDraft>) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("Studio", "Drafts approved for use. Publishing is not wired in this build.") }
        if (drafts.isEmpty()) item { EmptyLine("No drafts yet. Try \"Draft post about …\".") }
        items(drafts, key = { it.title + it.createdAt }) { d ->
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.title,
                        modifier = Modifier.weight(1f),
                        style = MemoTheme.typography.subtitle,
                        color = MemoTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Pill(humanize(d.kind.name))
                }
                Spacer(Modifier.height(8.dp))
                Text(d.body, color = MemoTheme.colors.inkMuted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Browser

/** The in-Memo browser is not built yet. The screen says so and why, and nothing is loaded. */
@Composable
fun BrowserScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Browser", "Websites Memo works in, inside the app.")
        EmptyState(
            title = "The in-Memo browser arrives with channels",
            body = "Channels will let Memo open the sites you approve in its own hardened view, " +
                "so web steps run without leaving the app. Nothing is loaded here yet.",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

// --------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(
    auditCount: Int,
    auditOk: Boolean,
    quarantined: Int,
    storageError: String,
    recoveryPending: Boolean,
    onConfirmRecovery: () -> Unit,
    onStopAll: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader("Settings", "Trust, audit and controls. Everything stays on this device.")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (storageError.isNotEmpty()) {
                MemoCard {
                    Text("Storage", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.attention)
                    Spacer(Modifier.height(4.dp))
                    Text(storageError, color = MemoTheme.colors.ink, fontSize = 13.sp)
                    if (recoveryPending) {
                        Spacer(Modifier.height(10.dp))
                        MemoButton(onClick = onConfirmRecovery, style = MemoButtonStyle.SECONDARY) {
                            Text("I reviewed my data, resume saving")
                        }
                    }
                }
            }
            MemoCard {
                Text("Audit trail", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$auditCount outbound entries · chain " + if (auditOk) "verified" else "BROKEN",
                    color = if (auditOk) MemoTheme.colors.inkMuted else MemoTheme.colors.attention,
                    fontSize = 13.sp,
                )
                if (quarantined > 0) {
                    Text("$quarantined stored rows were quarantined on load.", color = MemoTheme.colors.attention, fontSize = 13.sp)
                }
            }
            MemoCard {
                Text("Kill switch", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text("Stops every active run. Side effects already approved are not undone.", color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                MemoButton(onClick = onStopAll, style = MemoButtonStyle.SECONDARY) { Text("Stop all runs") }
            }
            MemoCard {
                Text("Data", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Workspace, runs, audit and models live in app-private storage. Nothing leaves the device except pinned model downloads you start.",
                    color = MemoTheme.colors.inkMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
