package com.businessshop.spaceai.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.MediaKind
import com.businessshop.spaceai.core.ScanCancelledException

/**
 * Explora carpetas que el usuario autorizó con el selector del sistema (Storage Access Framework).
 * Sustituye a MANAGE_EXTERNAL_STORAGE, que Google Play no permite para limpiadores.
 * Limitación de Android 11+: el selector no permite elegir la raíz de "Download" ni la raíz del
 * almacenamiento; sí subcarpetas y otras carpetas (Documents, WhatsApp/Media exportado por el usuario, etc.).
 */
class DocumentTreeScanner(private val context: Context) {

    fun persist(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun release(treeUri: Uri) = runCatching {
        context.contentResolver.releasePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun grantedTrees(): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    fun scan(tree: Uri, cancel: CancellationSignal, maxDepth: Int = 12): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, Int>>().apply { add(rootDoc to 0) }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        while (stack.isNotEmpty()) {
            if (cancel.isCancelled()) throw ScanCancelledException()
            val (docId, depth) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            runCatching {
                context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < maxDepth) stack.add(id to depth + 1)
                            continue
                        }
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        if (size <= 0) continue
                        val flags = c.getInt(5)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += MediaItem(
                            id = UriCodec.encode(MediaKind.DOCUMENT, uri),
                            displayName = c.getString(1) ?: id,
                            sizeBytes = size,
                            kind = if (mime.startsWith("image/") || mime.startsWith("video/")) MediaKind.OTHER else MediaKind.DOCUMENT,
                            mimeType = mime,
                            dateModifiedMillis = if (c.isNull(4)) 0L else c.getLong(4),
                            location = docId,
                            deletable = (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0,
                        )
                    }
                }
            } // una subcarpeta ilegible no detiene el análisis
        }
        return out
    }

    fun delete(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)

    fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}
