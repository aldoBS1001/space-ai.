import Foundation
import Vision
import Photos
import UIKit
import SpaceAICore

enum ScanPhase: String { case storage, listing, photos, duplicates, videos, files, summary }

struct ScanProgress: Equatable {
    var phase: ScanPhase
    var done: Int
    var total: Int
    var fraction: Double { total > 0 ? Double(done) / Double(total) : 0 }
}

/// Segunda opinión con Vision (VNFeaturePrint): solo puede QUITAR miembros de un grupo similar,
/// nunca agregar, así reduce falsos positivos del hash perceptual.
struct VisionRefiner {
    var maxDistance: Float = 0.55

    func refine(_ groups: [SimilarGroup], library: PhotoLibrary) async -> [SimilarGroup] {
        var out: [SimilarGroup] = []
        for g in groups where !g.isBurst {
            guard let keeperPrint = await featurePrint(g.keeper.item, library) else { out.append(g); continue }
            var kept = [g.keeper]
            for m in g.candidates {
                guard let p = await featurePrint(m.item, library) else { kept.append(m); continue }
                var d: Float = 0
                if (try? keeperPrint.computeDistance(&d, to: p)) != nil, d <= maxDistance { kept.append(m) }
            }
            if kept.count > 1 { out.append(SimilarGroup(items: kept, keeper: g.keeper, isBurst: false)) }
        }
        return out + groups.filter(\.isBurst)
    }

    private func featurePrint(_ item: MediaItem, _ library: PhotoLibrary) async -> VNFeaturePrintObservation? {
        guard let img = await library.thumbnail(for: item.id, side: 300), let cg = img.cgImage else { return nil }
        let req = VNGenerateImageFeaturePrintRequest()
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        guard (try? handler.perform([req])) != nil else { return nil }
        return req.results?.first as? VNFeaturePrintObservation
    }
}

/// Análisis completo, todo en el dispositivo. Cancelable; hashes en caché para reanudar.
final class ScanService: @unchecked Sendable {
    let library = PhotoLibrary()
    let folders = AuthorizedFolders()
    let cache: HashCache = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return HashCache(url: dir.appendingPathComponent("hashcache.tsv"))
    }()
    lazy var compressor = VideoCompressorIOS(library: library)

    /// Firma visual de una foto (con caché); nil si la miniatura no se puede decodificar o solo está en iCloud.
    private func signatureTask(_ p: MediaItem) -> @Sendable () async throws -> PhotoSignature? {
        let library = self.library, cache = self.cache
        return {
            try Task.checkCancellation()
            if let h = cache.get(p, .dhash).flatMap(PerceptualHash.fromHex), let q = cache.get(p, .blur).flatMap(QualityMetrics.decode) {
                return PhotoSignature(item: p, dHash: h, quality: q)
            }
            guard let gray = await library.gray(for: p) else { return nil }
            let h = PerceptualHash.dHash(gray), q = QualityAnalyzer.analyze(gray)
            cache.put(p, .dhash, PerceptualHash.toHex(h)); cache.put(p, .blur, q.encode())
            return PhotoSignature(item: p, dHash: h, quality: q)
        }
    }

    func scan(access: AccessReport, sensitivity: Sensitivity, conservative: Bool, preset: CompressionPreset,
              progress: @escaping @Sendable (ScanProgress) -> Void) async throws -> ScanResult {
        let cancel = CancellationSignal { Task.isCancelled }
        defer { try? cache.flush() }
        progress(ScanProgress(phase: .storage, done: 0, total: 1))
        let storage = try IOSStorageMeter().measure()

        progress(ScanProgress(phase: .listing, done: 0, total: 1))
        let canRead = access.photos == .full || access.photos == .limited
        let media = canRead ? try library.fetchAll(cancel: cancel) : []
        let photos = media.filter { $0.kind == .photo }
        let videos = media.filter { $0.kind == .video }
        progress(ScanProgress(phase: .files, done: 0, total: 1))
        let files = try folders.scan(cancel: cancel)

        // Firmas visuales con concurrencia limitada (3 en paralelo).
        var signatures: [PhotoSignature] = []
        var done = 0
        try await withThrowingTaskGroup(of: PhotoSignature?.self) { group in
            var next = 0
            for _ in 0..<min(3, photos.count) { group.addTask(operation: signatureTask(photos[next])); next += 1 }
            while let r = try await group.next() {
                if let r { signatures.append(r) }
                done += 1
                progress(ScanProgress(phase: .photos, done: done, total: photos.count))
                if next < photos.count { group.addTask(operation: signatureTask(photos[next])); next += 1 }
            }
        }

        // Duplicados exactos: medios y documentos por separado (un mismo archivo no puede ser "su propio duplicado").
        let mediaFinder = ExactDuplicateFinder(source: PhotoContentSource(library: library), cache: cache)
        let exactMedia = try mediaFinder.find(photos + videos, cancel: cancel) { d, t in progress(ScanProgress(phase: .duplicates, done: d, total: t)) }
        let docs = files.filter { $0.kind == .document }
        let exactDocs = try ExactDuplicateFinder(source: folders.contentSource(), cache: cache).find(docs, cancel: cancel)
        let exact = ExactDuplicateResult(groups: exactMedia.groups + exactDocs.groups, unreadable: exactMedia.unreadable + exactDocs.unreadable,
                                         bytesHashed: exactMedia.bytesHashed + exactDocs.bytesHashed)
        try? cache.flush()

        var similar = try SimilarityGrouper(sensitivity: sensitivity).group(signatures, cancel: cancel)
        similar = await VisionRefiner().refine(similar, library: library)

        let candidates = videos.filter { $0.sizeBytes >= 30 * 1024 * 1024 }
        var infos: [String: VideoInfo] = [:]
        for (i, v) in candidates.enumerated() {
            try Task.checkCancellation()
            if let info = await compressor.info(for: v) { infos[v.id] = info }
            progress(ScanProgress(phase: .videos, done: i + 1, total: candidates.count))
        }

        progress(ScanProgress(phase: .summary, done: 0, total: 1))
        var rules = ScanRules()
        rules.quality = conservative ? .conservative : .default
        rules.compressionPreset = preset
        return ScanAnalyzer(rules: rules).analyze(
            storage: storage, access: access, items: photos + videos + docs, exact: exact, similar: similar,
            quality: Dictionary(signatures.compactMap { s in s.quality.map { (s.item.id, $0) } }, uniquingKeysWith: { a, _ in a }),
            videoInfo: infos, appTempBytes: AppTempFiles.sizeBytes())
    }
}
