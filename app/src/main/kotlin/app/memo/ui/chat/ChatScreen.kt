package app.memo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memo.core.agent.ExtractionResult
import app.memo.core.chat.ChatMessage
import app.memo.core.chat.ModelSettings
import app.memo.core.codec.SynCodec
import app.memo.core.platform.ModelFormat
import app.memo.models.ModelStore
import app.memo.runtime.ChatService
import app.memo.runtime.Extracted
import app.memo.runtime.MemoRuntime
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.UndoSnackbarHost
import app.memo.ui.components.showUndo
import app.memo.ui.theme.MemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(runtime: MemoRuntime) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ExtractionResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    val controller = remember(runtime) {
        ChatController(
            repo = runtime.chats,
            service = runtime.chatService,
            scope = scope,
            installedModels = {
                val installed = runtime.models.catalogEntries()
                    .filter { runtime.models.state(it).status == ModelStore.Status.INSTALLED }
                    .map { it.id }
                val selected = runtime.models.activeId
                selected?.takeIf(installed::contains)?.let { id ->
                    listOf(id) + installed.filterNot { it == id }
                } ?: installed
            },
            settingsFor = runtime.chatSettings::load,
            runCommand = runtime::handleUtterance,
            runPlan = runtime::handlePlanRequest,
            modelEntryFor = runtime.models::entryFor,
        )
    }
    val planCards = remember(runtime) {
        PlanCardHooks(
            runFor = { id -> runtime.snapshot.value.runs.firstOrNull { it.id == id } },
            onRun = runtime::runProposal,
            onDiscard = runtime::discardRun,
            onDecide = { runId, stepId, approve, hash -> runtime.decide(runId, stepId, approve, hash) },
            onStop = runtime::discardRun,
        )
    }
    val streaming by runtime.chatService.streaming.collectAsState()
    val modelStatus by runtime.chatService.modelStatus.collectAsState()
    val installedEntries = runtime.models.catalogEntries().filter {
        runtime.models.state(it).status == ModelStore.Status.INSTALLED
    }
    val selected = runtime.models.entryFor(controller.modelId ?: "")
    val modelLabel = when (val state = modelStatus) {
        is ChatService.ModelStatus.Loading -> "Loading ${runtime.models.entryFor(state.modelId)?.displayName ?: "model"}"
        is ChatService.ModelStatus.Failed -> "Model could not be loaded"
        is ChatService.ModelStatus.Ready -> runtime.models.entryFor(state.modelId)?.displayName ?: "Local model ready"
        ChatService.ModelStatus.None -> selected?.displayName ?: "Choose a local model"
    }

    /** Finds people and things in one message (ENT.4b). The model runs off main; the sheet opens on main. */
    fun extract(message: ChatMessage) {
        if (extracting) return
        extracting = true
        runtime.extractEntities(message.text, message.id) { outcome ->
            scope.launch {
                extracting = false
                when (outcome) {
                    is Extracted.Ready -> review = outcome.result
                    Extracted.NoModel -> controller.showNotice("Install a chat model in Models to find people and things.")
                    is Extracted.Failed -> controller.showNotice("Could not read this message: ${outcome.message}")
                }
            }
        }
    }

    LaunchedEffect(controller) {
        controller.newChat()
        controller.refresh()
    }
    LaunchedEffect(streaming?.requestId) { controller.setGenerating(streaming != null) }
    LaunchedEffect(installedEntries.map { it.id }) { controller.chooseDefaultIfAbsent() }
    LaunchedEffect(controller.notice) {
        controller.notice?.let { message ->
            snackbar.showSnackbar(message)
            controller.dismissNotice()
        }
    }
    LaunchedEffect(controller.undo) {
        if (controller.undo != null) snackbar.showUndo(controller.undo!!.label, controller::runUndo)
    }
    DisposableEffect(controller) {
        onDispose { controller.flushDraft() }
    }

    // Edge-to-edge (MainActivity) disables adjustResize: lift the composer above the keyboard ourselves.
    Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.Top) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MemoTheme.spacing.space4, vertical = MemoTheme.spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MemoTheme.spacing.space2),
        ) {
            MemoButton(onClick = { historyOpen = true }, style = MemoButtonStyle.QUIET) { Text("History") }
            Column(Modifier.weight(1f)) {
                Text("Talk", style = MemoTheme.typography.title, color = MemoTheme.colors.ink)
                Text(modelLabel, style = MemoTheme.typography.caption, color = MemoTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MemoButton(onClick = { modelsOpen = true }, style = MemoButtonStyle.SECONDARY) {
                Text(selected?.format?.pickerLabel() ?: "Model", maxLines = 1)
            }
            MemoButton(onClick = controller::newChat, style = MemoButtonStyle.QUIET) { Text("New") }
        }
        ChatThread(
            controller = controller,
            streaming = streaming,
            onSuggestion = controller::onTextChanged,
            readClip = runtime::readVoiceClip,
            planCards = planCards,
            onExtract = ::extract,
            modifier = Modifier.weight(1f),
        )
        if (extracting) {
            Text(
                "Finding people and things…",
                style = MemoTheme.typography.caption,
                color = MemoTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = MemoTheme.spacing.space5),
            )
        }
        ChatComposer(controller, streaming, runtime.voiceMessages)
    }
    UndoSnackbarHost(snackbar)

    review?.let { result ->
        EntityReviewSheet(
            result = result,
            saving = saving,
            onSave = { selected, relations ->
                saving = true
                scope.launch {
                    val line = withContext(Dispatchers.IO) {
                        runCatching { runtime.entityService.approve(result, selected, relations) }
                    }.fold(
                        onSuccess = { runtime.entityService.summaryLine(it) },
                        onFailure = { "Could not save: ${SynCodec.safeMessage(it, 160)}" },
                    )
                    saving = false
                    review = null
                    controller.showNotice(line)
                }
            },
            onDismiss = { review = null },
        )
    }

    if (historyOpen) ChatHistorySheet(controller) { historyOpen = false }
    if (modelsOpen) ChatModelPickerSheet(
        models = installedEntries,
        selectedId = controller.modelId,
        onSelect = controller::setModel,
        onDismiss = { modelsOpen = false },
    )
}

private fun ModelFormat.pickerLabel(): String = when (this) {
    ModelFormat.LITERTLM -> "LiteRT-LM"
    ModelFormat.GGUF -> "GGUF"
}
