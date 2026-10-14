package com.businessshop.spaceai.core

/** Tipo de elemento que la plataforma entrega al motor. */
enum class MediaKind { PHOTO, VIDEO, DOCUMENT, OTHER }

/**
 * Elemento accesible del dispositivo. El motor es independiente de la plataforma:
 * Android lo construye desde MediaStore/SAF; iOS desde PhotoKit.
 * [id] es estable dentro de la plataforma (MediaStore _ID / PHAsset.localIdentifier).
 */
data class MediaItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: MediaKind,
    val mimeType: String? = null,
    val dateTakenMillis: Long = 0L,
    val dateModifiedMillis: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long = 0L,
    val isScreenshot: Boolean = false,
    val isScreenRecording: Boolean = false,
    val isFavorite: Boolean = false,
    val burstId: String? = null,
    val location: String? = null,
    /** Falso cuando el sistema no permite borrarlo desde la app (p. ej. solo lectura). */
    val deletable: Boolean = true,
) {
    val pixelCount: Long get() = width.toLong() * height.toLong()
    val aspectRatio: Double get() = if (height == 0) 0.0 else width.toDouble() / height.toDouble()
}

/** Nivel de riesgo de una acción de limpieza. */
enum class RiskLevel {
    /** Duplicados exactos verificados por hash criptográfico (se conserva una copia). */
    LOW,
    /** Fotos similares, borrosas, oscuras, capturas: requieren revisión humana. */
    MEDIUM,
    /** Documentos o archivos únicos: siempre requieren aprobación explícita. */
    HIGH;

    val requiresExplicitApproval: Boolean get() = this != LOW
}

/** Categorías que el usuario ve en la pantalla de resultados. */
enum class CleanupCategory {
    EXACT_DUPLICATE_PHOTOS,
    SIMILAR_PHOTOS,
    BURST_PHOTOS,
    SCREENSHOTS,
    BLURRY_PHOTOS,
    DARK_PHOTOS,
    LOW_QUALITY_PHOTOS,
    OLD_LARGE_PHOTOS,
    LARGE_VIDEOS,
    DUPLICATE_VIDEOS,
    SCREEN_RECORDINGS,
    OLD_VIDEOS,
    COMPRESSIBLE_VIDEOS,
    LARGE_FILES,
    DUPLICATE_FILES,
    APP_TEMP_FILES,
}

/** Estado de acceso a cada área del almacenamiento; nunca se oculta una restricción. */
enum class AccessState { FULL, LIMITED, DENIED, RESTRICTED_BY_OS, NOT_REQUESTED }

data class AccessReport(
    val photos: AccessState,
    val videos: AccessState,
    val files: AccessState,
    val otherAppsCaches: AccessState = AccessState.RESTRICTED_BY_OS,
)

/** Medición real del volumen, tomada de la API del sistema (StatFs / URLResourceValues). */
data class StorageSnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val takenAtMillis: Long,
) {
    init {
        require(totalBytes >= 0 && freeBytes >= 0) { "Valores negativos de almacenamiento" }
        require(freeBytes <= totalBytes) { "Libre > total: medición inválida" }
    }
    val usedBytes: Long get() = totalBytes - freeBytes
    val usedFraction: Double get() = if (totalBytes == 0L) 0.0 else usedBytes.toDouble() / totalBytes
}
