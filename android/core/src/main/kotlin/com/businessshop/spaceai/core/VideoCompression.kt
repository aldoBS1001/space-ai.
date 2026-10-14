package com.businessshop.spaceai.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Perfiles de salida. Se usan los codificadores de hardware (H.264/HEVC) de cada plataforma. */
enum class CompressionPreset(val maxShortSide: Int, val bitsPerPixelPerFrame: Double, val audioBitrate: Int) {
    /** Calidad alta, ahorro moderado. */
    HIGH(1080, 0.075, 128_000),
    /** Equilibrio recomendado. */
    BALANCED(720, 0.070, 96_000),
    /** Máximo ahorro, para videos de referencia o mensajería. */
    SMALL(540, 0.060, 64_000),
}

data class VideoInfo(
    val sizeBytes: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double = 30.0,
    val hasAudio: Boolean = true,
)

data class CompressionEstimate(
    val targetWidth: Int,
    val targetHeight: Int,
    val targetVideoBitrate: Int,
    val targetAudioBitrate: Int,
    val estimatedBytes: Long,
    val estimatedSavingsBytes: Long,
    val worthIt: Boolean,
) {
    val savingsFraction: Double get() = if (estimatedBytes + estimatedSavingsBytes == 0L) 0.0
        else estimatedSavingsBytes.toDouble() / (estimatedBytes + estimatedSavingsBytes)
}

object VideoCompressionPlanner {
    /** Por debajo de este ahorro no se ofrece comprimir (la pérdida de calidad no compensa). */
    const val MIN_SAVINGS_FRACTION = 0.20
    const val MIN_SAVINGS_BYTES = 10L * 1024 * 1024
    private const val CONTAINER_OVERHEAD = 1.02

    fun estimate(info: VideoInfo, preset: CompressionPreset = CompressionPreset.BALANCED): CompressionEstimate {
        require(info.durationMillis > 0) { "Duración inválida" }
        val shortSide = min(info.width, info.height)
        val scale = if (shortSide > preset.maxShortSide && shortSide > 0) preset.maxShortSide.toDouble() / shortSide else 1.0
        // Los codificadores exigen dimensiones pares.
        val tw = even((info.width * scale).roundToInt())
        val th = even((info.height * scale).roundToInt())
        val fps = info.frameRate.coerceIn(1.0, 60.0)
        val videoBitrate = max(250_000, (tw.toLong() * th * fps * preset.bitsPerPixelPerFrame).toInt())
        val audio = if (info.hasAudio) preset.audioBitrate else 0
        val seconds = info.durationMillis / 1000.0
        val sourceBitrate = info.sizeBytes * 8.0 / seconds
        // Nunca estimar una salida con más bitrate que la fuente.
        val effectiveVideo = min(videoBitrate.toDouble(), max(0.0, sourceBitrate - audio)).toInt()
        val estimated = (((effectiveVideo + audio) * seconds / 8.0) * CONTAINER_OVERHEAD).toLong()
        val savings = max(0L, info.sizeBytes - estimated)
        val worth = savings >= MIN_SAVINGS_BYTES && savings.toDouble() / info.sizeBytes >= MIN_SAVINGS_FRACTION
        return CompressionEstimate(tw, th, effectiveVideo, audio, estimated, savings, worth)
    }

    private fun even(v: Int) = max(2, v - (v % 2))
}

/** Resultado verificado de la copia comprimida, medido por la plataforma tras codificar. */
data class CompressedOutput(
    val sizeBytes: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    /** La plataforma pudo abrir y decodificar el primer y último cuadro. */
    val decodable: Boolean,
)

sealed class CompressionValidation {
    object Valid : CompressionValidation()
    data class Invalid(val reason: Reason) : CompressionValidation()
    enum class Reason { NOT_DECODABLE, DURATION_MISMATCH, NOT_SMALLER, EMPTY_FILE, BAD_DIMENSIONS }
}

object CompressionValidator {
    /**
     * Regla de seguridad: el original solo puede eliminarse si la copia
     * es decodificable, dura lo mismo (±1 s o ±2 %) y es realmente más pequeña.
     * Además el usuario debe validarla viéndola (la UI exige reproducirla).
     */
    fun validate(original: VideoInfo, output: CompressedOutput): CompressionValidation {
        if (output.sizeBytes <= 0) return CompressionValidation.Invalid(CompressionValidation.Reason.EMPTY_FILE)
        if (!output.decodable) return CompressionValidation.Invalid(CompressionValidation.Reason.NOT_DECODABLE)
        if (output.width <= 0 || output.height <= 0) return CompressionValidation.Invalid(CompressionValidation.Reason.BAD_DIMENSIONS)
        val tolerance = max(1000L, (original.durationMillis * 0.02).toLong())
        if (kotlin.math.abs(output.durationMillis - original.durationMillis) > tolerance)
            return CompressionValidation.Invalid(CompressionValidation.Reason.DURATION_MISMATCH)
        if (output.sizeBytes >= original.sizeBytes) return CompressionValidation.Invalid(CompressionValidation.Reason.NOT_SMALLER)
        return CompressionValidation.Valid
    }

    /** Espacio requerido antes de iniciar: salida estimada + 10 % + 200 MB de reserva del sistema. */
    fun requiredFreeBytes(estimate: CompressionEstimate): Long =
        (estimate.estimatedBytes * 1.10).toLong() + 200L * 1024 * 1024
}
