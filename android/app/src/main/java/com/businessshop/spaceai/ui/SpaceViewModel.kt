package com.businessshop.spaceai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.businessshop.spaceai.core.AccessReport
import com.businessshop.spaceai.core.AccessState
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.CleanupApproval
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.core.CleanupPlan
import com.businessshop.spaceai.core.CleanupReport
import com.businessshop.spaceai.core.CompressionPreset
import com.businessshop.spaceai.core.CompressionValidation
import com.businessshop.spaceai.core.MediaItem
import com.businessshop.spaceai.core.PlannedItem
import com.businessshop.spaceai.core.RiskLevel
import com.businessshop.spaceai.core.SafeCleanupEngine
import com.businessshop.spaceai.core.ScanResult
import com.businessshop.spaceai.core.Sensitivity
import com.businessshop.spaceai.core.StorageSnapshot
import com.businessshop.spaceai.core.UnsafePlanException
import com.businessshop.spaceai.core.VideoInfo
import com.businessshop.spaceai.data.AndroidCleanupExecutor
import com.businessshop.spaceai.data.AppSettings
import com.businessshop.spaceai.data.CompressionJobResult
import com.businessshop.spaceai.data.DeleteMode
import com.businessshop.spaceai.data.HistoryEntry
import com.businessshop.spaceai.data.HistoryStore
import com.businessshop.spaceai.data.InsufficientSpaceException
import com.businessshop.spaceai.data.ScanOrchestrator
import com.businessshop.spaceai.data.ScanProgress
import com.businessshop.spaceai.data.UriCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

enum class UiError { SCAN_FAILED, UNSAFE_PLAN, CLEANUP_FAILED, NO_SPACE_FOR_COMPRESSION, COMPRESSION_FAILED, SAVE_FAILED }

data class CompressionUi(
    val item: MediaItem,
    val info: VideoInfo?,
    val preset: CompressionPreset,
    val progress: Int? = null,
    val job: CompressionJobResult? = null,
    val previewWatched: Boolean = false,
    val savedCopy: Boolean = false,
)

data class UiState(
    val storage: StorageSnapshot? = null,
    val access: AccessReport = AccessReport(AccessState.NOT_REQUESTED, AccessState.NOT_REQUESTED, AccessState.NOT_REQUESTED),
    val progress: ScanProgress? = null,
    val result: ScanResult? = null,
    /** Ids seleccionados para eliminar. */
    val selected: Set<String> = emptySet(),
    /** Ids que el usuario decidió conservar (incluye la mejor foto elegida por grupo). */
    val keep: Set<String> = emptySet(),
    val pendingPlan: CleanupPlan? = null,
    val cleaning: Boolean = false,
    val lastReport: CleanupReport? = null,
    val compression: CompressionUi? = null,
    val history: List<HistoryEntry> = emptyList(),
    val error: UiError? = null,
    val errorDetail: String? = null,
    val tempFreedBytes: Long? = null,
)

class SpaceViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    private val orchestrator = ScanOrchestrator(app, settings)
    private val history = HistoryStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var cleanupCancel = AtomicBoolean(false)
    private var compressionJob: Job? = null

    private var finishedFlag = false

    init { refreshStorage() }

    /** true una sola vez tras terminar un análisis (para navegar a resultados). */
    fun justFinished(): Boolean { val f = finishedFlag; finishedFlag = false; return f }

    fun refreshStorage() = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) { orchestrator.measure() }
        _state.update { it.copy(storage = s) }
    }

    fun setAccess(access: AccessReport) = _state.update { it.copy(access = access) }

    fun startScan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(progress = ScanProgress(com.businessshop.spaceai.data.ScanPhase.STORAGE, 0, 1), error = null) }
        scanJob = viewModelScope.launch {
            try {
                val r = orchestrator.scan(_state.value.access) { p -> _state.update { it.copy(progress = p) } }
                finishedFlag = true
                _state.update { s ->
                    s.copy(result = r, storage = r.storage, progress = null,
                        selected = defaultSelection(r), keep = defaultKeep(r))
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, error = UiError.SCAN_FAILED, errorDetail = e.message) }
            }
        }
    }

    fun cancelScan() { scanJob?.cancel(); _state.update { it.copy(progress = null) } }

    /** Preselección conservadora: solo duplicados exactos (riesgo bajo). Lo demás lo elige el usuario. */
    private fun defaultSelection(r: ScanResult): Set<String> =
        r.exactDuplicates.flatMap { g -> g.duplicates.map { it.id } }.toSet()

    private fun defaultKeep(r: ScanResult): Set<String> =
        (r.exactDuplicates.map { it.keeper.id } + r.similarGroups.map { it.keeper.item.id }).toSet()

    fun toggle(id: String) = _state.update { s ->
        if (id in s.keep) s else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun selectAll(ids: Collection<String>, select: Boolean) = _state.update { s ->
        val allowed = ids.filter { it !in s.keep }
        s.copy(selected = if (select) s.selected + allowed else s.selected - allowed.toSet())
    }

    /** Cambia cuál foto se conserva dentro de un grupo; las demás del grupo quedan seleccionables. */
    fun setKeeper(groupIds: List<String>, keeperId: String) = _state.update { s ->
        s.copy(keep = s.keep - groupIds.toSet() + keeperId, selected = s.selected - keeperId)
    }

    fun clearError() = _state.update { it.copy(error = null, errorDetail = null) }

    /** Pasos 1-2: construir el plan exacto con lo seleccionado en las categorías dadas. */
    fun preparePlan(categories: Set<CleanupCategory>?): Boolean {
        val s = _state.value
        val r = s.result ?: return false
        val planned = LinkedHashMap<String, PlannedItem>()
        for (c in r.categories) {
            if (categories != null && c.category !in categories) continue
            if (c.category == CleanupCategory.COMPRESSIBLE_VIDEOS) continue // comprimir no es borrar
            for (item in c.items) if (item.id in s.selected) {
                val prev = planned[item.id]
                if (prev == null || prev.risk < c.risk) planned[item.id] = PlannedItem(item, c.risk, c.category)
            }
        }
        // Los no-conservados de grupos similares pueden no figurar en ninguna categoría si eran duplicados exactos.
        for (g in r.exactDuplicates) for (item in g.items) if (item.id in s.selected && item.id !in planned &&
            (categories == null || CleanupCategory.EXACT_DUPLICATE_PHOTOS in categories || CleanupCategory.DUPLICATE_VIDEOS in categories || CleanupCategory.DUPLICATE_FILES in categories)) {
            planned[item.id] = PlannedItem(item, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)
        }
        if (planned.isEmpty()) return false
        return try {
            val plan = CleanupPlan.build(planned.values.toList(), r.exactDuplicates, r.similarGroups, s.keep)
            _state.update { it.copy(pendingPlan = plan) }
            true
        } catch (e: UnsafePlanException) {
            _state.update { it.copy(error = UiError.UNSAFE_PLAN, errorDetail = e.message) }
            false
        }
    }

    fun planForItems(items: List<MediaItem>, risk: RiskLevel, category: CleanupCategory): Boolean = try {
        val plan = CleanupPlan.build(items.map { PlannedItem(it, risk, category) }, protectedIds = _state.value.keep)
        _state.update { it.copy(pendingPlan = plan) }; true
    } catch (e: UnsafePlanException) {
        _state.update { it.copy(error = UiError.UNSAFE_PLAN, errorDetail = e.message) }; false
    }

    fun discardPlan() = _state.update { it.copy(pendingPlan = null) }

    /** Pasos 3-6: confirmación explícita -> APIs oficiales -> registro -> nueva medición. */
    fun executePlan(userApproved: Boolean, mode: DeleteMode = settings.effectiveDeleteMode, onDone: () -> Unit) {
        val plan = _state.value.pendingPlan ?: return
        if (plan.requiresExplicitApproval && !userApproved) return
        val approval = if (userApproved) CleanupApproval(plan.id, System.currentTimeMillis(), plan.maxRisk) else null
        cleanupCancel = AtomicBoolean(false)
        val cancelFlag = cleanupCancel
        _state.update { it.copy(cleaning = true) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    SafeCleanupEngine(AndroidCleanupExecutor(getApplication<Application>(), mode), { orchestrator.measure() }, history)
                        .run(plan, approval, CancellationSignal { cancelFlag.get() })
                }
                val removed = report.results.filter {
                    it.outcome == com.businessshop.spaceai.core.ItemOutcome.DELETED ||
                        it.outcome == com.businessshop.spaceai.core.ItemOutcome.MOVED_TO_TRASH ||
                        it.outcome == com.businessshop.spaceai.core.ItemOutcome.NOT_FOUND
                }.map { it.item.id }.toSet()
                _state.update { s ->
                    s.copy(cleaning = false, pendingPlan = null, lastReport = report, storage = report.after,
                        selected = s.selected - removed, result = s.result?.let { pruneResult(it, removed) })
                }
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(cleaning = false, error = UiError.CLEANUP_FAILED, errorDetail = e.message) }
            }
        }
    }

    fun cancelCleanup() = cleanupCancel.set(true)

    /** Vacía de la papelera del sistema lo que SPACE AI acaba de enviar (borrado definitivo, con confirmación del sistema). */
    fun deleteTrashedPermanently(onDone: () -> Unit) {
        val report = _state.value.lastReport ?: return
        val trashed = report.results.filter { it.outcome == com.businessshop.spaceai.core.ItemOutcome.MOVED_TO_TRASH }.map { it.item }
        if (trashed.isEmpty()) return
        val plan = runCatching { CleanupPlan.build(trashed.map { PlannedItem(it, RiskLevel.MEDIUM, CleanupCategory.SIMILAR_PHOTOS) }) }.getOrNull() ?: return
        _state.update { it.copy(pendingPlan = plan) }
        executePlan(userApproved = true, mode = DeleteMode.PERMANENT, onDone = onDone)
    }

    /** Quita del resultado los elementos ya eliminados para que la UI refleje el estado real. */
    private fun pruneResult(r: ScanResult, removed: Set<String>): ScanResult {
        if (removed.isEmpty()) return r
        val cats = r.categories.mapNotNull { c ->
            val left = c.items.filter { it.id !in removed }
            if (left.isEmpty()) null else c.copy(items = left, bytes = if (c.isEstimate) c.bytes else left.sumOf { it.sizeBytes })
        }
        val recs = r.recommendations.mapNotNull { rec ->
            val c = cats.firstOrNull { it.category == rec.category } ?: return@mapNotNull if (rec.category == CleanupCategory.APP_TEMP_FILES) rec else null
            rec.copy(count = c.items.size, bytes = c.bytes)
        }
        val removedBytes = r.categories.flatMap { it.items }.filter { it.id in removed }.distinctBy { it.id }.sumOf { it.sizeBytes }
        return r.copy(
            categories = cats, recommendations = recs,
            exactDuplicates = r.exactDuplicates.map { g -> g.copy(items = g.items.filter { it.id !in removed }) }.filter { it.items.size > 1 },
            similarGroups = r.similarGroups.map { g -> g.copy(items = g.items.filter { it.item.id !in removed }) }.filter { it.items.size > 1 },
            potentiallyRecoverableBytes = (r.potentiallyRecoverableBytes - removedBytes).coerceAtLeast(0),
        )
    }

    fun clearAppTemp() = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { orchestrator.tempFiles().clear() }
        val s = withContext(Dispatchers.IO) { orchestrator.measure() }
        _state.update { st ->
            st.copy(tempFreedBytes = freed, storage = s, result = st.result?.let { r ->
                r.copy(appTempBytes = 0, recommendations = r.recommendations.filter { it.category != CleanupCategory.APP_TEMP_FILES })
            })
        }
    }

    // ---- Compresión de video ----

    fun openCompression(item: MediaItem) {
        _state.update { it.copy(compression = CompressionUi(item, null, settings.preset)) }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { orchestrator.compressor().readInfo(UriCodec.toUri(item.id), item.sizeBytes) }
            _state.update { s -> s.copy(compression = s.compression?.takeIf { it.item.id == item.id }?.copy(info = info)) }
        }
    }

    fun setPreset(p: CompressionPreset) = _state.update { s -> s.copy(compression = s.compression?.copy(preset = p, job = null)) }

    fun startCompression() {
        val c = _state.value.compression ?: return
        val info = c.info ?: return
        compressionJob?.cancel()
        _state.update { it.copy(compression = c.copy(progress = 0, job = null, previewWatched = false)) }
        compressionJob = viewModelScope.launch {
            try {
                val job = orchestrator.compressor().compress(c.item, info, c.preset) { p ->
                    _state.update { s -> s.copy(compression = s.compression?.copy(progress = p)) }
                }
                if (job.validation != CompressionValidation.Valid) job.outputFile.delete()
                _state.update { s -> s.copy(compression = s.compression?.copy(progress = null, job = job)) }
            } catch (e: CancellationException) {
                _state.update { s -> s.copy(compression = s.compression?.copy(progress = null)) }
                throw e
            } catch (e: InsufficientSpaceException) {
                _state.update { s -> s.copy(compression = s.compression?.copy(progress = null), error = UiError.NO_SPACE_FOR_COMPRESSION) }
            } catch (e: Exception) {
                _state.update { s -> s.copy(compression = s.compression?.copy(progress = null), error = UiError.COMPRESSION_FAILED, errorDetail = e.message) }
            }
        }
    }

    fun cancelCompression() = compressionJob?.cancel()

    fun markPreviewWatched() = _state.update { s -> s.copy(compression = s.compression?.copy(previewWatched = true)) }

    /** Guarda la copia validada en la galería; el original sigue intacto hasta que el usuario lo elimine. */
    fun saveCompressedCopy() {
        val c = _state.value.compression ?: return
        val job = c.job ?: return
        if (job.validation != CompressionValidation.Valid || !c.previewWatched) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { orchestrator.compressor().saveToGallery(job.outputFile, c.item) }
                _state.update { s -> s.copy(compression = s.compression?.copy(savedCopy = true)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = UiError.SAVE_FAILED, errorDetail = e.message) }
            }
        }
    }

    fun discardCompressed() {
        _state.value.compression?.job?.outputFile?.delete()
        _state.update { s -> s.copy(compression = s.compression?.copy(job = null, previewWatched = false)) }
    }

    fun closeCompression() {
        compressionJob?.cancel()
        val c = _state.value.compression
        if (c?.savedCopy != true) c?.job?.outputFile?.delete()
        _state.update { it.copy(compression = null) }
    }

    // ---- Ajustes / historial ----

    fun loadHistory() = viewModelScope.launch {
        val h = withContext(Dispatchers.IO) { history.load() }
        _state.update { it.copy(history = h.reversed()) }
    }

    fun clearHistory() = viewModelScope.launch {
        withContext(Dispatchers.IO) { history.clear() }
        _state.update { it.copy(history = emptyList()) }
    }

    fun clearAnalysisCache() = viewModelScope.launch(Dispatchers.IO) { orchestrator.cache.clear() }

    fun setSensitivity(s: Sensitivity) { settings.sensitivity = s }

    fun documents() = orchestrator.documents()
}
