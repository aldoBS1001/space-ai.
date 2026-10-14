import Foundation
import SwiftUI
import SpaceAICore

/// Texto localizado (Localizable.strings en / es-MX).
func L(_ key: String, _ args: CVarArg...) -> String {
    let f = NSLocalizedString(key, comment: "")
    return args.isEmpty ? f : String(format: f, locale: .current, arguments: args)
}

func bytes(_ b: Int64) -> String { ByteFormat.format(b) }

enum DeleteModeIOS { case recentlyDeleted } // iOS no permite borrado definitivo directo: siempre pasa por "Eliminados recientemente".

struct CompressionUI {
    var item: MediaItem
    var info: VideoInfo?
    var preset: CompressionPreset
    var progress: Double?
    var job: CompressionJob?
    var previewWatched = false
    var savedCopy = false
}

/// Bandera de cancelación segura entre hilos.
final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var storage: StorageSnapshot?
    @Published var access = AccessReport(photos: .notRequested, videos: .notRequested, files: .notRequested)
    @Published var progress: ScanProgress?
    @Published var result: ScanResult?
    @Published var selected: Set<String> = []
    @Published var keep: Set<String> = []
    @Published var pendingPlan: CleanupPlan?
    @Published var cleaning = false
    @Published var lastReport: CleanupReport?
    @Published var compression: CompressionUI?
    @Published var history: [HistoryEntry] = []
    @Published var errorMessage: String?
    @Published var tempFreed: Int64?
    @Published var finishedScan = false

    @AppStorage("sensitivity") var sensitivityRaw = Sensitivity.normal.rawValue
    @AppStorage("preset") var presetRaw = CompressionPreset.balanced.rawValue
    @AppStorage("conservative") var conservative = false

    var sensitivity: Sensitivity { Sensitivity(rawValue: sensitivityRaw) ?? .normal }
    var preset: CompressionPreset { CompressionPreset(rawValue: presetRaw) ?? .balanced }

    let service = ScanService()
    private let historyStore = HistoryStore()
    private var scanTask: Task<Void, Never>?
    private var cleanupFlag = AtomicFlag()
    private var compressionTask: Task<Void, Never>?

    func refresh() {
        storage = try? IOSStorageMeter().measure()
        let p = PhotoLibrary.authorizationState()
        access = AccessReport(photos: p, videos: p, files: service.folders.urls.isEmpty ? .notRequested : .limited)
    }

    func analyzeTapped() {
        Task {
            if access.photos == .notRequested { _ = await PhotoLibrary.requestAuthorization(); refresh() }
            startScan()
        }
    }

    func startScan() {
        guard scanTask == nil else { return }
        progress = ScanProgress(phase: .storage, done: 0, total: 1)
        let access = self.access, sens = sensitivity, cons = conservative, pre = preset
        scanTask = Task {
            defer { scanTask = nil }
            do {
                let r = try await Task.detached(priority: .userInitiated) { [service] in
                    try await service.scan(access: access, sensitivity: sens, conservative: cons, preset: pre) { p in
                        Task { @MainActor [weak self] in if self?.scanTask != nil { self?.progress = p } }
                    }
                }.value
                result = r; storage = r.storage; progress = nil
                selected = Set(r.exactDuplicates.flatMap { $0.duplicates.map(\.id) })
                keep = Set(r.exactDuplicates.map(\.keeper.id) + r.similarGroups.map(\.keeper.item.id))
                finishedScan = true
            } catch is CancellationError {
                progress = nil
            } catch CoreError.cancelled {
                progress = nil
            } catch {
                progress = nil
                errorMessage = L("err_scan") + "\n\n" + error.localizedDescription
            }
        }
    }

    func cancelScan() { scanTask?.cancel(); scanTask = nil; progress = nil }

    func toggle(_ id: String) {
        guard !keep.contains(id) else { return }
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }

    func selectAll(_ ids: [String], _ on: Bool) {
        let allowed = ids.filter { !keep.contains($0) }
        if on { selected.formUnion(allowed) } else { selected.subtract(allowed) }
    }

    func setKeeper(group: [String], keeper: String) {
        keep.subtract(group); keep.insert(keeper); selected.remove(keeper)
    }

    @discardableResult
    func preparePlan(_ categories: Set<CleanupCategory>?) -> Bool {
        guard let r = result else { return false }
        var planned: [String: PlannedItem] = [:]
        for c in r.categories where (categories == nil || categories!.contains(c.category)) && c.category != .compressibleVideos {
            for item in c.items where selected.contains(item.id) {
                if let prev = planned[item.id], prev.risk >= c.risk { continue }
                planned[item.id] = PlannedItem(item: item, risk: c.risk, category: c.category)
            }
        }
        guard !planned.isEmpty else { return false }
        do {
            pendingPlan = try CleanupPlan.build(Array(planned.values), exactGroups: r.exactDuplicates, similarGroups: r.similarGroups, protectedIds: keep)
            return true
        } catch CoreError.unsafePlan(let m) {
            errorMessage = L("err_unsafe") + "\n\n" + m; return false
        } catch { errorMessage = error.localizedDescription; return false }
    }

    @discardableResult
    func planFor(_ items: [MediaItem], risk: RiskLevel, category: CleanupCategory) -> Bool {
        do { pendingPlan = try CleanupPlan.build(items.map { PlannedItem(item: $0, risk: risk, category: category) }, protectedIds: keep); return true }
        catch { errorMessage = L("err_unsafe"); return false }
    }

    func execute(approved: Bool, onDone: @escaping () -> Void) {
        guard let plan = pendingPlan, !cleaning else { return }
        if plan.requiresExplicitApproval && !approved { return }
        let approval = approved ? CleanupApproval(planId: plan.id, approvedAt: Int64(Date().timeIntervalSince1970 * 1000), acknowledgedRisk: plan.maxRisk) : nil
        cleaning = true
        let flag = AtomicFlag(); cleanupFlag = flag
        let engine = SafeCleanupEngine(executor: IOSCleanupExecutor(folders: service.folders), meter: IOSStorageMeter(),
                                       log: { [historyStore] in historyStore.record($0) })
        Task {
            do {
                let report = try await engine.run(plan, approval: approval, cancel: CancellationSignal { flag.value })
                let removed = Set(report.results.filter { [.deleted, .movedToTrash, .notFound].contains($0.outcome) }.map(\.item.id))
                lastReport = report; storage = report.after; cleaning = false; pendingPlan = nil
                selected.subtract(removed)
                if var r = result { prune(&r, removed); result = r }
                onDone()
            } catch {
                cleaning = false
                errorMessage = L("err_cleanup") + "\n\n" + error.localizedDescription
            }
        }
    }

    func cancelCleanup() { cleanupFlag.value = true }

    private func prune(_ r: inout ScanResult, _ removed: Set<String>) {
        guard !removed.isEmpty else { return }
        let removedBytes = Dictionary(r.categories.flatMap(\.items).map { ($0.id, $0.sizeBytes) }, uniquingKeysWith: { a, _ in a })
            .filter { removed.contains($0.key) }.values.reduce(0, +)
        r.categories = r.categories.compactMap { c in
            var c = c
            c.items.removeAll { removed.contains($0.id) }
            if c.items.isEmpty { return nil }
            if !c.isEstimate { c.bytes = c.items.reduce(0) { $0 + $1.sizeBytes } }
            return c
        }
        r.recommendations = r.recommendations.compactMap { rec in
            if rec.category == .appTempFiles { return rec }
            guard let c = r.categories.first(where: { $0.category == rec.category }) else { return nil }
            var rec = rec; rec.count = c.items.count; rec.bytes = c.bytes; return rec
        }
        r.exactDuplicates = r.exactDuplicates.compactMap { g in
            var g = g; g.items.removeAll { removed.contains($0.id) }; return g.items.count > 1 ? g : nil
        }
        r.similarGroups = r.similarGroups.compactMap { g in
            var g = g; g.items.removeAll { removed.contains($0.item.id) }; return g.items.count > 1 ? g : nil
        }
        r.potentiallyRecoverableBytes = max(0, r.potentiallyRecoverableBytes - removedBytes)
    }

    func clearTemp() {
        let freed = AppTempFiles.clear()
        tempFreed = freed
        storage = try? IOSStorageMeter().measure()
        result?.appTempBytes = 0
        result?.recommendations.removeAll { $0.category == .appTempFiles }
    }

    // MARK: Compresión

    func openCompression(_ item: MediaItem) {
        compression = CompressionUI(item: item, preset: preset)
        Task {
            let info = await service.compressor.info(for: item)
            if compression?.item.id == item.id { compression?.info = info }
        }
    }

    func setPreset(_ p: CompressionPreset) { compression?.preset = p; compression?.job = nil }

    func startCompression() {
        guard let c = compression, let info = c.info else { return }
        compression?.progress = 0; compression?.job = nil; compression?.previewWatched = false
        compressionTask = Task {
            do {
                let job = try await service.compressor.compress(c.item, info: info, preset: c.preset) { p in
                    Task { @MainActor [weak self] in if self?.compression?.job == nil { self?.compression?.progress = p } }
                }
                compression?.progress = nil; compression?.job = job
            } catch is CancellationError {
                compression?.progress = nil
            } catch is InsufficientSpace {
                compression?.progress = nil; errorMessage = L("err_no_space")
            } catch {
                compression?.progress = nil; errorMessage = L("err_compression") + "\n\n" + error.localizedDescription
            }
        }
    }

    func cancelCompression() { compressionTask?.cancel() }

    func saveCopy() {
        guard let c = compression, let job = c.job, job.validation == .valid, c.previewWatched else { return }
        Task {
            do { try await service.compressor.saveToLibrary(job.outputURL, original: c.item); compression?.savedCopy = true }
            catch { errorMessage = L("err_save") + "\n\n" + error.localizedDescription }
        }
    }

    func discardCopy() {
        if let u = compression?.job?.outputURL { try? FileManager.default.removeItem(at: u) }
        compression?.job = nil; compression?.previewWatched = false
    }

    func closeCompression() {
        compressionTask?.cancel()
        if compression?.savedCopy != true, let u = compression?.job?.outputURL { try? FileManager.default.removeItem(at: u) }
        compression = nil
    }

    // MARK: Historial

    func loadHistory() { history = historyStore.load().reversed() }
    func clearHistory() { historyStore.clear(); history = [] }
    func clearAnalysisCache() { service.cache.clear() }
}
