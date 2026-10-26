package app.memo.ui

import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memo.core.catalog.ModelCatalog
import app.memo.core.catalog.ModelEntry
import app.memo.models.ModelStore
import app.memo.ui.components.IndeterminateProgressRing
import app.memo.ui.components.MemoButton
import app.memo.ui.components.MemoButtonStyle
import app.memo.ui.components.MemoCard
import app.memo.ui.components.Pill
import app.memo.ui.components.ProgressRing
import app.memo.ui.components.ScreenHeader
import app.memo.ui.nav.SharedKind
import app.memo.ui.nav.sharedElement
import app.memo.ui.theme.MemoTheme
import java.io.File

/**
 * One-tap model acquisition. Each catalog entry is a pinned source (repo +
 * revision + SHA-256). Discovery finds model files already on the device and
 * lets the user adopt them; a file is used only when its hash matches a pin.
 */
@Composable
fun ModelsScreen(models: ModelStore) {
    val context = LocalContext.current
    var discovered by remember { mutableStateOf<List<ModelStore.Discovered>?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c: Cursor -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "imported-model.litertlm"
            models.importFrom({ context.contentResolver.openInputStream(uri) }, name)
        }
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                "Models",
                "On-device models. One tap downloads and verifies a pinned build.",
            )
        }

        items(ModelCatalog.entries, key = { it.id }) { entry ->
            ModelCard(entry, models)
        }

        item {
            MemoCard(Modifier.padding(horizontal = 20.dp)) {
                Text("Already on this device", style = MemoTheme.typography.subtitle, color = MemoTheme.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scan app storage for model files, or pick one you downloaded yourself. " +
                        "Only files whose SHA-256 matches a pinned model are loaded.",
                    color = MemoTheme.colors.inkMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MemoButton(
                        onClick = {
                            scanning = true
                            models.discover { found ->
                                discovered = found
                                scanning = false
                            }
                        },
                        enabled = !scanning,
                        style = MemoButtonStyle.SECONDARY,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (scanning) "Scanning" else "Scan device")
                    }
                    MemoButton(
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                        style = MemoButtonStyle.SECONDARY,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Pick file")
                    }
                }
                if (scanning) {
                    Spacer(Modifier.height(10.dp))
                    IndeterminateProgressRing(contentDescription = "Scanning app storage", size = 20.dp, strokeWidth = 2.dp)
                }
            }
        }

        discovered?.let { found ->
            item {
                Text(
                    if (found.isEmpty()) "No model files found in app storage." else "Found ${found.size} file(s)",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MemoTheme.colors.inkMuted,
                    fontSize = 13.sp,
                )
            }
            items(found, key = { it.path }) { file ->
                MemoCard(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MemoTheme.typography.subtitle, fontSize = 14.sp, color = MemoTheme.colors.ink)
                            Text(
                                "${mb(file.sizeBytes)}" + if (file.matchesCatalogId != null) " · size matches a pinned model" else "",
                                color = MemoTheme.colors.inkMuted,
                                fontSize = 12.sp,
                            )
                        }
                        MemoButton(
                            onClick = { models.importFrom({ File(file.path).inputStream() }, file.name) },
                            style = MemoButtonStyle.QUIET,
                        ) { Text("Verify & import") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(entry: ModelEntry, models: ModelStore) {
    val state = models.state(entry)
    MemoCard(Modifier.padding(horizontal = 20.dp).sharedElement(SharedKind.MODEL, entry.id)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.displayName,
                style = MemoTheme.typography.subtitle,
                color = MemoTheme.colors.ink,
                modifier = Modifier.weight(1f),
            )
            Pill(entry.role.name.lowercase())
        }
        Spacer(Modifier.height(4.dp))
        Text(entry.description, color = MemoTheme.colors.inkMuted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "${mb(entry.sizeBytes)} · ${entry.repo}",
            color = MemoTheme.colors.inkMuted,
            fontSize = 12.sp,
        )

        if (state.status == ModelStore.Status.DOWNLOADING || state.status == ModelStore.Status.VERIFYING) {
            Spacer(Modifier.height(10.dp))
            ProgressRing(
                progress = state.progress,
                contentDescription = "${entry.displayName} download progress",
                size = 36.dp,
            )
        }
        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.message,
                color = if (state.status == ModelStore.Status.FAILED) MemoTheme.colors.attention else MemoTheme.colors.inkMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        val installed = state.status == ModelStore.Status.INSTALLED
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                installed -> {
                    val active = models.activeId == entry.id
                    if (active) {
                        Pill("Active", emphasis = true)
                    } else {
                        MemoButton(onClick = { models.activeId = entry.id }, style = MemoButtonStyle.SECONDARY) {
                            Text("Use this model")
                        }
                    }
                }
                state.status == ModelStore.Status.DOWNLOADING -> {
                    MemoButton(onClick = { models.cancel(entry) }, style = MemoButtonStyle.SECONDARY) { Text("Pause") }
                }
                state.status == ModelStore.Status.VERIFYING -> {
                    IndeterminateProgressRing(contentDescription = "Verifying ${entry.displayName}", size = 18.dp, strokeWidth = 2.dp)
                }
                else -> {
                    MemoButton(onClick = { models.download(entry) }, style = MemoButtonStyle.PRIMARY) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.message.startsWith("Partial")) "Resume" else "Download")
                    }
                }
            }
        }
    }
}

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
