package com.businessshop.spaceai.ui

import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.businessshop.spaceai.R
import com.businessshop.spaceai.core.ByteFormat
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.RiskLevel
import com.businessshop.spaceai.core.StorageSnapshot
import com.businessshop.spaceai.data.UriCodec
import com.businessshop.spaceai.ui.theme.BrandSoft
import com.businessshop.spaceai.ui.theme.RiskHigh
import com.businessshop.spaceai.ui.theme.RiskLow
import com.businessshop.spaceai.ui.theme.RiskMedium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

fun bytes(b: Long): String = ByteFormat.format(b, Locale.getDefault())

/** Anillo animado que representa la ocupación REAL medida del volumen. */
@Composable
fun StorageRing(snapshot: StorageSnapshot?, modifier: Modifier = Modifier, diameter: Dp = 230.dp) {
    val target = snapshot?.usedFraction?.toFloat() ?: 0f
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val desc = snapshot?.let { stringResource(R.string.a11y_ring, bytes(it.usedBytes), bytes(it.totalBytes), bytes(it.freeBytes)) }
        ?: stringResource(R.string.measuring)
    Box(modifier.size(diameter).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(BrandSoft, primary, BrandSoft)), -90f, 360f * anim.value, false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (snapshot != null) {
                Text(bytes(snapshot.freeBytes), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.free_of, bytes(snapshot.totalBytes)), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.measuring), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun RiskBadge(risk: RiskLevel) {
    val (color, label) = when (risk) {
        RiskLevel.LOW -> RiskLow to R.string.risk_low
        RiskLevel.MEDIUM -> RiskMedium to R.string.risk_medium
        RiskLevel.HIGH -> RiskHigh to R.string.risk_high
    }
    Text(
        stringResource(label), color = color, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp),
    ) { Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Miniatura generada por el sistema, cargada fuera del hilo principal. */
@Composable
fun Thumb(item: MediaItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.id) {
        bmp = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(UriCodec.toUri(item.id), Size(300, 300), null) }.getOrNull()
        }
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = item.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@StringRes fun categoryTitle(c: CleanupCategory): Int = when (c) {
    CleanupCategory.EXACT_DUPLICATE_PHOTOS -> R.string.cat_exact_photos
    CleanupCategory.SIMILAR_PHOTOS -> R.string.cat_similar
    CleanupCategory.BURST_PHOTOS -> R.string.cat_burst
    CleanupCategory.SCREENSHOTS -> R.string.cat_screenshots
    CleanupCategory.BLURRY_PHOTOS -> R.string.cat_blurry
    CleanupCategory.DARK_PHOTOS -> R.string.cat_dark
    CleanupCategory.LOW_QUALITY_PHOTOS -> R.string.cat_low_quality
    CleanupCategory.OLD_LARGE_PHOTOS -> R.string.cat_old_large_photos
    CleanupCategory.LARGE_VIDEOS -> R.string.cat_large_videos
    CleanupCategory.DUPLICATE_VIDEOS -> R.string.cat_duplicate_videos
    CleanupCategory.SCREEN_RECORDINGS -> R.string.cat_screen_recordings
    CleanupCategory.OLD_VIDEOS -> R.string.cat_old_videos
    CleanupCategory.COMPRESSIBLE_VIDEOS -> R.string.cat_compressible
    CleanupCategory.LARGE_FILES -> R.string.cat_large_files
    CleanupCategory.DUPLICATE_FILES -> R.string.cat_duplicate_files
    CleanupCategory.APP_TEMP_FILES -> R.string.cat_app_temp
}

/** Texto de recomendación ("Detectamos 2.4 GB de videos duplicados que podrías revisar"). */
@StringRes fun recommendationText(c: CleanupCategory): Int = when (c) {
    CleanupCategory.COMPRESSIBLE_VIDEOS -> R.string.rec_compress
    CleanupCategory.APP_TEMP_FILES -> R.string.rec_temp
    else -> R.string.rec_generic
}
