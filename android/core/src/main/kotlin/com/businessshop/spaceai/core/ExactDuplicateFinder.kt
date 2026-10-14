package com.businessshop.spaceai.core

import java.io.InputStream
import java.security.MessageDigest

/** Abre el contenido de un elemento. La plataforma lo implementa (ContentResolver / PHAssetResource). */
fun interface ContentSource {
    /** Devuelve null si el contenido no se puede leer (corrupto, en la nube, sin permiso). */
    fun open(item: MediaItem): InputStream?
}

/** Señal de cancelación cooperativa, compatible con corrutinas y Swift Tasks. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val NONE = CancellationSignal { false }
    }
}

class ScanCancelledException : RuntimeException("Análisis cancelado por el usuario")

object Sha256 {
    private const val BUFFER = 64 * 1024

    /** SHA-256 en streaming; nunca carga el archivo completo en memoria. */
    fun of(input: InputStream, limitBytes: Long = Long.MAX_VALUE, cancel: CancellationSignal = CancellationSignal.NONE): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var remaining = limitBytes
        input.use { stream ->
            while (remaining > 0) {
                if (cancel.isCancelled()) throw ScanCancelledException()
                val toRead = if (remaining < BUFFER) remaining.toInt() else BUFFER
                val n = stream.read(buf, 0, toRead)
                if (n < 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest().toHex()
    }

    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

data class DuplicateGroup(
    val hash: String,
    val items: List<MediaItem>,
    /** Copia que se conserva por defecto; el usuario puede cambiarla. */
    val keeper: MediaItem,
) {
    val duplicates: List<MediaItem> get() = items.filter { it.id != keeper.id }
    val reclaimableBytes: Long get() = duplicates.sumOf { it.sizeBytes }
}

data class ExactDuplicateResult(
    val groups: List<DuplicateGroup>,
    /** Elementos que no se pudieron leer: se reportan, nunca se asumen duplicados. */
    val unreadable: List<MediaItem>,
    val bytesHashed: Long,
)

/**
 * Detección exacta en tres etapas para minimizar lecturas y batería:
 * 1) mismo tamaño en bytes, 2) SHA-256 de los primeros 64 KB, 3) SHA-256 completo.
 * Solo la etapa 3 confirma un duplicado. Un resultado de esta clase es "riesgo bajo".
 */
class ExactDuplicateFinder(
    private val source: ContentSource,
    private val cache: HashCache? = null,
    private val partialBytes: Long = 64 * 1024,
) {
    private fun safeOpen(item: MediaItem): InputStream? = runCatching { source.open(item) }.getOrNull()

    fun find(
        items: List<MediaItem>,
        cancel: CancellationSignal = CancellationSignal.NONE,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ExactDuplicateResult {
        val unreadable = mutableListOf<MediaItem>()
        var bytesHashed = 0L
        val bySize = items.filter { it.sizeBytes > 0 }.groupBy { it.sizeBytes }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val groups = mutableListOf<DuplicateGroup>()

        for (sameSize in bySize) {
            // Etapa 2: hash parcial (barato) para descartar rápido.
            val byPartial = LinkedHashMap<String, MutableList<MediaItem>>()
            for (item in sameSize) {
                if (cancel.isCancelled()) throw ScanCancelledException()
                val partial = cache?.get(item, HashCache.Kind.PARTIAL) ?: run {
                    val s = safeOpen(item)
                    if (s == null) { unreadable += item; null } else {
                        val h = runCatching { Sha256.of(s, partialBytes, cancel) }
                            .onFailure { if (it is ScanCancelledException) throw it }
                            .getOrNull()
                        if (h == null) unreadable += item else {
                            bytesHashed += minOf(partialBytes, item.sizeBytes)
                            cache?.put(item, HashCache.Kind.PARTIAL, h)
                        }
                        h
                    }
                }
                if (partial != null) byPartial.getOrPut(partial) { mutableListOf() } += item
                progress(++done, total)
            }
            // Etapa 3: hash completo solo donde hay colisión parcial.
            for ((partialHash, candidates) in byPartial) {
                if (candidates.size < 2) continue
                // Si el archivo cabe completo en el bloque parcial, el hash parcial ya es el completo.
                if (candidates.first().sizeBytes <= partialBytes) {
                    groups += DuplicateGroup(partialHash, candidates, KeeperPolicy.chooseExactKeeper(candidates))
                    continue
                }
                val byFull = LinkedHashMap<String, MutableList<MediaItem>>()
                for (item in candidates) {
                    if (cancel.isCancelled()) throw ScanCancelledException()
                    val full = run {
                        cache?.get(item, HashCache.Kind.FULL) ?: safeOpen(item)?.let { s ->
                            runCatching { Sha256.of(s, Long.MAX_VALUE, cancel) }
                                .onFailure { if (it is ScanCancelledException) throw it }
                                .getOrNull()
                                ?.also { bytesHashed += item.sizeBytes; cache?.put(item, HashCache.Kind.FULL, it) }
                        }
                    }
                    if (full == null) unreadable += item else byFull.getOrPut(full) { mutableListOf() } += item
                }
                for ((hash, group) in byFull) {
                    if (group.size > 1) groups += DuplicateGroup(hash, group, KeeperPolicy.chooseExactKeeper(group))
                }
            }
        }
        return ExactDuplicateResult(
            groups.sortedByDescending { it.reclaimableBytes },
            unreadable.distinctBy { it.id },
            bytesHashed,
        )
    }
}

object KeeperPolicy {
    /** Entre copias idénticas conserva: favorita > más antigua > nombre más corto (suele ser el original). */
    fun chooseExactKeeper(group: List<MediaItem>): MediaItem =
        group.sortedWith(
            compareByDescending<MediaItem> { it.isFavorite }
                .thenBy { if (it.dateTakenMillis > 0) it.dateTakenMillis else Long.MAX_VALUE }
                .thenBy { it.displayName.length }
                .thenBy { it.id }
        ).first()
}
