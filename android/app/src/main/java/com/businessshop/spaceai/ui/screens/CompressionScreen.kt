package com.businessshop.spaceai.ui.screens

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.CompressionPreset
import com.businessshop.spaceai.core.CompressionValidation
import com.businessshop.spaceai.core.VideoCompressionPlanner
import com.businessshop.spaceai.ui.PrimaryAction
import com.businessshop.spaceai.ui.SectionCard
import com.businessshop.spaceai.ui.StatRow
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.bytes

@Composable
fun CompressionScreen(
    state: UiState,
    onPreset: (CompressionPreset) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onWatched: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDeleteOriginal: () -> Unit,
) {
    val c = state.compression ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(c.item.displayName, style = MaterialTheme.typography.headlineSmall)
        val info = c.info
        if (info == null) {
            Text(stringResource(R.string.reading_video)); LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column
        }
        val est = VideoCompressionPlanner.estimate(info, c.preset)
        SectionCard {
            StatRow(stringResource(R.string.original_size), bytes(info.sizeBytes))
            StatRow(stringResource(R.string.original_res), "${info.width}×${info.height}")
            StatRow(stringResource(R.string.result_res), "${est.targetWidth}×${est.targetHeight}")
            StatRow(stringResource(R.string.estimated_size), bytes(est.estimatedBytes))
            StatRow(stringResource(R.string.estimated_savings), "${bytes(est.estimatedSavingsBytes)} (${(est.savingsFraction * 100).toInt()} %)")
            if (!est.worthIt) Text(stringResource(R.string.not_worth_it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressionPreset.entries.forEach { p ->
                FilterChip(c.preset == p, { onPreset(p) }, {
                    Text(stringResource(when (p) {
                        CompressionPreset.HIGH -> R.string.preset_high
                        CompressionPreset.BALANCED -> R.string.preset_balanced
                        CompressionPreset.SMALL -> R.string.preset_small
                    }))
                }, enabled = c.progress == null)
            }
        }
        val progress = c.progress
        val job = c.job
        when {
            progress != null -> {
                Text(stringResource(R.string.compressing, progress))
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            job == null -> PrimaryAction(stringResource(R.string.compress), onStart, enabled = est.worthIt)
            job.validation != CompressionValidation.Valid -> {
                Text(stringResource(R.string.compression_invalid, (job.validation as CompressionValidation.Invalid).reason.name),
                    color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.original_untouched))
            }
            !c.savedCopy -> {
                SectionCard {
                    StatRow(stringResource(R.string.real_result_size), bytes(job.output.sizeBytes))
                    StatRow(stringResource(R.string.real_result_res), "${job.output.width}×${job.output.height}")
                    StatRow(stringResource(R.string.real_savings), bytes(info.sizeBytes - job.output.sizeBytes))
                }
                Text(stringResource(R.string.watch_before_keeping), style = MaterialTheme.typography.bodyMedium)
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setVideoPath(job.outputFile.absolutePath)
                            setOnPreparedListener { start() }
                            setOnInfoListener { _, what, _ ->
                                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onWatched(); false
                            }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(job.output.width.toFloat().coerceAtLeast(1f) / job.output.height.toFloat().coerceAtLeast(1f)),
                )
                PrimaryAction(stringResource(R.string.keep_copy), onSave, enabled = c.previewWatched)
                OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.discard_copy)) }
            }
            else -> {
                Text(stringResource(R.string.copy_saved), style = MaterialTheme.typography.bodyLarge)
                PrimaryAction(stringResource(R.string.review_delete_original), onDeleteOriginal)
            }
        }
    }
}
