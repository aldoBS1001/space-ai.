package com.businessshop.spaceai.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.MediaKind
import com.businessshop.spaceai.core.ScanCancelledException

/**
 * Lee fotos y videos accesibles mediante MediaStore. Con acceso parcial (Android 14+)
 * MediaStore devuelve solo lo que el usuario seleccionó: el escáner no intenta saltarse eso.
 * Los ids se prefijan ("img:", "vid:") porque MediaStore usa tablas distintas.
 */
class MediaStoreScanner(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun uriOf(item: MediaItem): Uri = UriCodec.toUri(item.id)

    fun scanPhotos(cancel: CancellationSignal): List<MediaItem> =
        query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.PHOTO, cancel)

    fun scanVideos(cancel: CancellationSignal): List<MediaItem> =
        query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.VIDEO, cancel)

    private fun query(collection: Uri, kind: MediaKind, cancel: CancellationSignal): List<MediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.IS_PENDING)
            add(MediaStore.MediaColumns.ORIENTATION)
            if (kind == MediaKind.VIDEO) add(MediaStore.MediaColumns.DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

        val out = ArrayList<MediaItem>()
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
            val idx = Columns(c)
            while (c.moveToNext()) {
                if (out.size % 200 == 0 && cancel.isCancelled()) throw ScanCancelledException()
                if (idx.pending >= 0 && c.getInt(idx.pending) == 1) continue // aún se está escribiendo
                val id = c.getLong(idx.id)
                // MediaStore actualiza SIZE de forma asíncrona tras escribir un archivo: si aún es 0,
                // se mide el archivo real en lugar de omitirlo o inventar un tamaño.
                val size = c.long0(idx.size).takeIf { it > 0 }
                    ?: realSize(ContentUris.withAppendedId(collection, id)) ?: continue
                val path = (c.getStringOrNull(idx.relativePath) ?: "") + "/" + (c.getStringOrNull(idx.bucket) ?: "")
                val modifiedMs = c.long0(idx.modified) * 1000L
                val takenMs = c.long0(idx.taken).takeIf { it > 0 } ?: (c.long0(idx.added) * 1000L)
                val uri = ContentUris.withAppendedId(collection, id)
                val rotated = c.long0(idx.orientation).toInt().let { it == 90 || it == 270 }
                out += MediaItem(
                    id = UriCodec.encode(kind, uri),
                    displayName = c.getStringOrNull(idx.name) ?: id.toString(),
                    sizeBytes = size,
                    kind = kind,
                    mimeType = c.getStringOrNull(idx.mime),
                    dateTakenMillis = takenMs,
                    dateModifiedMillis = modifiedMs,
                    width = if (rotated) c.long0(idx.height).toInt() else c.long0(idx.width).toInt(),
                    height = if (rotated) c.long0(idx.width).toInt() else c.long0(idx.height).toInt(),
                    durationMillis = c.long0(idx.duration),
                    isScreenshot = kind == MediaKind.PHOTO && SCREENSHOT.containsMatchIn(path),
                    isScreenRecording = kind == MediaKind.VIDEO && SCREEN_RECORDING.containsMatchIn(path),
                    isFavorite = idx.favorite >= 0 && c.getInt(idx.favorite) == 1,
                )
            }
        }
        return out
    }

    private fun realSize(uri: Uri): Long? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0 }

    /** true si el elemento sigue visible (no borrado ni en papelera). */
    fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** true si el elemento está en la papelera del sistema (Android 11+). */
    fun isTrashed(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } ?: false
        }.getOrDefault(false)
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val added = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val relativePath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val pending = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
        val duration = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
        val favorite = c.getColumnIndex("is_favorite")
        val orientation = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)
    private fun Cursor.long0(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)

    companion object {
        private val SCREENSHOT = Regex("screenshot|captura|pantallazo", RegexOption.IGNORE_CASE)
        private val SCREEN_RECORDING = Regex("screen ?record|screenrecord|grabaci[oó]n de pantalla", RegexOption.IGNORE_CASE)
    }
}

/** Codifica tipo + URI en el id del motor y lo recupera. */
object UriCodec {
    fun encode(kind: MediaKind, uri: Uri): String = when (kind) {
        MediaKind.PHOTO -> "img:"
        MediaKind.VIDEO -> "vid:"
        MediaKind.DOCUMENT, MediaKind.OTHER -> "doc:"
    } + uri.toString()

    fun toUri(id: String): Uri = Uri.parse(id.substringAfter(':'))
    fun isMedia(id: String) = id.startsWith("img:") || id.startsWith("vid:")
}
