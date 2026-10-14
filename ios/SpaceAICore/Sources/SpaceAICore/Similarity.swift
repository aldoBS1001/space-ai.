import Foundation

public enum Sensitivity: String, CaseIterable, Sendable {
    case strict, normal, relaxed
    public var maxHamming: Int {
        switch self {
        case .strict: return 4
        case .normal: return 8
        case .relaxed: return 12
        }
    }
}

public struct PhotoSignature: Sendable {
    public var item: MediaItem
    public var dHash: UInt64
    public var quality: QualityMetrics?
    public init(item: MediaItem, dHash: UInt64, quality: QualityMetrics?) { self.item = item; self.dHash = dHash; self.quality = quality }
}

public struct SimilarGroup: Sendable {
    public var items: [PhotoSignature]
    public var keeper: PhotoSignature
    public var isBurst: Bool
    public var candidates: [PhotoSignature] { items.filter { $0.item.id != keeper.item.id } }
    public var reclaimableBytes: Int64 { candidates.reduce(0) { $0 + $1.item.sizeBytes } }
}

/// Mismo algoritmo que Android: casi-exactas en toda la biblioteca (BK-tree) + similares
/// dentro de una ventana temporal, con guarda de relación de aspecto. Excluye capturas.
public struct SimilarityGrouper {
    public var sensitivity: Sensitivity
    public var timeWindowMillis: Int64
    public var nearExactBits: Int
    public var aspectTolerance: Double

    public init(sensitivity: Sensitivity = .normal, timeWindowMillis: Int64 = 10 * 60 * 1000, nearExactBits: Int = 2, aspectTolerance: Double = 0.08) {
        self.sensitivity = sensitivity; self.timeWindowMillis = timeWindowMillis
        self.nearExactBits = nearExactBits; self.aspectTolerance = aspectTolerance
    }

    public func group(_ signatures: [PhotoSignature], cancel: CancellationSignal = .none) throws -> [SimilarGroup] {
        let eligible = signatures.filter { !$0.item.isScreenshot }
        guard eligible.count > 1 else { return [] }
        var uf = UnionFind(eligible.count)
        let tree = BKTree()
        for (i, sig) in eligible.enumerated() {
            if i % 256 == 0 { try cancel.throwIfCancelled() }
            for j in tree.query(sig.dHash, radius: nearExactBits) where compatible(sig, eligible[j]) { uf.union(i, j) }
            tree.add(sig.dHash, i)
        }
        let dated = eligible.indices.filter { eligible[$0].item.dateTaken > 0 }.sorted { eligible[$0].item.dateTaken < eligible[$1].item.dateTaken }
        var start = 0
        for k in dated.indices {
            if k % 256 == 0 { try cancel.throwIfCancelled() }
            let i = dated[k]
            let ti = eligible[i].item.dateTaken
            while ti - eligible[dated[start]].item.dateTaken > timeWindowMillis { start += 1 }
            for m in start..<k {
                let j = dated[m]
                if PerceptualHash.hamming(eligible[i].dHash, eligible[j].dHash) <= sensitivity.maxHamming && compatible(eligible[i], eligible[j]) {
                    uf.union(i, j)
                }
            }
        }
        let bursts = Dictionary(grouping: eligible.indices.filter { eligible[$0].item.burstId != nil }, by: { eligible[$0].item.burstId! })
        for idx in bursts.values where idx.count > 1 { for k in 1..<idx.count { uf.union(idx[0], idx[k]) } }

        var roots: [Int: [Int]] = [:]
        for i in eligible.indices { roots[uf.find(i), default: []].append(i) }
        return roots.values.filter { $0.count > 1 }.map { idx in
            let members = idx.map { eligible[$0] }
            let ids = Set(members.map { $0.item.burstId })
            let burst = members.allSatisfy { $0.item.burstId != nil } && ids.count == 1
            return SimilarGroup(items: members, keeper: BestShotSelector.choose(members), isBurst: burst)
        }.sorted { $0.reclaimableBytes > $1.reclaimableBytes }
    }

    private func compatible(_ a: PhotoSignature, _ b: PhotoSignature) -> Bool {
        let ra = a.item.aspectRatio, rb = b.item.aspectRatio
        if ra <= 0 || rb <= 0 { return true }
        return abs(ra - rb) / max(ra, rb) <= aspectTolerance
    }
}

public enum BestShotSelector {
    public static func choose(_ members: [PhotoSignature]) -> PhotoSignature {
        members.min { a, b in
            if a.item.isFavorite != b.item.isFavorite { return a.item.isFavorite }
            let sa = a.quality?.score ?? 0, sb = b.quality?.score ?? 0
            if sa != sb { return sa > sb }
            if a.item.pixelCount != b.item.pixelCount { return a.item.pixelCount > b.item.pixelCount }
            if a.item.sizeBytes != b.item.sizeBytes { return a.item.sizeBytes > b.item.sizeBytes }
            return a.item.id < b.item.id
        }!
    }
}

struct UnionFind {
    private var parent: [Int]
    private var rank: [Int]
    init(_ n: Int) { parent = Array(0..<n); rank = Array(repeating: 0, count: n) }
    mutating func find(_ x: Int) -> Int {
        var r = x
        while parent[r] != r { r = parent[r] }
        var c = x
        while parent[c] != r { let n = parent[c]; parent[c] = r; c = n }
        return r
    }
    mutating func union(_ a: Int, _ b: Int) {
        let ra = find(a), rb = find(b)
        guard ra != rb else { return }
        if rank[ra] < rank[rb] { parent[ra] = rb } else if rank[ra] > rank[rb] { parent[rb] = ra } else { parent[rb] = ra; rank[ra] += 1 }
    }
}

/// BK-tree sobre distancia de Hamming.
final class BKTree {
    private final class Node {
        let hash: UInt64; let index: Int
        var children: [Int: Node] = [:]
        var duplicates: [Int] = []
        init(_ h: UInt64, _ i: Int) { hash = h; index = i }
    }
    private var root: Node?

    func add(_ hash: UInt64, _ index: Int) {
        guard var node = root else { root = Node(hash, index); return }
        while true {
            let d = PerceptualHash.hamming(hash, node.hash)
            if d == 0 { node.duplicates.append(index); return }
            if let child = node.children[d] { node = child } else { node.children[d] = Node(hash, index); return }
        }
    }

    func query(_ hash: UInt64, radius: Int) -> [Int] {
        guard let root else { return [] }
        var out: [Int] = []
        var stack = [root]
        while let n = stack.popLast() {
            let d = PerceptualHash.hamming(hash, n.hash)
            if d <= radius { out.append(n.index); out.append(contentsOf: n.duplicates) }
            for (k, c) in n.children where k >= d - radius && k <= d + radius { stack.append(c) }
        }
        return out
    }
}
