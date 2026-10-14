package com.businessshop.spaceai.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.MediaKind
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.RiskBadge
import com.businessshop.spaceai.ui.Thumb
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.bytes
import com.businessshop.spaceai.ui.categoryTitle
import java.text.DateFormat
import java.util.Date

private enum class SortBy { SIZE, DATE, TYPE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(
    state: UiState,
    category: CleanupCategory,
    onToggle: (String) -> Unit,
    onSelectAll: (Collection<String>, Boolean) -> Unit,
    onSetKeeper: (List<String>, String) -> Unit,
    onCompress: (MediaItem) -> Unit,
    onDelete: () -> Unit,
) {
    val r = state.result ?: return
    val cat = r.category(category)
    val groups: List<List<MediaItem>> = when (category) {
        CleanupCategory.EXACT_DUPLICATE_PHOTOS -> r.exactDuplicates.filter { g -> g.items.all { it.kind == MediaKind.PHOTO } }.map { it.items }
        CleanupCategory.DUPLICATE_VIDEOS -> r.exactDuplicates.filter { g -> g.items.all { it.kind == MediaKind.VIDEO } }.map { it.items }
        CleanupCategory.DUPLICATE_FILES -> r.exactDuplicates.filter { g -> g.items.none { it.kind == MediaKind.PHOTO || it.kind == MediaKind.VIDEO } }.map { it.items }
        CleanupCategory.SIMILAR_PHOTOS -> r.similarGroups.filter { !it.isBurst }.map { g -> g.items.map { it.item } }
        CleanupCategory.BURST_PHOTOS -> r.similarGroups.filter { it.isBurst }.map { g -> g.items.map { it.item } }
        else -> emptyList()
    }
    var sort by remember { mutableStateOf(SortBy.SIZE) }
    val flat = (cat?.items ?: emptyList()).let { list ->
        when (sort) {
            SortBy.SIZE -> list.sortedByDescending { it.sizeBytes }
            SortBy.DATE -> list.sortedBy { if (it.dateTakenMillis > 0) it.dateTakenMillis else it.dateModifiedMillis }
            SortBy.TYPE -> list.sortedBy { it.mimeType ?: "" }
        }
    }
    val selectable = if (groups.isNotEmpty()) groups.flatten().filter { it.id !in state.keep } else flat.filter { it.id !in state.keep }
    val selectedHere = selectable.filter { it.id in state.selected }
    val isVideoCat = category in setOf(CleanupCategory.LARGE_VIDEOS, CleanupCategory.OLD_VIDEOS, CleanupCategory.COMPRESSIBLE_VIDEOS, CleanupCategory.SCREEN_RECORDINGS)
    val isFileCat = category == CleanupCategory.LARGE_FILES || category == CleanupCategory.DUPLICATE_FILES
    val asList = isVideoCat || isFileCat

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = if (asList) GridCells.Fixed(1) else GridCells.Adaptive(104.dp),
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(categoryTitle(category)), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        cat?.let { RiskBadge(it.risk) }
                    }
                    Text(stringResource(if (groups.isNotEmpty()) R.string.review_hint_groups else R.string.review_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isFileCat || isVideoCat) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(sort == SortBy.SIZE, { sort = SortBy.SIZE }, { Text(stringResource(R.string.sort_size)) })
                        FilterChip(sort == SortBy.DATE, { sort = SortBy.DATE }, { Text(stringResource(R.string.sort_date)) })
                        FilterChip(sort == SortBy.TYPE, { sort = SortBy.TYPE }, { Text(stringResource(R.string.sort_type)) })
                    }
                    Row {
                        TextButton(onClick = { onSelectAll(selectable.map { it.id }, true) }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick = { onSelectAll(selectable.map { it.id }, false) }) { Text(stringResource(R.string.select_none)) }
                    }
                }
            }
            if (groups.isNotEmpty() && !asList) {
                groups.forEachIndexed { gi, g ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$gi") {
                        Text(stringResource(R.string.group_n, gi + 1, g.size), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    items(g, key = { "g$gi-" + it.id }) { item ->
                        MediaTile(item, item.id in state.selected, item.id in state.keep,
                            onClick = { onToggle(item.id) }, onLongClick = { onSetKeeper(g.map { it.id }, item.id) })
                    }
                }
            } else {
                val rows = if (groups.isNotEmpty()) groups.flatten() else flat
                items(rows, key = { it.id }) { item ->
                    if (asList) FileRow(item, item.id in state.selected, item.id in state.keep, onToggle = { onToggle(item.id) },
                        onCompress = if (item.kind == MediaKind.VIDEO) ({ onCompress(item) }) else null)
                    else MediaTile(item, item.id in state.selected, item.id in state.keep, onClick = { onToggle(item.id) }, onLongClick = null)
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Box(Modifier.padding(16.dp)) {
                PrimaryAction(
                    stringResource(R.string.delete_selected, selectedHere.size, bytes(selectedHere.sumOf { it.sizeBytes })),
                    onDelete, enabled = selectedHere.isNotEmpty(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(item: MediaItem, selected: Boolean, keep: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val stateText = stringResource(when { keep -> R.string.state_keep; selected -> R.string.state_selected; else -> R.string.state_not_selected })
    Box(
        Modifier
            .combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected; stateDescription = stateText }
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Thumb(item)
        if (keep) Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.state_keep), tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp))
        Text(bytes(item.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

@Composable
private fun FileRow(item: MediaItem, selected: Boolean, keep: Boolean, onToggle: () -> Unit, onCompress: (() -> Unit)?) {
    val date = (if (item.dateTakenMillis > 0) item.dateTakenMillis else item.dateModifiedMillis).takeIf { it > 0 }
        ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = item.displayName },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !keep && item.deletable)
        if (item.kind == MediaKind.VIDEO) Thumb(item, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${bytes(item.sizeBytes)} · $date · ${item.mimeType ?: ""}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (!item.deletable) Text(stringResource(R.string.not_deletable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (onCompress != null) TextButton(onClick = onCompress) { Text(stringResource(R.string.compress)) }
    }
}
