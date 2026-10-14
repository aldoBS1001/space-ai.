package com.businessshop.spaceai.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.businessshop.spaceai.core.CompressedOutput
import com.businessshop.spaceai.core.CompressionEstimate
import com.businessshop.spaceai.core.CompressionPreset
import com.businessshop.spaceai.core.CompressionValidation
import com.businessshop.spaceai.core.CompressionValidator
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.VideoCompressionPlanner
import com.businessshop.spaceai.core.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class InsufficientSpaceException(val requiredBytes: Long, val freeBytes: Long) : Exception("Espacio insuficiente para comprimir")

data class CompressionJobResult(
    val outputFile: File,
    val output: CompressedOutput,
    val validation: CompressionValidation,
    val estimate: CompressionEstimate,
)

/**
 * Compresión real con Media3 Transformer (codificadores de hardware H.264 + AAC).
 * Flujo seguro: comprimir a caché propia -> validar (decodificable, duración, tamaño) ->
 * el usuario reproduce la copia -> guardar en la galería -> solo entonces se ofrece borrar el original.
 */
@OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context, private val temp: AppTempFiles, private val meter: AndroidStorageMeter) {

    fun readInfo(uri: Uri, sizeBytes: Long): VideoInfo? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val fps = if (frames != null && duration > 0) frames * 1000.0 / duration else 30.0
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            VideoInfo(sizeBytes, duration, w, h, fps, audio)
        }
    }.getOrNull()

    suspend fun compress(
        item: MediaItem,
        info: VideoInfo,
        preset: CompressionPreset,
        onProgress: (Int) -> Unit,
    ): CompressionJobResult {
        val estimate = VideoCompressionPlanner.estimate(info, preset)
        val required = CompressionValidator.requiredFreeBytes(estimate)
        val free = meter.measure().freeBytes
        if (free < required) throw InsufficientSpaceException(required, free)

        val out = temp.compressionOutput("spaceai_${System.currentTimeMillis()}.mp4")
        try {
            runTransformer(UriCodec.toUri(item.id), out, estimate, onProgress)
        } catch (t: Throwable) {
            out.delete() // nunca dejar copias a medias
            throw t
        }
        val output = inspect(out)
        return CompressionJobResult(out, output, CompressionValidator.validate(info, output), estimate)
    }

    private suspend fun runTransformer(src: Uri, out: File, estimate: CompressionEstimate, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.Main) {
            coroutineScope {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(estimate.targetVideoBitrate).build())
                    .build()
                lateinit var transformer: Transformer
                val poller = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        delay(250)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoderFactory)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            })
                            .build()
                        val effects = Effects(
                            emptyList(),
                            listOf(Presentation.createForWidthAndHeight(estimate.targetWidth, estimate.targetHeight, Presentation.LAYOUT_SCALE_TO_FIT)),
                        )
                        val edited = EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(src)).setEffects(effects).build()
                        transformer.start(edited, out.absolutePath)
                        cont.invokeOnCancellation { transformer.cancel() }
                    }
                } finally {
                    poller.cancel()
                }
                onProgress(100)
            }
        }

    /** Medición real de la copia: tamaño en disco, duración, dimensiones y decodificación del primer y último cuadro. */
    fun inspect(file: File): CompressedOutput = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val first = r.getFrameAtTime(0)
            val last = r.getFrameAtTime(maxOf(0L, d - 500) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val decodable = first != null && last != null
            first?.recycle(); last?.recycle()
            CompressedOutput(file.length(), d, w, h, decodable)
        }
    }.getOrElse { CompressedOutput(file.length(), 0, 0, 0, false) }

    /** Publica la copia validada en la galería (Movies/SPACE AI) conservando la fecha original. */
    fun saveToGallery(file: File, original: MediaItem): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, original.displayName.substringBeforeLast('.') + "_spaceai.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SPACE AI")
            if (original.dateTakenMillis > 0) put(MediaStore.Video.Media.DATE_TAKEN, original.dateTakenMillis)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("No se pudo crear el video en la galería")
        try {
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
                ?: error("No se pudo escribir el video")
            // Verifica que lo escrito coincide byte a byte en tamaño antes de publicarlo.
            val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            check(written == file.length()) { "Tamaño escrito no coincide" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        file.delete()
        return uri
    }
}
