package com.businessshop.spaceai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.CleanupStatus
import com.businessshop.spaceai.core.ItemOutcome
import com.businessshop.spaceai.data.DeleteMode
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.RiskBadge
import com.businessshop.spaceai.ui.SectionCard
import com.businessshop.spaceai.ui.StatRow
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.bytes
import com.businessshop.spaceai.ui.categoryTitle

/** Pasos 1-3 de la eliminación segura: lista exacta, bytes, confirmación explícita. */
@Composable
fun ConfirmScreen(state: UiState, mode: DeleteMode, onConfirm: (Boolean) -> Unit, onCancel: () -> Unit, onCancelRunning: () -> Unit) {
    val plan = state.pendingPlan
    if (plan == null) {
        Text(stringResource(R.string.nothing_selected), Modifier.padding(24.dp)); return
    }
    var ack by remember(plan.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        RiskBadge(plan.maxRisk)
                    }
                    StatRow(stringResource(R.string.files_count), plan.count.toString())
                    StatRow(stringResource(R.string.potentially_recoverable), bytes(plan.totalBytes))
                    Text(
                        stringResource(if (mode == DeleteMode.TRASH) R.string.mode_trash_note else R.string.mode_permanent_note),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.system_dialog_note), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(plan.items, key = { it.item.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(stringResource(categoryTitle(p.category)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(bytes(p.item.sizeBytes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan.requiresExplicitApproval) {
                Row(
                    Modifier.fillMaxWidth().toggleable(ack, role = Role.Checkbox, onValueChange = { ack = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ack, onCheckedChange = null)
                    Text(stringResource(R.string.ack_text, plan.count), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.cleaning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.cleaning), modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelRunning) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                PrimaryAction(
                    stringResource(if (mode == DeleteMode.TRASH) R.string.move_to_trash_n else R.string.delete_n, plan.count),
                    { onConfirm(ack) }, enabled = !plan.requiresExplicitApproval || ack,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** Pasos 5-6: resultados verificados, papelera separada de espacio liberado. */
@Composable
fun ReportScreen(state: UiState, onDeleteTrashed: () -> Unit, onDone: () -> Unit) {
    val r = state.lastReport ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text(stringResource(statusTitle(r.status)), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(statusBody(r.status)), style = MaterialTheme.typography.bodyMedium)
                StatRow(stringResource(R.string.measured_freed), bytes(r.measuredFreedBytes.coerceAtLeast(0)))
                StatRow(stringResource(R.string.deleted_count), "${r.deletedCount} · ${bytes(r.deletedBytes)}")
                StatRow(stringResource(R.string.trashed_count), "${r.trashedCount} · ${bytes(r.trashedBytes)}")
                StatRow(stringResource(R.string.failed_count), r.failed.size.toString())
                StatRow(stringResource(R.string.denied_count), r.results.count { it.outcome == ItemOutcome.USER_DENIED }.toString())
                StatRow(stringResource(R.string.free_now), bytes(r.after.freeBytes))
            }
        }
        if (r.trashedCount > 0) item {
            SectionCard {
                Text(stringResource(R.string.trash_explainer), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDeleteTrashed) { Text(stringResource(R.string.empty_trash_now)) }
            }
        }
        if (r.failed.isNotEmpty()) {
            item { Text(stringResource(R.string.failures), style = MaterialTheme.typography.titleMedium) }
            items(r.failed, key = { it.item.id }) { f ->
                Text("${f.item.displayName}: ${f.error ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { PrimaryAction(stringResource(R.string.done), onDone) }
    }
}

private fun statusTitle(s: CleanupStatus) = when (s) {
    CleanupStatus.VERIFIED_FREED -> R.string.status_verified
    CleanupStatus.PENDING_SYSTEM_TRASH -> R.string.status_trash
    CleanupStatus.UNVERIFIED -> R.string.status_unverified
    CleanupStatus.PARTIAL_FAILURE -> R.string.status_partial
    CleanupStatus.NOTHING_DELETED -> R.string.status_nothing
    CleanupStatus.CANCELLED -> R.string.status_cancelled
}

private fun statusBody(s: CleanupStatus) = when (s) {
    CleanupStatus.VERIFIED_FREED -> R.string.status_verified_body
    CleanupStatus.PENDING_SYSTEM_TRASH -> R.string.status_trash_body
    CleanupStatus.UNVERIFIED -> R.string.status_unverified_body
    CleanupStatus.PARTIAL_FAILURE -> R.string.status_partial_body
    CleanupStatus.NOTHING_DELETED -> R.string.status_nothing_body
    CleanupStatus.CANCELLED -> R.string.status_cancelled_body
}
