package com.businessshop.spaceai.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.os.storage.StorageManager
import android.util.Size
import com.businessshop.spaceai.core.ContentSource
import com.businessshop.spaceai.core.GrayImage
import com.businessshop.spaceai.core.HashCache
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.PerceptualHash
import com.businessshop.spaceai.core.PhotoSignature
import com.businessshop.spaceai.core.QualityAnalyzer
import com.businessshop.spaceai.core.QualityMetrics
import com.businessshop.spaceai.core.StorageMeter
import com.businessshop.spaceai.core.StorageSnapshot
import java.io.File
import java.io.InputStream

/**
 * Mide el volumen interno con StorageStatsManager: son las mismas cifras que muestra
 * Ajustes > Almacenamiento. Si falla (equipos con ROM modificada), usa StatFs del directorio de datos.
 */
class AndroidStorageMeter(private val context: Context) : StorageMeter {
    override fun measure(): StorageSnapshot {
        val now = System.currentTimeMillis()
        return runCatching {
            val stats = context.getSystemService(StorageStatsManager::class.java)
            val total = stats.getTotalBytes(StorageManager.UUID_DEFAULT)
            val free = stats.getFreeBytes(StorageManager.UUID_DEFAULT)
            StorageSnapshot(total, free.coerceAtMost(total), now)
        }.getOrElse {
            val dir = context.filesDir
            StorageSnapshot(dir.totalSpace, dir.usableSpace.coerceAtMost(dir.totalSpace), now)
        }
    }
}

/** Abre contenido mediante ContentResolver (MediaStore y SAF usan el mismo mecanismo). */
class AndroidContentSource(private val context: Context) : ContentSource {
    override fun open(item: MediaItem): InputStream? =
        context.contentResolver.openInputStream(UriCodec.toUri(item.id))
}

/**
 * Calcula dHash y métricas de calidad sobre una miniatura de 512 px generada por el sistema
 * (no decodifica la foto completa: bajo consumo de memoria y batería). Resultados en caché.
 */
class ThumbnailAnalyzer(private val context: Context, private val cache: HashCache) {

    fun signature(item: MediaItem): PhotoSignature? {
        val cachedHash = cache.get(item, HashCache.Kind.DHASH)
        val cachedQuality = cache.get(item, HashCache.Kind.BLUR)?.let(QualityMetrics::decode)
        if (cachedHash != null && cachedQuality != null) {
            return runCatching { PhotoSignature(item, PerceptualHash.fromHex(cachedHash), cachedQuality) }.getOrNull()
        }
        val bmp = runCatching {
            context.contentResolver.loadThumbnail(UriCodec.toUri(item.id), Size(THUMB, THUMB), null)
        }.getOrNull() ?: return null // corrupta o no decodificable: se reporta como ilegible
        return try {
            val gray = toGray(bmp)
            val hash = PerceptualHash.dHash(gray)
            val quality = QualityAnalyzer.analyze(gray)
            cache.put(item, HashCache.Kind.DHASH, PerceptualHash.toHex(hash))
            cache.put(item, HashCache.Kind.BLUR, quality.encode())
            PhotoSignature(item, hash, quality)
        } finally {
            bmp.recycle()
        }
    }

    private fun toGray(src: Bitmap): GrayImage {
        val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (bmp !== src) bmp.recycle()
        return GrayImage.fromArgb(src.width, src.height, px)
    }

    companion object { const val THUMB = 512 }
}

/** Archivos temporales propios de SPACE AI (lo único de "caché" que una app puede borrar legalmente). */
class AppTempFiles(private val context: Context) {
    private val compressionDir get() = File(context.cacheDir, "compression")

    fun compressionOutput(name: String): File = File(compressionDir.apply { mkdirs() }, name)

    fun sizeBytes(): Long = context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Borra la caché propia; devuelve los bytes realmente borrados (medidos antes/después por archivo). */
    fun clear(exclude: Set<File> = emptySet()): Long {
        var freed = 0L
        context.cacheDir.walkBottomUp().forEach { f ->
            if (f == context.cacheDir || f in exclude) return@forEach
            if (f.isFile) {
                val len = f.length()
                if (f.delete()) freed += len
            } else {
                f.delete() // solo si quedó vacío
            }
        }
        return freed
    }
}
