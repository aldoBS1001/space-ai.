package com.businessshop.spaceai.core

data class ScanRules(
    val largeVideoBytes: Long = 200L * 1024 * 1024,
    val largeFileBytes: Long = 50L * 1024 * 1024,
    val oldLargePhotoBytes: Long = 8L * 1024 * 1024,
    val oldAgeMillis: Long = 2L * 365 * 24 * 3600 * 1000,
    val qualityThresholds: QualityThresholds = QualityThresholds.DEFAULT,
    val compressionPreset: CompressionPreset = CompressionPreset.BALANCED,
)

data class CategoryResult(
    val category: CleanupCategory,
    /** Elementos sugeridos para revisión (nunca se borran sin confirmación). */
    val items: List<MediaItem>,
    val risk: RiskLevel,
    /** Bytes que se liberarían si se eliminan los sugeridos. Para COMPRESSIBLE_VIDEOS es el ahorro estimado. */
    val bytes: Long,
    val isEstimate: Boolean,
)

data class ScanResult(
    val storage: StorageSnapshot,
    val access: AccessReport,
    val photoCount: Int,
    val photoBytes: Long,
    val videoCount: Int,
    val videoBytes: Long,
    val fileCount: Int,
    val fileBytes: Long,
    val appTempBytes: Long,
    val categories: List<CategoryResult>,
    val exactDuplicates: List<DuplicateGroup>,
    val similarGroups: List<SimilarGroup>,
    val unreadable: List<MediaItem>,
    /** Estimación (no espacio liberado): unión sin duplicar de sugeridos + ahorro de compresión + temporales. */
    val potentiallyRecoverableBytes: Long,
    val recommendations: List<Recommendation>,
) {
    fun category(c: CleanupCategory) = categories.firstOrNull { it.category == c }
}

data class Recommendation(
    val category: CleanupCategory,
    val count: Int,
    val bytes: Long,
    val risk: RiskLevel,
    val isEstimate: Boolean,
    /** Clave de texto; la UI la traduce (es-MX / en) con [count] y [bytes]. */
    val messageKey: String,
)

/**
 * Convierte datos reales del dispositivo en categorías y recomendaciones.
 * No inventa cantidades: todo número sale de [MediaItem.sizeBytes] o de estimaciones marcadas como tal.
 */
class ScanAnalyzer(private val rules: ScanRules = ScanRules(), private val clock: () -> Long = System::currentTimeMillis) {

