package com.businessshop.spaceai.core

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object Fixtures {
    fun photo(
        id: String,
        size: Long = 3_000_000,
        taken: Long = 1_700_000_000_000,
        w: Int = 4032, h: Int = 3024,
        favorite: Boolean = false,
        screenshot: Boolean = false,
        burst: String? = null,
    ) = MediaItem(id, "IMG_$id.jpg", size, MediaKind.PHOTO, "image/jpeg", taken, taken, w, h,
        isScreenshot = screenshot, isFavorite = favorite, burstId = burst)

    fun video(id: String, size: Long, taken: Long = 1_700_000_000_000, recording: Boolean = false) =
        MediaItem(id, "VID_$id.mp4", size, MediaKind.VIDEO, "video/mp4", taken, taken, 1920, 1080, 60_000, isScreenRecording = recording)

    fun file(id: String, size: Long) = MediaItem(id, "doc_$id.pdf", size, MediaKind.DOCUMENT, "application/pdf")

    /** Fuente en memoria que cuenta aperturas para verificar la caché y el filtrado por tamaño. */
    class MemorySource(private val data: Map<String, ByteArray?>) : ContentSource {
        val opens = AtomicInteger()
        override fun open(item: MediaItem) = data[item.id]?.let { opens.incrementAndGet(); ByteArrayInputStream(it) }
    }

    /** Imagen sintética con estructura (escena) determinista por semilla. */
    fun scene(seed: Int, w: Int = 320, h: Int = 240): GrayImage {
        val r = Random(seed)
        val blobs = List(12) { Triple(r.nextInt(w), r.nextInt(h), r.nextInt(20, 90)) to r.nextInt(30, 230) }
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 40f + 140f * x / w
            for ((b, c) in blobs) {
                val dx = x - b.first; val dy = y - b.second
                if (dx * dx + dy * dy < b.third * b.third) v = c.toFloat()
            }
            luma[y * w + x] = v
        }
        return GrayImage(w, h, luma)
    }

    fun map(img: GrayImage, f: (Float) -> Float) = GrayImage(img.width, img.height, FloatArray(img.luma.size) { f(img.luma[it]).coerceIn(0f, 255f) })

    fun boxBlur(img: GrayImage, radius: Int): GrayImage {
        val w = img.width; val h = img.height
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += img[xx, yy]; n++ }
            }
            out[y * w + x] = s / n
        }
        return GrayImage(w, h, out)
    }

    class FakeMeter(vararg values: Long, val total: Long = 128_000_000_000) : StorageMeter {
        private val queue = ArrayDeque(values.toList())
        override fun measure() = StorageSnapshot(total, queue.removeFirst(), 0)
    }
}
