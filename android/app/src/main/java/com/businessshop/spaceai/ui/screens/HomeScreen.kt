package com.businessshop.spaceai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.AccessState
import com.businessshop.spaceai.data.ScanPhase
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.SectionCard
import com.businessshop.spaceai.ui.StorageRing
import com.businessshop.spaceai.ui.UiState

@Composable
fun HomeScreen(
    state: UiState,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onSeeResults: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SPACE AI", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings)) }
        }
        Spacer(Modifier.height(8.dp))
        StorageRing(state.storage)
        Spacer(Modifier.height(8.dp))

        val p = state.progress
        if (p != null) {
            SectionCard {
                val phase = stringResource(phaseLabel(p.phase))
                val counter = if (p.total > 1) stringResource(R.string.progress_count, p.done, p.total) else ""
                Text("$phase $counter", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (p.total > 1) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scan_local_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            PrimaryAction(stringResource(R.string.analyze), onAnalyze)
            if (state.result != null) {
                TextButton(onClick = onSeeResults) { Text(stringResource(R.string.see_last_results)) }
            }
            if (state.access.photos == AccessState.DENIED || state.access.videos == AccessState.DENIED) {
                Text(stringResource(R.string.permission_denied_note), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.access.photos == AccessState.LIMITED) {
                Text(stringResource(R.string.permission_limited_note), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.privacy_footer), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BrandFooter()
    }
}

/** Firma de la empresa: logotipo de Business Shop (variante clara/oscura según el tema). */
@Composable
fun BrandFooter(modifier: Modifier = Modifier, logoHeight: androidx.compose.ui.unit.Dp = 64.dp) {
    Column(modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.logo_business_shop),
            contentDescription = stringResource(R.string.logo_a11y),
            modifier = Modifier.height(logoHeight),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        Text(stringResource(R.string.made_by), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun phaseLabel(p: ScanPhase): Int = when (p) {
    ScanPhase.STORAGE -> R.string.phase_storage
    ScanPhase.LISTING -> R.string.phase_listing
    ScanPhase.PHOTOS -> R.string.phase_photos
    ScanPhase.DUPLICATES -> R.string.phase_duplicates
    ScanPhase.VIDEOS -> R.string.phase_videos
    ScanPhase.FILES -> R.string.phase_files
    ScanPhase.SUMMARY -> R.string.phase_summary
}
