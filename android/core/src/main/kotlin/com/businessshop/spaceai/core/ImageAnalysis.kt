package com.businessshop.spaceai.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Imagen en escala de grises (luma 0..255) en memoria compacta.
 * La plataforma decodifica una miniatura (Android: loadThumbnail; iOS: PHImageManager)
 * y entrega los píxeles ARGB; el motor nunca necesita la foto a resolución completa.
 */
class GrayImage(val width: Int, val height: Int, val luma: FloatArray) {
    init {
        require(width > 0 && height > 0) { "Dimensiones inválidas" }
        require(luma.size == width * height) { "Tamaño de buffer inválido" }
    }

    operator fun get(x: Int, y: Int): Float = luma[y * width + x]

    /** Reducción por promedio de área: estable y sin aliasing, apta para hashes perceptuales. */
    fun resize(newW: Int, newH: Int): GrayImage {
        require(newW > 0 && newH > 0)
        val out = FloatArray(newW * newH)
        val sx = width.toDouble() / newW
        val sy = height.toDouble() / newH
        for (oy in 0 until newH) {
            val y0 = oy * sy; val y1 = (oy + 1) * sy
            for (ox in 0 until newW) {
                val x0 = ox * sx; val x1 = (ox + 1) * sx
                var sum = 0.0; var area = 0.0
                var y = y0.toInt()
                while (y < y1 && y < height) {
                    val wy = min(y + 1.0, y1) - max(y.toDouble(), y0)
                    var x = x0.toInt()
                    while (x < x1 && x < width) {
                        val wx = min(x + 1.0, x1) - max(x.toDouble(), x0)
                        val w = wx * wy
                        sum += this[x, y] * w; area += w
                        x++
                    }
                    y++
                }
                out[oy * newW + ox] = if (area > 0) (sum / area).toFloat() else 0f
            }
        }
        return GrayImage(newW, newH, out)
    }

    /** Escala para que el lado mayor sea [maxSide] (nunca amplía). */
    fun normalized(maxSide: Int): GrayImage {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val f = maxSide.toDouble() / longest
        return resize(max(1, (width * f).roundToInt()), max(1, (height * f).roundToInt()))
    }

    companion object {
        /** Luma Rec. 601 desde píxeles ARGB empaquetados (formato de Android Bitmap.getPixels). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            require(argb.size == width * height)
            val l = FloatArray(argb.size)
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
                l[i] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
            return GrayImage(width, height, l)
        }

        /** Luma desde RGBA de 8 bits (formato de CGContext en iOS). */
        fun fromRgba(width: Int, height: Int, rgba: ByteArray): GrayImage {
            require(rgba.size == width * height * 4)
            val l = FloatArray(width * height)
            for (i in l.indices) {
                val r = rgba[i * 4].toInt() and 0xFF
                val g = rgba[i * 4 + 1].toInt() and 0xFF
                val b = rgba[i * 4 + 2].toInt() and 0xFF
                l[i] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
            return GrayImage(width, height, l)
        }
    }
}

object PerceptualHash {
    /**
     * dHash de 64 bits: compara gradientes horizontales en una rejilla 9x8.
     * Robusto a recompresión JPEG, cambios de tamaño y ajustes leves de brillo.
     */
    fun dHash(img: GrayImage): Long {
        val s = img.resize(9, 8)
        var hash = 0L
        var bit = 63
        for (y in 0 until 8) for (x in 0 until 8) {
            if (s[x, y] < s[x + 1, y]) hash = hash or (1L shl bit)
            bit--
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun toHex(h: Long): String = java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    fun fromHex(s: String): Long = java.lang.Long.parseUnsignedLong(s, 16)
}

data class QualityMetrics(
    /** Varianza del Laplaciano a 512 px: baja = borrosa. */
    val sharpness: Double,
    /** Luma media 0..255. */
    val meanLuma: Double,
    /** Fracción de píxeles con luma < 40. */
    val darkFraction: Double,
    /** Fracción de píxeles casi blancos (> 245): detecta sobreexposición. */
    val brightFraction: Double,
) {
    fun isBlurry(t: QualityThresholds = QualityThresholds.DEFAULT) = sharpness < t.blurVariance
    fun isDark(t: QualityThresholds = QualityThresholds.DEFAULT) = meanLuma < t.darkMeanLuma && darkFraction > t.darkFraction
    fun isOverexposed(t: QualityThresholds = QualityThresholds.DEFAULT) = brightFraction > t.overexposedFraction

    /** Puntuación 0..1 para elegir la mejor foto de un grupo. */
    fun score(): Double {
        val sharp = (sharpness / 400.0).coerceIn(0.0, 1.0)
        val exposure = 1.0 - (kotlin.math.abs(meanLuma - 128.0) / 128.0)
        val clipping = 1.0 - (darkFraction + brightFraction).coerceIn(0.0, 1.0)
        return 0.6 * sharp + 0.25 * exposure + 0.15 * clipping
    }

    fun encode(): String = "$sharpness,$meanLuma,$darkFraction,$brightFraction"

    companion object {
        fun decode(s: String): QualityMetrics? = runCatching {
            val p = s.split(',').map { it.toDouble() }
            QualityMetrics(p[0], p[1], p[2], p[3])
        }.getOrNull()
    }
}

/** Umbrales ajustables desde Configuración (sensibilidad). */
data class QualityThresholds(
    val blurVariance: Double,
    val darkMeanLuma: Double,
    val darkFraction: Double,
    val overexposedFraction: Double,
    val lowQualityMaxPixels: Long,
) {
    companion object {
        val DEFAULT = QualityThresholds(60.0, 45.0, 0.70, 0.60, 640L * 480L)
        val CONSERVATIVE = QualityThresholds(30.0, 30.0, 0.85, 0.80, 320L * 240L)
    }
}

object QualityAnalyzer {
    const val ANALYSIS_SIDE = 512

    fun analyze(source: GrayImage): QualityMetrics {
        val img = source.normalized(ANALYSIS_SIDE)
        val w = img.width; val h = img.height
        var sumLuma = 0.0; var dark = 0; var bright = 0
        for (v in img.luma) {
            sumLuma += v
            if (v < 40f) dark++
            if (v > 245f) bright++
        }
        val n = img.luma.size.toDouble()
        // Laplaciano 4-vecinos sobre el interior.
        var sharpness = 0.0
        if (w >= 3 && h >= 3) {
            var mean = 0.0; var m2 = 0.0; var count = 0L
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val lap = (img[x - 1, y] + img[x + 1, y] + img[x, y - 1] + img[x, y + 1] - 4f * img[x, y]).toDouble()
                count++
                val d = lap - mean
                mean += d / count
                m2 += d * (lap - mean) // Welford: estable numéricamente
            }
            sharpness = if (count > 1) m2 / count else 0.0
        }
        return QualityMetrics(sharpness, sumLuma / n, dark / n, bright / n)
    }
}
