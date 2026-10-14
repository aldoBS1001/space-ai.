package com.businessshop.spaceai.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.CleanupExecutor
import com.businessshop.spaceai.core.ItemOutcome
import com.businessshop.spaceai.core.ItemResult
import com.businessshop.spaceai.core.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * Puente hacia la Activity: Android exige que el usuario confirme en un diálogo del sistema
 * la eliminación de fotos/videos que no creó la app. El motor espera la respuesta aquí.
 */
object SystemConsentBridge {
    class Request(val sender: IntentSender, val result: CompletableDeferred<Boolean> = CompletableDeferred())

    val requests = Channel<Request>(Channel.UNLIMITED)

    suspend fun ask(sender: IntentSender): Boolean {
        val r = Request(sender)
        requests.send(r)
        return r.result.await()
    }
}

enum class DeleteMode {
    /** Papelera del sistema (Android 11+): recuperable ~30 días; el espacio se libera al vaciarla. */
    TRASH,
    /** Eliminación definitiva: libera el espacio inmediatamente. */
    PERMANENT,
}

/**
 * Ejecuta eliminaciones con APIs oficiales y VERIFICA cada resultado consultando de nuevo
 * MediaStore/SAF. Nunca reporta éxito por el simple hecho de que la llamada no falló.
 * Debe invocarse fuera del hilo principal.
 */
class AndroidCleanupExecutor(
    private val context: Context,
    private val mode: DeleteMode,
    private val media: MediaStoreScanner = MediaStoreScanner(context),
    private val docs: DocumentTreeScanner = DocumentTreeScanner(context),
) : CleanupExecutor {

    override fun execute(items: List<MediaItem>, cancel: CancellationSignal): List<ItemResult> {
        val (mediaItems, docItems) = items.partition { UriCodec.isMedia(it.id) }
        val results = ArrayList<ItemResult>(items.size)
        if (mediaItems.isNotEmpty()) results += deleteMedia(mediaItems, cancel)
        for (d in docItems) {
            if (cancel.isCancelled()) break
            val uri = UriCodec.toUri(d.id)
            val called = docs.delete(uri)
            results += when {
                !docs.exists(uri) -> ItemResult(d, ItemOutcome.DELETED)
                called -> ItemResult(d, ItemOutcome.FAILED, "El proveedor reportó éxito pero el archivo sigue existiendo")
                else -> ItemResult(d, ItemOutcome.FAILED, "El proveedor de documentos rechazó la eliminación")
            }
        }
        return results
    }

    private fun deleteMedia(items: List<MediaItem>, cancel: CancellationSignal): List<ItemResult> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val out = ArrayList<ItemResult>()
            for (batch in items.chunked(BATCH)) {
                if (cancel.isCancelled()) break
                val uris = batch.map { UriCodec.toUri(it.id) }
                val pi = if (mode == DeleteMode.TRASH) MediaStore.createTrashRequest(context.contentResolver, uris, true)
                         else MediaStore.createDeleteRequest(context.contentResolver, uris)
                val accepted = runBlocking { SystemConsentBridge.ask(pi.intentSender) }
                out += batch.map { verify(it, accepted) }
            }
            return out
        }
        // Android 10: el sistema pide confirmación por elemento mediante RecoverableSecurityException.
        val out = ArrayList<ItemResult>()
        for (item in items) {
            if (cancel.isCancelled()) break
            val uri = UriCodec.toUri(item.id)
            val accepted = try {
                context.contentResolver.delete(uri, null, null); true
            } catch (e: RecoverableSecurityException) {
                val ok = runBlocking { SystemConsentBridge.ask(e.userAction.actionIntent.intentSender) }
                if (ok) runCatching { context.contentResolver.delete(uri, null, null) }.isSuccess else false
            } catch (e: SecurityException) {
                out += ItemResult(item, ItemOutcome.FAILED, e.message); continue
            }
            out += verify(item, accepted)
        }
        return out
    }

    private fun verify(item: MediaItem, userAccepted: Boolean): ItemResult {
        val uri = UriCodec.toUri(item.id)
        return when {
            media.isTrashed(uri) -> ItemResult(item, ItemOutcome.MOVED_TO_TRASH)
            !media.exists(uri) -> ItemResult(item, if (userAccepted) ItemOutcome.DELETED else ItemOutcome.NOT_FOUND)
            !userAccepted -> ItemResult(item, ItemOutcome.USER_DENIED)
            else -> ItemResult(item, ItemOutcome.FAILED, "Sigue existiendo tras la confirmación")
        }
    }

    companion object { private const val BATCH = 300 }
}
