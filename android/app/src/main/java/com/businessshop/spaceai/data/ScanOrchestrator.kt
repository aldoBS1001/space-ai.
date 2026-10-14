package com.businessshop.spaceai.data

import android.content.Context
import android.content.SharedPreferences
import com.businessshop.spaceai.core.AccessReport
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.CompressionPreset
import com.businessshop.spaceai.core.ExactDuplicateFinder
import com.businessshop.spaceai.core.ExactDuplicateResult
import com.businessshop.spaceai.core.HashCache
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.MediaKind
import com.businessshop.spaceai.core.PhotoSignature
import com.businessshop.spaceai.core.QualityThresholds
import com.businessshop.spaceai.core.ScanAnalyzer
import com.businessshop.spaceai.core.ScanResult
import com.businessshop.spaceai.core.ScanRules
import com.businessshop.spaceai.core.Sensitivity
import com.businessshop.spaceai.core.SimilarityGrouper
import com.businessshop.spaceai.core.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class ScanPhase { STORAGE, LISTING, PHOTOS, DUPLICATES, VIDEOS, FILES, SUMMARY }

/** Progreso real: fase actual + elementos procesados / total de esa fase. */
data class ScanProgress(val phase: ScanPhase, val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(prefs.getString("sens", null)!!) }.getOrDefault(Sensitivity.NORMAL)
        set(v) = prefs.edit().putString("sens", v.name).apply()
    var deleteMode: DeleteMode
        get() = runCatching { DeleteMode.valueOf(prefs.getString("del", null)!!) }.getOrDefault(DeleteMode.TRASH)
        set(v) = prefs.edit().putString("del", v.name).apply()
    /** Android 10 no tiene papelera del sistema: ahí toda eliminación es definitiva. */
    val effectiveDeleteMode: DeleteMode
        get() = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) DeleteMode.PERMANENT else deleteMode
    var preset: CompressionPreset
        get() = runCatching { CompressionPreset.valueOf(prefs.getString("preset", null)!!) }.getOrDefault(CompressionPreset.BALANCED)
        set(v) = prefs.edit().putString("preset", v.name).apply()
    var conservativeQuality: Boolean
        get() = prefs.getBoolean("conservative", false)
        set(v) = prefs.edit().putBoolean("conservative", v).apply()
}

/**
 * Orquesta el análisis completo en el dispositivo. Todo es local: no hay red en el manifiesto.
 * Cancelable en cualquier punto; los hashes calculados quedan en caché para reanudar.
 */
class ScanOrchestrator(private val context: Context, private val settings: AppSettings) {
    val cache = HashCache(File(context.noBackupFilesDir, "hashcache.tsv"))
    private val media = MediaStoreScanner(context)
    private val docs = DocumentTreeScanner(context)
    private val meter = AndroidStorageMeter(context)
    private val thumbs = ThumbnailAnalyzer(context, cache)
    private val temp = AppTempFiles(context)
    private val compressor = VideoCompressor(context, temp, meter)

    suspend fun scan(access: AccessReport, onProgress: (ScanProgress) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        val cancel = CancellationSignal { !ctx.isActive }
        try {
            onProgress(ScanProgress(ScanPhase.STORAGE, 0, 1))
            val storage = meter.measure()

            onProgress(ScanProgress(ScanPhase.LISTING, 0, 1))
            val photos = if (access.photos.canRead()) media.scanPhotos(cancel) else emptyList()
            val videos = if (access.videos.canRead()) media.scanVideos(cancel) else emptyList()
            val files = docs.grantedTrees().flatMap { tree -> runCatching { docs.scan(tree, cancel) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }
            ctx.ensureActive()

            // Firmas visuales en paralelo limitado (gama media: 3 hilos).
            val done = AtomicInteger()
            val pool = Dispatchers.Default.limitedParallelism(3)
            val signatures: List<PhotoSignature> = coroutineScope {
                photos.chunked(64).map { chunk ->
                    async(pool) {
                        chunk.mapNotNull { p ->
                            ensureActive()
                            thumbs.signature(p).also { onProgress(ScanProgress(ScanPhase.PHOTOS, done.incrementAndGet(), photos.size)) }
                        }
                    }
                }.awaitAll().flatten()
            }
            val unreadablePhotos = photos.size - signatures.size

            // Duplicados exactos: medios y documentos por separado. Un mismo archivo físico puede
            // aparecer en MediaStore y en una carpeta SAF; compararlos entre sí lo marcaría como
            // "duplicado" y borrar uno borraría el único original.
            val finder = ExactDuplicateFinder(AndroidContentSource(context), cache)
            val mediaAll = photos + videos
            val exactMedia = finder.find(mediaAll, cancel) { d, t -> onProgress(ScanProgress(ScanPhase.DUPLICATES, d, t)) }
            val docOnly = files.filter { it.kind == MediaKind.DOCUMENT }
            val exactDocs = finder.find(docOnly, cancel)
            val exact = ExactDuplicateResult(exactMedia.groups + exactDocs.groups, exactMedia.unreadable + exactDocs.unreadable,
                exactMedia.bytesHashed + exactDocs.bytesHashed)
            cache.flush()

            val similar = SimilarityGrouper(settings.sensitivity).group(signatures, cancel)

            val candidatesForCompression = videos.filter { it.sizeBytes >= 30L * 1024 * 1024 }
            val infos = HashMap<String, VideoInfo>()
            candidatesForCompression.forEachIndexed { i, v ->
                ctx.ensureActive()
                compressor.readInfo(UriCodec.toUri(v.id), v.sizeBytes)?.let { infos[v.id] = it }
                onProgress(ScanProgress(ScanPhase.VIDEOS, i + 1, candidatesForCompression.size))
            }

            onProgress(ScanProgress(ScanPhase.SUMMARY, 0, 1))
            val rules = ScanRules(
                qualityThresholds = if (settings.conservativeQuality) QualityThresholds.CONSERVATIVE else QualityThresholds.DEFAULT,
                compressionPreset = settings.preset,
            )
            val result = ScanAnalyzer(rules).analyze(
                storage, access, photos + videos + files.filter { it.kind == MediaKind.DOCUMENT }, exact, similar,
                signatures.mapNotNull { s -> s.quality?.let { s.item.id to it } }.toMap(),
                infos, temp.sizeBytes(),
            )
            if (unreadablePhotos > 0) android.util.Log.i("SpaceAI", "Fotos sin miniatura decodificable: $unreadablePhotos")
            result
        } finally {
            runCatching { cache.flush() } // protege el avance ante cancelación o cierre
        }
    }

    fun measure() = meter.measure()
    fun tempFiles() = temp
    fun compressor() = compressor
    fun documents() = docs

    private fun com.businessshop.spaceai.core.AccessState.canRead() =
        this == com.businessshop.spaceai.core.AccessState.FULL || this == com.businessshop.spaceai.core.AccessState.LIMITED

    companion object {
        fun itemsById(result: ScanResult): Map<String, MediaItem> =
            (result.categories.flatMap { it.items } + result.exactDuplicates.flatMap { it.items } +
                result.similarGroups.flatMap { g -> g.items.map { it.item } }).associateBy { it.id }
    }
}
