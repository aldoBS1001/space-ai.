package com.businessshop.spaceai.core

import java.io.File

/**
 * Caché incremental de hashes y métricas. Permite:
 * - reanudar un análisis interrumpido (cierre inesperado, cancelación),
 * - no recalcular elementos sin cambios en el siguiente análisis.
 * La clave incluye tamaño y fecha de modificación: si el archivo cambia, se invalida.
 * Solo guarda identificadores y hashes, nunca contenido ni miniaturas.
 */
class HashCache(private val file: File? = null) {
    enum class Kind { PARTIAL, FULL, DHASH, BLUR, LUMA }

    private val map = HashMap<String, String>()
    private var dirty = 0

    init { load() }

    private fun key(item: MediaItem, kind: Kind) = "${kind.name}|${item.id}|${item.sizeBytes}|${item.dateModifiedMillis}"

    @Synchronized fun get(item: MediaItem, kind: Kind): String? = map[key(item, kind)]

    @Synchronized fun put(item: MediaItem, kind: Kind, value: String) {
        require('\n' !in value && '\t' !in value) { "Valor de caché inválido" }
        map[key(item, kind)] = value
        if (++dirty >= FLUSH_EVERY) flush()
    }

    @Synchronized fun size(): Int = map.size

    /** Escritura atómica: archivo temporal + rename, para no corromper la caché si la app muere. */
    @Synchronized fun flush() {
        val target = file ?: return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            for ((k, v) in map) { w.write(k); w.write("\t"); w.write(v); w.newLine() }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "No se pudo guardar la caché" }
        }
        dirty = 0
    }

    @Synchronized fun clear() {
        map.clear(); dirty = 0
        file?.delete()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            f.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext() || it.next() != HEADER) return@useLines // versión distinta: se ignora
                while (it.hasNext()) {
                    val line = it.next()
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
        }.onFailure { map.clear() } // caché corrupta: se reconstruye, nunca bloquea el análisis
    }

    companion object {
        const val HEADER = "SPACEAI-HASHCACHE-V1"
        private const val FLUSH_EVERY = 200
    }
}
