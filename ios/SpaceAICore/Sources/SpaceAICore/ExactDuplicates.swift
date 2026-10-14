import Foundation
import CryptoKit

/// Fuente de contenido: la app la implementa con PHAssetResourceManager o FileHandle.
/// `read` entrega bloques en orden; devolver `false` desde el bloque detiene la lectura.
/// Lanza si el contenido no se puede leer (corrupto, solo en iCloud, sin permiso).
public protocol ContentSource {
    func read(_ item: MediaItem, chunk: (Data) throws -> Bool) throws
}

public enum Sha256 {
    public static func of(_ data: Data) -> String { hex(SHA256.hash(data: data)) }

    /// Hash en streaming, limitado a `limit` bytes.
    public static func of(source: ContentSource, item: MediaItem, limit: Int64 = .max, cancel: CancellationSignal = .none) throws -> String {
        var hasher = SHA256()
        var remaining = limit
        try source.read(item) { chunk in
            try cancel.throwIfCancelled()
            if remaining <= 0 { return false }
            let take = Int(min(Int64(chunk.count), remaining))
            hasher.update(data: take == chunk.count ? chunk : chunk.prefix(take))
            remaining -= Int64(take)
            return remaining > 0
        }
        return hex(hasher.finalize())
    }

    private static func hex<D: Sequence>(_ d: D) -> String where D.Element == UInt8 {
        d.map { String(format: "%02x", $0) }.joined()
    }
}

public struct DuplicateGroup: Sendable {
    public var hash: String
    public var items: [MediaItem]
    public var keeper: MediaItem
    public init(hash: String, items: [MediaItem], keeper: MediaItem) { self.hash = hash; self.items = items; self.keeper = keeper }
    public var duplicates: [MediaItem] { items.filter { $0.id != keeper.id } }
    public var reclaimableBytes: Int64 { duplicates.reduce(0) { $0 + $1.sizeBytes } }
}

public struct ExactDuplicateResult: Sendable {
    public var groups: [DuplicateGroup]
    public var unreadable: [MediaItem]
    public var bytesHashed: Int64
    public init(groups: [DuplicateGroup], unreadable: [MediaItem], bytesHashed: Int64) {
        self.groups = groups; self.unreadable = unreadable; self.bytesHashed = bytesHashed
    }
}

/// Detección exacta en tres etapas: tamaño -> SHA-256 de 64 KB -> SHA-256 completo.
public final class ExactDuplicateFinder {
    private let source: ContentSource
    private let cache: HashCache?
    private let partialBytes: Int64

    public init(source: ContentSource, cache: HashCache? = nil, partialBytes: Int64 = 64 * 1024) {
        self.source = source; self.cache = cache; self.partialBytes = partialBytes
    }

    private func hash(_ item: MediaItem, limit: Int64, kind: HashCache.Kind, cancel: CancellationSignal) throws -> String? {
        if let c = cache?.get(item, kind) { return c }
        do {
            let h = try Sha256.of(source: source, item: item, limit: limit, cancel: cancel)
            cache?.put(item, kind, h)
            return h
        } catch CoreError.cancelled {
            throw CoreError.cancelled
        } catch {
            return nil
        }
    }

    public func find(_ items: [MediaItem], cancel: CancellationSignal = .none,
                     progress: (Int, Int) -> Void = { _, _ in }) throws -> ExactDuplicateResult {
        var unreadable: [String: MediaItem] = [:]
        var bytesHashed: Int64 = 0
        let bySize = Dictionary(grouping: items.filter { $0.sizeBytes > 0 }, by: { $0.sizeBytes }).values.filter { $0.count > 1 }
        let total = bySize.reduce(0) { $0 + $1.count }
        var done = 0
        var groups: [DuplicateGroup] = []

        for sameSize in bySize {
            var byPartial: [String: [MediaItem]] = [:]
            var order: [String] = []
            for item in sameSize {
                try cancel.throwIfCancelled()
                if let h = try hash(item, limit: partialBytes, kind: .partial, cancel: cancel) {
                    bytesHashed += min(partialBytes, item.sizeBytes)
                    if byPartial[h] == nil { order.append(h) }
                    byPartial[h, default: []].append(item)
                } else {
                    unreadable[item.id] = item
                }
                done += 1; progress(done, total)
            }
            for partialHash in order {
                guard let candidates = byPartial[partialHash], candidates.count > 1 else { continue }
                if candidates[0].sizeBytes <= partialBytes {
                    groups.append(DuplicateGroup(hash: partialHash, items: candidates, keeper: KeeperPolicy.chooseExactKeeper(candidates)))
                    continue
                }
                var byFull: [String: [MediaItem]] = [:]
                var fullOrder: [String] = []
                for item in candidates {
                    try cancel.throwIfCancelled()
                    if let h = try hash(item, limit: .max, kind: .full, cancel: cancel) {
                        bytesHashed += item.sizeBytes
                        if byFull[h] == nil { fullOrder.append(h) }
                        byFull[h, default: []].append(item)
                    } else {
                        unreadable[item.id] = item
                    }
                }
                for h in fullOrder where byFull[h]!.count > 1 {
                    groups.append(DuplicateGroup(hash: h, items: byFull[h]!, keeper: KeeperPolicy.chooseExactKeeper(byFull[h]!)))
                }
            }
        }
        return ExactDuplicateResult(groups: groups.sorted { $0.reclaimableBytes > $1.reclaimableBytes },
                                    unreadable: Array(unreadable.values), bytesHashed: bytesHashed)
    }
}

public enum KeeperPolicy {
    /// Favorita > más antigua > nombre más corto > id.
    public static func chooseExactKeeper(_ group: [MediaItem]) -> MediaItem {
        group.min { a, b in
            if a.isFavorite != b.isFavorite { return a.isFavorite }
            let ta = a.dateTaken > 0 ? a.dateTaken : .max, tb = b.dateTaken > 0 ? b.dateTaken : .max
            if ta != tb { return ta < tb }
            if a.displayName.count != b.displayName.count { return a.displayName.count < b.displayName.count }
            return a.id < b.id
        }!
    }
}
