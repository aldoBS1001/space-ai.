package com.businessshop.spaceai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.CompressionPreset
import com.businessshop.spaceai.core.Sensitivity
import com.businessshop.spaceai.data.AppSettings
import com.businessshop.spaceai.data.DeleteMode
import com.businessshop.spaceai.data.DocumentTreeScanner
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.SectionCard
import com.businessshop.spaceai.ui.StatRow
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.bytes
import java.text.DateFormat
import java.util.Date

@Composable
private fun <T> RadioGroup(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton) { onSelect(value) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == value, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onOpenHistory: () -> Unit,
    onOpenFolders: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
) {
    // Estado local para recomponer al cambiar; la fuente de verdad es AppSettings.
    var tick by remember { mutableIntStateOf(0) }
    key(tick) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        SectionCard {
            Text(stringResource(R.string.sensitivity), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sensitivity_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RadioGroup(
                listOf(Sensitivity.STRICT to R.string.sens_strict, Sensitivity.NORMAL to R.string.sens_normal, Sensitivity.RELAXED to R.string.sens_relaxed),
                settings.sensitivity,
            ) { settings.sensitivity = it; tick++ }
            Row(Modifier.fillMaxWidth().toggleable(settings.conservativeQuality, role = Role.Switch) { settings.conservativeQuality = it; tick++ },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conservative_quality), Modifier.weight(1f))
                Switch(checked = settings.conservativeQuality, onCheckedChange = null)
            }
        }
        SectionCard {
            Text(stringResource(R.string.delete_mode), style = MaterialTheme.typography.titleMedium)
            RadioGroup(listOf(DeleteMode.TRASH to R.string.mode_trash, DeleteMode.PERMANENT to R.string.mode_permanent), settings.deleteMode) {
                settings.deleteMode = it; tick++
            }
        }
        SectionCard {
            Text(stringResource(R.string.compression_quality), style = MaterialTheme.typography.titleMedium)
            RadioGroup(
                listOf(CompressionPreset.HIGH to R.string.preset_high, CompressionPreset.BALANCED to R.string.preset_balanced, CompressionPreset.SMALL to R.string.preset_small),
                settings.preset,
            ) { settings.preset = it; tick++ }
        }
        SectionCard {
            TextButton(onClick = onOpenFolders) { Text(stringResource(R.string.files_folders_title)) }
            TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.history)) }
            TextButton(onClick = onClearCache) { Text(stringResource(R.string.clear_analysis_cache)) }
            TextButton(onClick = onClearHistory) { Text(stringResource(R.string.clear_history)) }
        }
        SectionCard {
            Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
        }
    } }
}

@Composable
fun FoldersScreen(docs: DocumentTreeScanner, onChanged: () -> Unit) {
    var trees by remember { mutableStateOf(docs.grantedTrees()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { runCatching { docs.persist(uri) }; trees = docs.grantedTrees(); onChanged() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.files_folders_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.folders_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(trees, key = { it.toString() }) { t ->
            SectionCard {
                Text(Uri.decode(t.lastPathSegment ?: t.toString()), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { docs.release(t); trees = docs.grantedTrees(); onChanged() }) { Text(stringResource(R.string.remove_access)) }
            }
        }
        item { PrimaryAction(stringResource(R.string.add_folder), { picker.launch(null) }) }
    }
}

@Composable
fun HistoryScreen(state: UiState, onLoad: () -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.history), style = MaterialTheme.typography.headlineSmall) }
        if (state.history.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
        items(state.history, key = { it.timestamp }) { h ->
            SectionCard {
                Text(DateFormat.getDateTimeInstance().format(Date(h.timestamp)), style = MaterialTheme.typography.titleMedium)
                StatRow(stringResource(R.string.measured_freed), bytes(h.measuredFreedBytes.coerceAtLeast(0)))
                StatRow(stringResource(R.string.deleted_count), "${h.deletedCount} · ${bytes(h.deletedBytes)}")
                StatRow(stringResource(R.string.trashed_count), "${h.trashedCount} · ${bytes(h.trashedBytes)}")
                StatRow(stringResource(R.string.failed_count), h.failedCount.toString())
            }
        }
    }
}
