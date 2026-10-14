package com.businessshop.spaceai.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.AccessState
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.RiskBadge
import com.businessshop.spaceai.ui.SectionCard
import com.businessshop.spaceai.ui.StatRow
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.bytes
import com.businessshop.spaceai.ui.categoryTitle
import com.businessshop.spaceai.ui.recommendationText

@Composable
fun ResultsScreen(
    state: UiState,
    onOpenCategory: (CleanupCategory) -> Unit,
    onReviewAndFree: () -> Unit,
    onClearTemp: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val r = state.result ?: return
    var showGuide by remember { mutableStateOf(false) }
    val similarCount = (r.category(CleanupCategory.SIMILAR_PHOTOS)?.items?.size ?: 0) +
        (r.category(CleanupCategory.BURST_PHOTOS)?.items?.size ?: 0) +
        (r.category(CleanupCategory.BLURRY_PHOTOS)?.items?.size ?: 0) +
        (r.category(CleanupCategory.DARK_PHOTOS)?.items?.size ?: 0) +
        (r.category(CleanupCategory.SCREENSHOTS)?.items?.size ?: 0)
    val dupBytes = r.exactDuplicates.sumOf { it.reclaimableBytes }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard {
                Text(stringResource(R.string.potentially_recoverable), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(bytes(r.potentiallyRecoverableBytes), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.estimate_disclaimer), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                StatRow(stringResource(R.string.photos_to_review), similarCount.toString())
                StatRow(stringResource(R.string.large_videos), (r.category(CleanupCategory.LARGE_VIDEOS)?.items?.size ?: 0).toString())
                StatRow(stringResource(R.string.duplicate_files), bytes(dupBytes))
                StatRow(stringResource(R.string.photos_total), "${r.photoCount} · ${bytes(r.photoBytes)}")
                StatRow(stringResource(R.string.videos_total), "${r.videoCount} · ${bytes(r.videoBytes)}")
                StatRow(stringResource(R.string.files_total), "${r.fileCount} · ${bytes(r.fileBytes)}")
                if (r.unreadable.isNotEmpty()) StatRow(stringResource(R.string.unreadable), r.unreadable.size.toString())
            }
        }
        item { PrimaryAction(stringResource(R.string.review_and_free), onReviewAndFree, enabled = r.categories.isNotEmpty()) }

        if (r.recommendations.isNotEmpty()) {
            item { Text(stringResource(R.string.smart_recommendations), style = MaterialTheme.typography.titleMedium) }
            items(r.recommendations, key = { it.messageKey }) { rec ->
                SectionCard(Modifier.clickable(role = Role.Button) {
                    when (rec.category) {
                        CleanupCategory.APP_TEMP_FILES -> onClearTemp()
                        else -> onOpenCategory(rec.category)
                    }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(categoryTitle(rec.category)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        RiskBadge(rec.risk)
                    }
                    Text(stringResource(recommendationText(rec.category), bytes(rec.bytes), stringResource(categoryTitle(rec.category)).lowercase()),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Text(stringResource(R.string.all_categories), style = MaterialTheme.typography.titleMedium) }
        items(r.categories, key = { it.category.name }) { c ->
            SectionCard(Modifier.clickable(role = Role.Button) { onOpenCategory(c.category) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(categoryTitle(c.category)), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (c.isEstimate) R.string.count_bytes_estimate else R.string.count_bytes, c.items.size, bytes(c.bytes)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RiskBadge(c.risk)
                }
            }
        }

        item {
            SectionCard(Modifier.clickable(role = Role.Button, onClick = onOpenFiles)) {
                Text(stringResource(R.string.files_folders_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.files_folders_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleMedium)
                StatRow(stringResource(R.string.access_photos), stringResource(accessLabel(r.access.photos)))
                StatRow(stringResource(R.string.access_videos), stringResource(accessLabel(r.access.videos)))
                StatRow(stringResource(R.string.access_files), stringResource(accessLabel(r.access.files)))
                StatRow(stringResource(R.string.access_other_caches), stringResource(accessLabel(r.access.otherAppsCaches)))
                TextButton(onClick = { showGuide = true }) { Text(stringResource(R.string.other_apps_guide_button)) }
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.cat_app_temp), style = MaterialTheme.typography.titleMedium)
                Text(bytes(r.appTempBytes), style = MaterialTheme.typography.bodyLarge)
                state.tempFreedBytes?.let { Text(stringResource(R.string.temp_freed, bytes(it)), style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onClearTemp, enabled = r.appTempBytes > 0) { Text(stringResource(R.string.clear_temp)) }
            }
        }
    }

    if (showGuide) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(stringResource(R.string.other_apps_guide_title)) },
            text = { Text(stringResource(R.string.other_apps_guide_body_android)) },
            confirmButton = {
                TextButton(onClick = {
                    showGuide = false
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.open_storage_settings)) }
            },
            dismissButton = { TextButton(onClick = { showGuide = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

fun accessLabel(a: AccessState): Int = when (a) {
    AccessState.FULL -> R.string.access_full
    AccessState.LIMITED -> R.string.access_limited
    AccessState.DENIED -> R.string.access_denied
    AccessState.RESTRICTED_BY_OS -> R.string.access_restricted
    AccessState.NOT_REQUESTED -> R.string.access_not_requested
}
