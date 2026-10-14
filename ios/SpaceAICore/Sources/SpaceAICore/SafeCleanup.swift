import Foundation

public struct PlannedItem: Sendable {
    public var item: MediaItem
    public var risk: RiskLevel
    public var category: CleanupCategory
    public init(item: MediaItem, risk: RiskLevel, category: CleanupCategory) { self.item = item; self.risk = risk; self.category = category }
}

/// Plan inmutable y validado: nunca borra todas las copias de un grupo ni elementos protegidos.
public struct CleanupPlan: Sendable {
    public let id: String
    public let items: [PlannedItem]
    public var totalBytes: Int64 { items.reduce(0) { $0 + $1.item.sizeBytes } }
    public var maxRisk: RiskLevel { items.map(\.risk).max() ?? .low }
    public var requiresExplicitApproval: Bool { maxRisk.requiresExplicitApproval }
    public var count: Int { items.count }

    public static func build(_ selected: [PlannedItem], exactGroups: [DuplicateGroup] = [], similarGroups: [SimilarGroup] = [],
                             protectedIds: Set<String> = []) throws -> CleanupPlan {
        var seen = Set<String>()
        let unique = selected.filter { seen.insert($0.item.id).inserted }
        if let p = unique.first(where: { protectedIds.contains($0.item.id) }) {
            throw CoreError.unsafePlan("El elemento \(p.item.displayName) está marcado para conservar")
        }
        if let p = unique.first(where: { !$0.item.deletable }) {
            throw CoreError.unsafePlan("El sistema no permite eliminar \(p.item.displayName)")
        }
        for g in exactGroups where g.items.allSatisfy({ seen.contains($0.id) }) {
            throw CoreError.unsafePlan("Se eliminarían todas las copias de un duplicado; se debe conservar al menos una")
        }
        for g in similarGroups where g.items.allSatisfy({ seen.contains($0.item.id) }) {
            throw CoreError.unsafePlan("Se eliminarían todas las fotos de un grupo similar; se debe conservar al menos una")
        }
        return CleanupPlan(id: UUID().uuidString, items: unique)
    }
}

public struct CleanupApproval: Sendable {
    public var planId: String
    public var approvedAt: Int64
    public var acknowledgedRisk: RiskLevel
    public init(planId: String, approvedAt: Int64, acknowledgedRisk: RiskLevel) {
        self.planId = planId; self.approvedAt = approvedAt; self.acknowledgedRisk = acknowledgedRisk
    }
}

public enum ItemOutcome: String, Sendable { case deleted, movedToTrash, userDenied, failed, notFound }

public struct ItemResult: Sendable {
    public var item: MediaItem
    public var outcome: ItemOutcome
    public var error: String?
    public init(item: MediaItem, outcome: ItemOutcome, error: String? = nil) { self.item = item; self.outcome = outcome; self.error = error }
}

/// iOS: PHAssetChangeRequest.deleteAssets (va a "Eliminados recientemente") o FileManager para archivos autorizados.
public protocol CleanupExecutor {
    func execute(_ items: [MediaItem], cancel: CancellationSignal) async -> [ItemResult]
}

public protocol StorageMeter {
    func measure() throws -> StorageSnapshot
}

public enum CleanupStatus: String, Sendable { case verifiedFreed, pendingSystemTrash, unverified, partialFailure, nothingDeleted, cancelled }

public struct CleanupReport: Sendable {
    public var planId: String
    public var requestedCount: Int
    public var requestedBytes: Int64
    public var deletedBytes: Int64
    public var trashedBytes: Int64
    public var results: [ItemResult]
    public var before: StorageSnapshot
    public var after: StorageSnapshot
    public var status: CleanupStatus
    public var measuredFreedBytes: Int64 { after.freeBytes - before.freeBytes }
    public var failed: [ItemResult] { results.filter { $0.outcome == .failed } }
    public var deletedCount: Int { results.filter { $0.outcome == .deleted }.count }
    public var trashedCount: Int { results.filter { $0.outcome == .movedToTrash }.count }
}

public final class SafeCleanupEngine {
    private let executor: CleanupExecutor
    private let meter: StorageMeter
    private let log: (CleanupReport) -> Void
    private let verifyTolerance: Double

    public init(executor: CleanupExecutor, meter: StorageMeter, log: @escaping (CleanupReport) -> Void = { _ in }, verifyTolerance: Double = 0.5) {
        self.executor = executor; self.meter = meter; self.log = log; self.verifyTolerance = verifyTolerance
    }

    public func run(_ plan: CleanupPlan, approval: CleanupApproval?, cancel: CancellationSignal = .none) async throws -> CleanupReport {
        if plan.requiresExplicitApproval {
            guard let approval else { throw CoreError.unsafePlan("Se requiere aprobación explícita") }
            guard approval.planId == plan.id else { throw CoreError.unsafePlan("La aprobación no corresponde a este plan") }
            guard approval.acknowledgedRisk >= plan.maxRisk else { throw CoreError.unsafePlan("La aprobación no cubre el riesgo") }
        }
        let before = try meter.measure()
        let results = cancel.isCancelled ? [] : await executor.execute(plan.items.map(\.item), cancel: cancel)
        let after = try meter.measure()
        let deleted = results.filter { $0.outcome == .deleted }.reduce(0) { $0 + $1.item.sizeBytes }
        let trashed = results.filter { $0.outcome == .movedToTrash }.reduce(0) { $0 + $1.item.sizeBytes }
        let freed = after.freeBytes - before.freeBytes
        let anyFailed = results.contains { $0.outcome == .failed }
        let status: CleanupStatus
        if cancel.isCancelled && !results.contains(where: { $0.outcome == .deleted || $0.outcome == .movedToTrash }) { status = .cancelled }
        else if deleted == 0 && trashed == 0 { status = anyFailed ? .partialFailure : .nothingDeleted }
        else if anyFailed { status = .partialFailure }
        else if deleted > 0 && freed >= Int64(Double(deleted) * verifyTolerance) { status = .verifiedFreed }
        else if deleted == 0 && trashed > 0 { status = .pendingSystemTrash }
        else { status = .unverified }
        let report = CleanupReport(planId: plan.id, requestedCount: plan.count, requestedBytes: plan.totalBytes, deletedBytes: deleted,
                                   trashedBytes: trashed, results: results, before: before, after: after, status: status)
        log(report)
        return report
    }
}

public enum ByteFormat {
    /// Base 1000, como Ajustes de iOS.
    public static func format(_ bytes: Int64, locale: Locale = .current) -> String {
        if bytes < 0 { return "-" + format(-bytes, locale: locale) }
        if bytes < 1000 { return "\(bytes) B" }
        let units = ["B", "KB", "MB", "GB", "TB"]
        var value = Double(bytes), unit = 0
        while value >= 1000 && unit < units.count - 1 { value /= 1000; unit += 1 }
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.minimumFractionDigits = value >= 100 ? 0 : 1
        f.maximumFractionDigits = value >= 100 ? 0 : 1
        return "\(f.string(from: NSNumber(value: value)) ?? String(value)) \(units[unit])"
    }
}
