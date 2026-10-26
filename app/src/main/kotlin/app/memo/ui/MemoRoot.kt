package app.memo.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memo.core.domain.AgentRun
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepStatus
import app.memo.runtime.MemoRuntime
import app.memo.runtime.Planner
import org.json.JSONObject

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Outlined.Home),
    TALK("Talk", Icons.Outlined.Mic),
    WORK("Work", Icons.Outlined.Person),
    STUDIO("Studio", Icons.Outlined.Edit),
    MODELS("Models", Icons.Outlined.Download),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun MemoRoot(runtime: MemoRuntime) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val snap by runtime.snapshot

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.LIVE -> LiveScreen(
                    runs = snap.runs,
                    auditCount = snap.auditCount,
                    auditOk = snap.auditOk,
                    draftCount = snap.drafts.size,
                    onApprove = { run, step -> runtime.decide(run, step, true) },
                    onVeto = { run, step -> runtime.decide(run, step, false) },
                    onStopAll = { runtime.stopEverything() },
                    message = snap.lastReply,
                )
                Tab.TALK -> TalkScreen(runtime = runtime)
                Tab.WORK -> WorkScreen(snap.people, snap.companies, snap.tasks)
                Tab.STUDIO -> StudioScreen(snap.drafts)
                Tab.MODELS -> ModelsScreen(runtime.models)
                Tab.SETTINGS -> SettingsScreen(
                    auditCount = snap.auditCount,
                    auditOk = snap.auditOk,
                    quarantined = snap.quarantined,
                    onStopAll = { runtime.stopEverything() },
                )
            }
        }
    }
}

// ----------------------------------------------------------------- shared

@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MemoColors.MutedInk)
    }
}

@Composable
fun MemoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Pill(text: String, emphasis: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (emphasis) MemoColors.Mint else MemoColors.Hairline)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 12.sp, color = MemoColors.Ink)
    }
}

fun humanize(name: String): String = name.lowercase().replace('_', ' ')

private fun pretty(json: String): String =
    runCatching { JSONObject(json).toString(2) }.getOrDefault(json)

// ------------------------------------------------------------------- Live

@OptIn(ExperimentalMaterial3Api::class)
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
            StatTile("Running", active.toString(), Modifier.weight(1f))
            StatTile("Approvals", waiting.size.toString(), Modifier.weight(1f))
            StatTile("Drafts", draftCount.toString(), Modifier.weight(1f))
            StatTile("Audit", if (auditOk) "$auditCount ✓" else "$auditCount !", Modifier.weight(1f))
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(message, modifier = Modifier.padding(horizontal = 20.dp), color = MemoColors.MutedInk, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (waiting.isNotEmpty()) {
                item { Text("Waiting for you", style = MaterialTheme.typography.titleMedium) }
                items(waiting, key = { (run, step) -> run.id + step.spec.id }) { (run, step) ->
                    MemoCard {
                        Text(run.spec.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Review the filled artifact before it is saved.", color = MemoColors.MutedInk, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MemoColors.Paper)
                                .padding(12.dp),
                        ) {
                            Text(
                                step.artifact.ifBlank { "(nothing filled yet)" }.let(::pretty),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MemoColors.Ink,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onApprove(run.id, step.spec.id) },
                                enabled = step.artifact.isNotBlank(),
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Approve")
                            }
                            OutlinedButton(onClick = { onVeto(run.id, step.spec.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Veto")
                            }
                        }
                    }
                }
            }

            item { Text("Recent runs", style = MaterialTheme.typography.titleMedium) }
            if (runs.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Say or type a command in Talk.",
                        color = MemoColors.MutedInk,
                        fontSize = 14.sp,
                    )
                }
            }
            items(runs.sortedByDescending { it.createdAt }.take(30), key = { it.id }) { run ->
                MemoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            run.spec.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Pill(humanize(run.status.name), emphasis = run.status == RunStatus.COMPLETED)
                    }
                    if (run.failure.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(run.failure, color = MemoColors.Amber, fontSize = 13.sp)
                    }
                }
            }

            if (active > 0) {
                item {
                    TextButton(onClick = onStopAll) { Text("Stop all active runs", color = MemoColors.Amber) }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
        Text(label, fontSize = 11.sp, color = MemoColors.MutedInk)
    }
}

// ------------------------------------------------------------------- Talk

@Composable
fun TalkScreen(runtime: MemoRuntime) {
    var text by rememberSaveable { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }

    fun submit(raw: String) {
        if (raw.isBlank()) return
        reply = runtime.handleUtterance(raw)
        text = ""
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (!spoken.isNullOrBlank()) submit(spoken)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("Talk", "Describe the work. Memo plans it and waits at each gate.")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Add Ada Lovelace to my CRM") },
            minLines = 2,
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { submit(text) }, enabled = text.isNotBlank()) {
                Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Plan")
            }
            OutlinedButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a Memo command")
                }
                runCatching { voice.launch(intent) }
                    .onFailure { reply = "Voice recognition is not available on this device." }
            }) {
                Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Speak")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (reply.isNotBlank()) {
            MemoCard { Text(reply, fontSize = 15.sp) }
            Spacer(Modifier.height(12.dp))
        }
        MemoCard {
            Text("Examples", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(Planner.helpText(), color = MemoColors.MutedInk, fontSize = 13.sp)
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
        item { Text("People (${people.size})", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
        if (people.isEmpty()) item { EmptyLine("No people yet. Try \"Add … to my CRM\".") }
        items(people, key = { it.fullName + it.phone }) { p ->
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Text(p.fullName, style = MaterialTheme.typography.titleMedium)
                if (p.notes.isNotBlank()) Text(p.notes, color = MemoColors.MutedInk, fontSize = 13.sp)
            }
        }
        item { Text("Companies (${companies.size})", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
        if (companies.isEmpty()) item { EmptyLine("Companies appear here once captured.") }
        items(companies, key = { it.domain }) { c ->
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Text(c.displayName.ifBlank { c.domain }, style = MaterialTheme.typography.titleMedium)
            }
        }
        item { Text("Tasks (${tasks.size})", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
        if (tasks.isEmpty()) item { EmptyLine("No tasks yet. Try \"Add task …\".") }
        items(tasks, key = { it.title + it.createdAt }) { t ->
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Pill(humanize(t.state.name))
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 20.dp), color = MemoColors.MutedInk, fontSize = 13.sp)
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
                    Text(d.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Pill(humanize(d.kind.name))
                }
                Spacer(Modifier.height(8.dp))
                Text(d.body, color = MemoColors.MutedInk, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(
    auditCount: Int,
    auditOk: Boolean,
    quarantined: Int,
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
            MemoCard {
                Text("Audit trail", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$auditCount outbound entries · chain " + if (auditOk) "verified" else "BROKEN",
                    color = if (auditOk) MemoColors.MutedInk else MemoColors.Amber,
                    fontSize = 13.sp,
                )
                if (quarantined > 0) {
                    Text("$quarantined stored rows were quarantined on load.", color = MemoColors.Amber, fontSize = 13.sp)
                }
            }
            MemoCard {
                Text("Kill switch", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Stops every active run. Side effects already approved are not undone.", color = MemoColors.MutedInk, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onStopAll) { Text("Stop all runs") }
            }
            MemoCard {
                Text("Data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Workspace, runs, audit and models live in app-private storage. Nothing leaves the device except pinned model downloads you start.",
                    color = MemoColors.MutedInk,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