    fun analyze(
        storage: StorageSnapshot,
        access: AccessReport,
        items: List<MediaItem>,
        exact: ExactDuplicateResult,
        similar: List<SimilarGroup>,
        quality: Map<String, QualityMetrics>,
        videoInfo: Map<String, VideoInfo> = emptyMap(),
        appTempBytes: Long = 0L,
    ): ScanResult {
        val now = clock()
        val photos = items.filter { it.kind == MediaKind.PHOTO }
        val videos = items.filter { it.kind == MediaKind.VIDEO }
        val files = items.filter { it.kind == MediaKind.DOCUMENT || it.kind == MediaKind.OTHER }
        val t = rules.qualityThresholds
        val cats = mutableListOf<CategoryResult>()

        fun add(c: CleanupCategory, list: List<MediaItem>, risk: RiskLevel, bytes: Long = list.sumOf { it.sizeBytes }, est: Boolean = false) {
            if (list.isNotEmpty()) cats += CategoryResult(c, list.distinctBy { it.id }.sortedByDescending { it.sizeBytes }, risk, bytes, est)
        }

        val exactPhotoDups = exact.groups.filter { g -> g.items.all { it.kind == MediaKind.PHOTO } }
        val exactVideoDups = exact.groups.filter { g -> g.items.all { it.kind == MediaKind.VIDEO } }
        val exactFileDups = exact.groups.filter { g -> g.items.none { it.kind == MediaKind.PHOTO || it.kind == MediaKind.VIDEO } }
        add(CleanupCategory.EXACT_DUPLICATE_PHOTOS, exactPhotoDups.flatMap { it.duplicates }, RiskLevel.LOW)
        add(CleanupCategory.DUPLICATE_VIDEOS, exactVideoDups.flatMap { it.duplicates }, RiskLevel.LOW)
        add(CleanupCategory.DUPLICATE_FILES, exactFileDups.flatMap { it.duplicates }, RiskLevel.LOW)

        val exactIds = exact.groups.flatMap { it.duplicates }.map { it.id }.toSet()
        add(CleanupCategory.SIMILAR_PHOTOS, similar.filter { !it.isBurst }.flatMap { g -> g.candidates.map { it.item } }.filter { it.id !in exactIds }, RiskLevel.MEDIUM)
        add(CleanupCategory.BURST_PHOTOS, similar.filter { it.isBurst }.flatMap { g -> g.candidates.map { it.item } }, RiskLevel.MEDIUM)

        val nonFav = photos.filter { !it.isFavorite }
        add(CleanupCategory.SCREENSHOTS, nonFav.filter { it.isScreenshot }, RiskLevel.MEDIUM)
        val keeperIds = similar.map { it.keeper.item.id }.toSet()
        // Fotos que el usuario podría querer aunque sean "malas": favoritas y la mejor de su grupo quedan fuera.
        val qualityPool = nonFav.filter { !it.isScreenshot && it.id !in keeperIds }
        add(CleanupCategory.BLURRY_PHOTOS, qualityPool.filter { quality[it.id]?.isBlurry(t) == true }, RiskLevel.MEDIUM)
        add(CleanupCategory.DARK_PHOTOS, qualityPool.filter { quality[it.id]?.isDark(t) == true }, RiskLevel.MEDIUM)
        add(CleanupCategory.LOW_QUALITY_PHOTOS, qualityPool.filter {
            it.pixelCount in 1 until t.lowQualityMaxPixels || quality[it.id]?.isOverexposed(t) == true
        }, RiskLevel.MEDIUM)
        add(CleanupCategory.OLD_LARGE_PHOTOS, nonFav.filter {
            it.sizeBytes >= rules.oldLargePhotoBytes && it.dateTakenMillis in 1..(now - rules.oldAgeMillis)
        }, RiskLevel.HIGH)

        add(CleanupCategory.LARGE_VIDEOS, videos.filter { it.sizeBytes >= rules.largeVideoBytes }, RiskLevel.HIGH)
        add(CleanupCategory.SCREEN_RECORDINGS, videos.filter { it.isScreenRecording }, RiskLevel.MEDIUM)
        add(CleanupCategory.OLD_VIDEOS, videos.filter { it.dateTakenMillis in 1..(now - rules.oldAgeMillis) }, RiskLevel.HIGH)

        val compressible = videos.mapNotNull { v ->
            val info = videoInfo[v.id] ?: return@mapNotNull null
            val est = runCatching { VideoCompressionPlanner.estimate(info, rules.compressionPreset) }.getOrNull() ?: return@mapNotNull null
            if (est.worthIt) v to est.estimatedSavingsBytes else null
        }
        add(CleanupCategory.COMPRESSIBLE_VIDEOS, compressible.map { it.first }, RiskLevel.MEDIUM, compressible.sumOf { it.second }, est = true)

        add(CleanupCategory.LARGE_FILES, files.filter { it.sizeBytes >= rules.largeFileBytes }, RiskLevel.HIGH)

        // Unión sin duplicar: un mismo archivo no se cuenta dos veces aunque esté en varias categorías.
        val deletionCats = setOf(
            CleanupCategory.EXACT_DUPLICATE_PHOTOS, CleanupCategory.DUPLICATE_VIDEOS, CleanupCategory.DUPLICATE_FILES,
            CleanupCategory.SIMILAR_PHOTOS, CleanupCategory.BURST_PHOTOS, CleanupCategory.SCREENSHOTS,
            CleanupCategory.BLURRY_PHOTOS, CleanupCategory.DARK_PHOTOS, CleanupCategory.LOW_QUALITY_PHOTOS,
            CleanupCategory.SCREEN_RECORDINGS,
        )
        val flagged = cats.filter { it.category in deletionCats }.flatMap { it.items }.associateBy { it.id }
        val compressionOnly = compressible.filter { it.first.id !in flagged }.sumOf { it.second }
        val recoverable = flagged.values.sumOf { it.sizeBytes } + compressionOnly + appTempBytes

        return ScanResult(
            storage = storage,
            access = access,
            photoCount = photos.size, photoBytes = photos.sumOf { it.sizeBytes },
            videoCount = videos.size, videoBytes = videos.sumOf { it.sizeBytes },
            fileCount = files.size, fileBytes = files.sumOf { it.sizeBytes },
            appTempBytes = appTempBytes,
            categories = cats,
            exactDuplicates = exact.groups,
            similarGroups = similar,
            unreadable = exact.unreadable,
            potentiallyRecoverableBytes = recoverable,
            recommendations = RecommendationEngine.build(cats, appTempBytes),
        )
    }
}

object RecommendationEngine {
    /** Ignora recomendaciones triviales para no saturar al usuario. */
    const val MIN_BYTES = 5L * 1024 * 1024

    fun build(categories: List<CategoryResult>, appTempBytes: Long): List<Recommendation> {
        val recs = categories
            .filter { it.bytes >= MIN_BYTES }
            .map { Recommendation(it.category, it.items.size, it.bytes, it.risk, it.isEstimate, "rec_" + it.category.name.lowercase()) }
            .toMutableList()
        if (appTempBytes >= 1024 * 1024) {
            recs += Recommendation(CleanupCategory.APP_TEMP_FILES, 0, appTempBytes, RiskLevel.LOW, false, "rec_app_temp_files")
        }
        // Primero lo seguro y rentable: riesgo bajo, luego por bytes.
        return recs.sortedWith(compareBy<Recommendation> { it.risk.ordinal }.thenByDescending { it.bytes })
    }
}
