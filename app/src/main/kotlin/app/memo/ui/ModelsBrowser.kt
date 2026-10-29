package app.memo.ui

import android.app.ActivityManager
import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.core.chat.Fit
import app.memo.core.chat.ModelFit
import app.memo.core.chat.ModelSettings
import app.memo.core.platform.ModelFormat
import app.memo.models.ModelStore
import app.memo.runtime.ChatSettingsRepository
import app.memo.runtime.MemoRuntime
import app.memo.ui.components.EmptyState
import app.memo.ui.components.IndeterminateProgressRing
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.MemoCard
import app.memo.ui.components.MemoSheet
import app.memo.ui.components.Pill
import app.memo.ui.components.ProgressRing
import app.memo.ui.components.ScreenHeader
import app.memo.ui.nav.SharedKind
import app.memo.ui.nav.sharedElement
import app.memo.ui.theme.MemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ModelSection(val label: String) { INSTALLED("Installed"), RECOMMENDED("Recommended"), BROWSE("Browse"), VOICE("Voice"), IMPORT("Import") }

@Composable
fun ModelsBrowserScreen(runtime: MemoRuntime) {
    val models = runtime.models
    val context = LocalContext.current
    val ramBytes = remember(context) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        info.totalMem
    }
    var section by remember { mutableStateOf(ModelSection.RECOMMENDED) }
    var search by remember { mutableStateOf("") }
    var formatFilter by remember { mutableStateOf<ModelFormat?>(null) }
    var familyFilter by remember { mutableStateOf<String?>(null) }
    var liveResults by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var deviceFiles by remember { mutableStateOf<List<ModelStore.Discovered>?>(null) }
    var settingsEntry by remember { mutableStateOf<ModelEntry?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var activeId by remember { mutableStateOf(models.activeId) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "imported-model.litertlm"
            models.importFrom({ context.contentResolver.openInputStream(uri) }, name)
        }
    }

    LaunchedEffect(section, search) {
        val generation = ++searchGeneration
        if (section == ModelSection.BROWSE) {
            delay(400)
            if (generation != searchGeneration) return@LaunchedEffect
            searching = true
            models.discoverOnline(search) { found ->
                if (generation == searchGeneration) {
                    liveResults = found
                    searching = false
                }
            }
        } else {
            searching = false
        }
    }
    LaunchedEffect(models.activeId) { activeId = models.activeId }

    val catalog = models.catalogEntries()
    val installed = catalog.filter { models.state(it).status == ModelStore.Status.INSTALLED }
    val browseCandidates = (catalog + liveResults).distinctBy { it.id }
    val browseItems = browseCandidates
        .filter { entry ->
            (search.isBlank() || listOf(entry.displayName, entry.repo, entry.family).any { it.contains(search, ignoreCase = true) }) &&
                (formatFilter == null || entry.format == formatFilter) &&
                (familyFilter == null || entry.family == familyFilter)
        }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Models", "Private models for local chat, voice, and planning.")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MemoTheme.spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2),
        ) {
            ModelSection.entries.forEach { tab ->
                MemoButton(
                    onClick = { section = tab },
                    style = if (section == tab) MemoButtonStyle.PRIMARY else MemoButtonStyle.QUIET,
                ) { Text(tab.label) }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = MemoTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space3),
        ) {
            if (actionMessage != null) {
                item(key = "model-action-message") {
                    Text(actionMessage.orEmpty(), Modifier.padding(horizontal = MemoTheme.spacing.space5), color = MemoTheme.colors.attention, style = MemoTheme.typography.bodySmall)
                }
            }
            if (models.importMessage != null) {
                item(key = "model-import-message") {
                    Text(
                        models.importMessage.orEmpty(),
                        Modifier.padding(horizontal = MemoTheme.spacing.space5),
                        color = if (models.importMessage?.startsWith("Import failed") == true) MemoTheme.colors.attention else MemoTheme.colors.inkMuted,
                        style = MemoTheme.typography.bodySmall,
                    )
                }
            }
            when (section) {
                ModelSection.INSTALLED -> {
                    if (installed.isEmpty()) item { EmptyState("No installed models", "Choose a recommendation or browse pinned model builds.") }
                    items(installed, key = { it.id }) { entry ->
                        ModelCard(entry, models, runtime, ramBytes, activeId, canDownload = true, onDefault = { activeId = it }, onSettings = { settingsEntry = entry }) {
                            actionMessage = it
                        }
                    }
                }
                ModelSection.RECOMMENDED -> {
                    item {
                        Text("Recommended for this phone", Modifier.padding(horizontal = MemoTheme.spacing.space5), style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                    }
                    val recommended = ModelCatalog.entries.sortedBy { fitRank(ModelFit.judge(ramBytes, it.minRamBytes, it.sizeBytes)) }
                    items(recommended, key = { it.id }) { entry ->
                        ModelCard(entry, models, runtime, ramBytes, activeId, canDownload = true, onDefault = { activeId = it }, onSettings = { settingsEntry = entry }) {
                            actionMessage = it
                        }
                    }
                }
                ModelSection.BROWSE -> {
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = MemoTheme.spacing.space5),
                            singleLine = true,
                            placeholder = { Text("Search models and families") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        )
                    }
                    item { FilterChoices("Format", listOf("All", "LiteRT-LM", "GGUF"), formatFilter?.let(::formatLabel) ?: "All") { choice -> formatFilter = when (choice) { "LiteRT-LM" -> ModelFormat.LITERTLM; "GGUF" -> ModelFormat.GGUF; else -> null } } }
                    val families = browseCandidates
                        .filter { entry ->
                            (search.isBlank() || listOf(entry.displayName, entry.repo, entry.family).any { it.contains(search, ignoreCase = true) }) &&
                                (formatFilter == null || entry.format == formatFilter)
                        }
                        .map { it.family }.distinct().sorted()
                    if (families.isNotEmpty()) item { FilterChoices("Family", listOf("All") + families, familyFilter ?: "All") { familyFilter = it.takeUnless { value -> value == "All" } } }
                    item {
                        Text(
                            when {
                                searching -> "Checking pinned model sources…"
                                models.discoveryError != null -> models.discoveryError.orEmpty()
                                else -> "Live results are pinned by revision and SHA-256 before download."
                            },
                            Modifier.padding(horizontal = MemoTheme.spacing.space5),
                            style = MemoTheme.typography.caption,
                            color = if (models.discoveryError != null) MemoTheme.colors.attention else MemoTheme.colors.inkMuted,
                        )
                    }
                    if (browseItems.isEmpty() && !searching) item { EmptyState("No models found", "Try another search or clear the format and family filters.") }
                    items(browseItems, key = { it.id }) { entry ->
                        ModelCard(
                            entry, models, runtime, ramBytes, activeId,
                            canDownload = models.entryFor(entry.id) == entry,
                            onDefault = { activeId = it },
                            onSettings = { settingsEntry = entry },
                        ) { actionMessage = it }
                    }
                }
                ModelSection.VOICE -> item {
                    EmptyState(
                        title = "Local voice models",
                        body = "Voice model downloads will appear here as the M2 speech packages are completed.",
                        icon = Icons.Outlined.Lock,
                    )
                }
                ModelSection.IMPORT -> {
                    item {
                        MemoCard(Modifier.padding(horizontal = MemoTheme.spacing.space5)) {
                            Text("Import a model", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                            Spacer(Modifier.height(MemoTheme.spacing.space2))
                            Text("Only a file matching a pinned SHA-256 becomes runnable. Other imports stay unverified.", style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
                            Spacer(Modifier.height(MemoTheme.spacing.space3))
                            Row(horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
                                MemoButton(onClick = { importPicker.launch(arrayOf("*/*")) }, style = MemoButtonStyle.SECONDARY) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(MemoTheme.spacing.space2)); Text("Pick file")
                                }
                                MemoButton(
                                    onClick = { scanning = true; models.discover { found -> deviceFiles = found; scanning = false } },
                                    enabled = !scanning,
                                    style = MemoButtonStyle.SECONDARY,
                                ) { Text(if (scanning) "Scanning…" else "Scan device") }
                            }
                            if (scanning) {
                                Spacer(Modifier.height(MemoTheme.spacing.space3))
                                IndeterminateProgressRing("Scanning app storage", size = 20.dp, strokeWidth = 2.dp)
                            }
                        }
                    }
                    deviceFiles?.let { found ->
                        item { Text(if (found.isEmpty()) "No model files found." else "Found ${found.size} file(s)", Modifier.padding(horizontal = MemoTheme.spacing.space5), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted) }
                        items(found, key = { it.path }) { file ->
                            MemoCard(Modifier.padding(horizontal = MemoTheme.spacing.space5)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name, style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                                        Text("${mb(file.sizeBytes)}${if (file.matchesCatalogId != null) " · size matches a pinned model" else " · unverified"}", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                                    }
                                    MemoButton(onClick = { models.importFrom({ File(file.path).inputStream() }, file.name) }, style = MemoButtonStyle.QUIET) { Text("Verify") }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("${mb(models.freeBytes())} free for model files", Modifier.padding(horizontal = MemoTheme.spacing.space5), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkFaint) }
        }
    }

    settingsEntry?.let { entry ->
        ModelSettingsSheet(entry, runtime.chatSettings, onDismiss = { settingsEntry = null }) {
            actionMessage = "Settings saved for ${entry.displayName}."
            settingsEntry = null
        }
    }
}

@Composable
private fun FilterChoices(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
        Text(title, Modifier.padding(horizontal = MemoTheme.spacing.space5), style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MemoTheme.spacing.space5), horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
            choices.forEach { choice ->
                MemoButton(onClick = { onSelect(choice) }, style = if (choice == selected) MemoButtonStyle.SECONDARY else MemoButtonStyle.QUIET) { Text(choice) }
            }
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    models: ModelStore,
    runtime: MemoRuntime,
    ramBytes: Long,
    activeId: String?,
    canDownload: Boolean,
    onDefault: (String) -> Unit,
    onSettings: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val state = models.state(entry)
    val fit = ModelFit.judge(ramBytes, entry.minRamBytes, entry.sizeBytes)
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    var uninstalling by remember(entry.id) { mutableStateOf(false) }
    MemoCard(Modifier.padding(horizontal = MemoTheme.spacing.space5).sharedElement(SharedKind.MODEL, entry.id)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.displayName, Modifier.weight(1f), style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Pill(entry.role.name.lowercase())
        }
        Text(entry.description, style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
        Text("${mb(entry.sizeBytes)} · ${entry.family}${entry.quant?.let { " · $it" }.orEmpty()}", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FitBadge(fit)
            Pill(entry.license?.let { "License · $it" } ?: "License not listed")
            if (entry.gated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = "Gated model")
                    Pill("Access required")
                }
            }
        }
        if (state.status == ModelStore.Status.DOWNLOADING || state.status == ModelStore.Status.VERIFYING) {
            Spacer(Modifier.height(MemoTheme.spacing.space2))
            if (state.status == ModelStore.Status.DOWNLOADING) {
                ProgressRing(state.progress, "${entry.displayName} download progress", size = 36.dp)
            } else {
                IndeterminateProgressRing("Verifying ${entry.displayName}", size = 22.dp, strokeWidth = 2.dp)
            }
        }
        if (state.message.isNotBlank()) {
            Text(state.message, style = MemoTheme.typography.caption, color = if (state.status == ModelStore.Status.FAILED) MemoTheme.colors.attention else MemoTheme.colors.inkMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.status == ModelStore.Status.INSTALLED -> {
                    if (activeId == entry.id) Pill("Default", emphasis = true)
                    else MemoButton(onClick = { models.activeId = entry.id; onDefault(entry.id) }, style = MemoButtonStyle.SECONDARY) { Text("Use as default") }
                    MemoButton(onClick = onSettings, style = MemoButtonStyle.QUIET) { Icon(Icons.Outlined.Settings, contentDescription = "Model settings", modifier = Modifier.size(18.dp)) }
                    MemoButton(onClick = { confirmDelete = true }, enabled = !uninstalling, style = MemoButtonStyle.QUIET, contentColor = MemoTheme.colors.danger) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete model", modifier = Modifier.size(18.dp))
                    }
                }
                state.status == ModelStore.Status.DOWNLOADING -> {
                    MemoButton(onClick = { models.cancel(entry) }, style = MemoButtonStyle.SECONDARY) { Text("Pause") }
                    MemoButton(onClick = onSettings, style = MemoButtonStyle.QUIET) { Icon(Icons.Outlined.Settings, contentDescription = "Model settings", modifier = Modifier.size(18.dp)) }
                }
                state.status == ModelStore.Status.VERIFYING -> {
                    MemoButton(onClick = onSettings, style = MemoButtonStyle.QUIET) { Icon(Icons.Outlined.Settings, contentDescription = "Model settings", modifier = Modifier.size(18.dp)) }
                }
                else -> {
                    MemoButton(
                        onClick = { models.download(entry) },
                        enabled = canDownload && !entry.gated && fit != Fit.TOO_LARGE,
                        style = MemoButtonStyle.PRIMARY,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(MemoTheme.spacing.space2)); Text(if (!canDownload) "Pin unavailable" else if (state.message.startsWith("Paused")) "Resume" else "Download")
                    }
                    MemoButton(onClick = onSettings, style = MemoButtonStyle.QUIET) { Icon(Icons.Outlined.Settings, contentDescription = "Model settings", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${entry.displayName}?") },
        text = { Text("The verified model file and its local download data will be removed. You can download it again later.") },
        confirmButton = {
            TextButton(
                enabled = !uninstalling,
                onClick = {
                    confirmDelete = false
                    uninstalling = true
                    models.uninstall(entry, runtime.chatService::unloadIfIdle) { removed ->
                        uninstalling = false
                        onMessage(if (removed) "${entry.displayName} removed." else "Finish local inference before removing this model, or try again.")
                    }
                },
            ) { Text(if (uninstalling) "Removing…" else "Remove", color = MemoTheme.colors.danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep model") } },
    )
}

@Composable
private fun FitBadge(fit: Fit) {
    val text = when (fit) {
        Fit.FITS -> "Fits this phone"
        Fit.TIGHT -> "Tight fit"
        Fit.TOO_LARGE -> "Too large"
        Fit.UNKNOWN -> "Fit unknown"
    }
    Pill(text, emphasis = fit == Fit.FITS)
}

@Composable
private fun ModelSettingsSheet(
    entry: ModelEntry,
    store: ChatSettingsRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var settings by remember(entry.id) { mutableStateOf(ModelSettings.DEFAULT.copy(thinking = entry.thinkingByDefault)) }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var saving by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) {
        try {
            settings = withContext(Dispatchers.IO) { store.load(entry.id) }
        } catch (_: RuntimeException) {
            error = "Saved settings could not be read; the stored bytes were kept."
        } finally {
            loading = false
        }
    }
    MemoSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Model settings", style = MemoTheme.typography.title, color = MemoTheme.colors.ink)
                    Text(entry.displayName, style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close settings") }
            }
            if (loading) Text("Loading saved settings…", style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.inkMuted)
            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { settings = settings.copy(systemPrompt = it.take(ModelSettings.MAX_PROMPT_CHARS)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && error == null,
                label = { Text("System prompt") },
                minLines = 3,
                maxLines = 6,
                supportingText = { Text("${settings.systemPrompt.length}/${ModelSettings.MAX_PROMPT_CHARS}") },
            )
            Column {
                Text("Temperature · ${"%.1f".format(settings.temperature)}", style = MemoTheme.typography.label, color = MemoTheme.colors.ink)
                Slider(
                    value = settings.temperature.toFloat(),
                    onValueChange = { settings = settings.copy(temperature = it.toDouble()) },
                    valueRange = 0f..2f,
                    steps = 19,
                    enabled = !loading && error == null,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
                Text("Context size · ${settings.contextTokens}", style = MemoTheme.typography.label, color = MemoTheme.colors.ink)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2)) {
                    listOf(512, 1024, 2048, 4096, 8192, 16384, 32768).forEach { size ->
                        MemoButton(onClick = { settings = settings.copy(contextTokens = size) }, enabled = !loading && error == null, style = if (settings.contextTokens == size) MemoButtonStyle.SECONDARY else MemoButtonStyle.QUIET) { Text(if (size >= 1024) "${size / 1024}K" else "$size") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Thinking mode", style = MemoTheme.typography.label, color = MemoTheme.colors.ink)
                    Text("Use the model’s reasoning channel when supported.", style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted)
                }
                Switch(checked = settings.thinking, onCheckedChange = { settings = settings.copy(thinking = it) }, enabled = !loading && error == null)
            }
            error?.let { Text(it, style = MemoTheme.typography.bodySmall, color = MemoTheme.colors.attention) }
            MemoButton(
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            withContext(Dispatchers.IO) { store.save(entry.id, settings) }
                            onSaved()
                        } catch (_: RuntimeException) {
                            error = "Settings could not be saved; existing data was kept."
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !loading && !saving && error == null,
                style = MemoButtonStyle.PRIMARY,
            ) { Text(if (saving) "Saving…" else "Save settings") }
        }
    }
}

private fun fitRank(fit: Fit): Int = when (fit) { Fit.FITS -> 0; Fit.TIGHT -> 1; Fit.UNKNOWN -> 2; Fit.TOO_LARGE -> 3 }

private fun formatLabel(format: ModelFormat): String = when (format) {
    ModelFormat.LITERTLM -> "LiteRT-LM"
    ModelFormat.GGUF -> "GGUF"
}

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
