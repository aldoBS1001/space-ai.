package com.businessshop.spaceai.core

import java.util.UUID

class UnsafePlanException(message: String) : IllegalStateException(message)

/** Elemento seleccionado con el motivo y riesgo por el que se sugirió. */
data class PlannedItem(val item: MediaItem, val risk: RiskLevel, val category: CleanupCategory)

/**
 * Plan inmutable de eliminación (pasos 1 y 2 de la eliminación segura):
 * lista exacta + bytes potencialmente recuperables. Valida que nunca se borren
 * todas las copias de un grupo ni elementos protegidos.
 */
class CleanupPlan private constructor(
    val id: String,
    val items: List<PlannedItem>,
) {
    val totalBytes: Long get() = items.sumOf { it.item.sizeBytes }
    val maxRisk: RiskLevel get() = items.maxOfOrNull { it.risk } ?: RiskLevel.LOW
    val requiresExplicitApproval: Boolean get() = maxRisk.requiresExplicitApproval
    val count: Int get() = items.size

    companion object {
        fun build(
            selected: List<PlannedItem>,
            exactGroups: List<DuplicateGroup> = emptyList(),
            similarGroups: List<SimilarGroup> = emptyList(),
            /** Elementos que el usuario marcó como "conservar" (incluye la mejor foto elegida). */
            protectedIds: Set<String> = emptySet(),
        ): CleanupPlan {
            val unique = selected.distinctBy { it.item.id }
            val ids = unique.map { it.item.id }.toSet()
            unique.firstOrNull { it.item.id in protectedIds }?.let {
                throw UnsafePlanException("El elemento ${it.item.displayName} está marcado para conservar")
            }
            unique.firstOrNull { !it.item.deletable }?.let {
                throw UnsafePlanException("El sistema no permite eliminar ${it.item.displayName}")
            }
            for (g in exactGroups) if (g.items.all { it.id in ids })
                throw UnsafePlanException("Se eliminarían todas las copias de un duplicado; se debe conservar al menos una")
            for (g in similarGroups) if (g.items.all { it.item.id in ids })
                throw UnsafePlanException("Se eliminarían todas las fotos de un grupo similar; se debe conservar al menos una")
            return CleanupPlan(UUID.randomUUID().toString(), unique)
        }
    }
}

/** Confirmación explícita del usuario (paso 3). Va ligada a un plan concreto. */
data class CleanupApproval(val planId: String, val approvedAtMillis: Long, val acknowledgedRisk: RiskLevel)

enum class ItemOutcome {
    /** Borrado definitivo: el espacio debería liberarse ya. */
    DELETED,
    /** Enviado a la papelera / "Eliminados recientemente": el espacio NO se libera aún. */
    MOVED_TO_TRASH,
    /** El usuario rechazó el diálogo del sistema. */
    USER_DENIED,
    FAILED,
    /** Ya no existía (borrado fuera de la app). */
    NOT_FOUND,
}

data class ItemResult(val item: MediaItem, val outcome: ItemOutcome, val error: String? = null)

/** Ejecuta mediante APIs oficiales (paso 4). Android: MediaStore trash/delete request. iOS: PHAssetChangeRequest. */
fun interface CleanupExecutor {
    fun execute(items: List<MediaItem>, cancel: CancellationSignal): List<ItemResult>
}

/** Mide el almacenamiento real (paso 6). */
fun interface StorageMeter {
    fun measure(): StorageSnapshot
}

enum class CleanupStatus {
    /** Se midió un aumento real de espacio libre. */
    VERIFIED_FREED,
    /** Archivos en la papelera del sistema: se liberará cuando se vacíe. */
    PENDING_SYSTEM_TRASH,
    /** Hubo eliminaciones, pero la medición no muestra aumento (otro proceso escribió, o el sistema aún no actualiza). */
    UNVERIFIED,
    PARTIAL_FAILURE,
    NOTHING_DELETED,
    CANCELLED,
}

data class CleanupReport(
    val planId: String,
    val requestedCount: Int,
    val requestedBytes: Long,
    val deletedBytes: Long,
    val trashedBytes: Long,
    val results: List<ItemResult>,
    val before: StorageSnapshot,
    val after: StorageSnapshot,
    val status: CleanupStatus,
) {
    /** Diferencia medida de espacio libre. Puede ser menor que lo borrado si otra app escribió en paralelo. */
    val measuredFreedBytes: Long get() = after.freeBytes - before.freeBytes
    val failed: List<ItemResult> get() = results.filter { it.outcome == ItemOutcome.FAILED }
    val deletedCount: Int get() = results.count { it.outcome == ItemOutcome.DELETED }
    val trashedCount: Int get() = results.count { it.outcome == ItemOutcome.MOVED_TO_TRASH }
}

/** Registro de éxitos y errores (paso 5) para el historial. */
fun interface CleanupLog {
    fun record(report: CleanupReport)
}

/**
 * Orquesta los 6 pasos de eliminación segura. Garantías:
 * - sin aprobación ligada al plan no se ejecuta nada de riesgo medio/alto,
 * - el estado final nunca dice "liberado" sin medición que lo respalde,
 * - papelera y espacio liberado se reportan por separado.
 */
class SafeCleanupEngine(
    private val executor: CleanupExecutor,
    private val meter: StorageMeter,
    private val log: CleanupLog = CleanupLog { },
    /** Tolerancia de medición: el sistema de archivos reserva bloques y redondea. */
    private val verifyToleranceFraction: Double = 0.5,
) {
    fun run(plan: CleanupPlan, approval: CleanupApproval?, cancel: CancellationSignal = CancellationSignal.NONE): CleanupReport {
        if (plan.requiresExplicitApproval) {
            if (approval == null) throw UnsafePlanException("Se requiere aprobación explícita para riesgo ${plan.maxRisk}")
            if (approval.planId != plan.id) throw UnsafePlanException("La aprobación no corresponde a este plan")
            if (approval.acknowledgedRisk < plan.maxRisk) throw UnsafePlanException("La aprobación no cubre el riesgo ${plan.maxRisk}")
        }
        val before = meter.measure()
        val results = if (cancel.isCancelled()) emptyList() else executor.execute(plan.items.map { it.item }, cancel)
        val after = meter.measure()

        val deletedBytes = results.filter { it.outcome == ItemOutcome.DELETED }.sumOf { it.item.sizeBytes }
        val trashedBytes = results.filter { it.outcome == ItemOutcome.MOVED_TO_TRASH }.sumOf { it.item.sizeBytes }
        val freed = after.freeBytes - before.freeBytes
        val anyFailed = results.any { it.outcome == ItemOutcome.FAILED }

        val status = when {
            cancel.isCancelled() && results.none { it.outcome == ItemOutcome.DELETED || it.outcome == ItemOutcome.MOVED_TO_TRASH } -> CleanupStatus.CANCELLED
            deletedBytes == 0L && trashedBytes == 0L -> if (anyFailed) CleanupStatus.PARTIAL_FAILURE else CleanupStatus.NOTHING_DELETED
            anyFailed -> CleanupStatus.PARTIAL_FAILURE
            deletedBytes > 0 && freed >= (deletedBytes * verifyToleranceFraction).toLong() -> CleanupStatus.VERIFIED_FREED
            deletedBytes == 0L && trashedBytes > 0 -> CleanupStatus.PENDING_SYSTEM_TRASH
            else -> CleanupStatus.UNVERIFIED
        }
        val report = CleanupReport(plan.id, plan.count, plan.totalBytes, deletedBytes, trashedBytes, results, before, after, status)
        runCatching { log.record(report) }
        return report
    }
}
