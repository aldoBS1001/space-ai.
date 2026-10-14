import Foundation

public struct ScanRules: Sendable {
    public var largeVideoBytes: Int64 = 200 * 1024 * 1024
    public var largeFileBytes: Int64 = 50 * 1024 * 1024
    public var oldLargePhotoBytes: Int64 = 8 * 1024 * 1024
    public var oldAgeMillis: Int64 = 2 * 365 * 24 * 3600 * 1000
    public var quality: QualityThresholds = .default
    public var compressionPreset: CompressionPreset = .balanced
    public init() {}
}

public struct CategoryResult: Sendable {
    public var category: CleanupCategory
    public var items: [MediaItem]
    public var risk: RiskLevel
    public var bytes: Int64
    public var isEstimate: Bool
}

public struct Recommendation: Sendable {
    public var category: CleanupCategory
    public var count: Int
    public var bytes: Int64
    public var risk: RiskLevel
    public var isEstimate: Bool
}

public struct ScanResult: Sendable {
    public var storage: StorageSnapshot
    public var access: AccessReport
    public var photoCount: Int, photoBytes: Int64
    public var videoCount: Int, videoBytes: Int64
    public var fileCount: Int, fileBytes: Int64
    public var appTempBytes: Int64
    public var categories: [CategoryResult]
    public var exactDuplicates: [DuplicateGroup]
    public var similarGroups: [SimilarGroup]
    public var unreadable: [MediaItem]
    /// Estimación, nunca espacio liberado.
    public var potentiallyRecoverableBytes: Int64
    public var recommendations: [Recommendation]
    public func category(_ c: CleanupCategory) -> CategoryResult? { categories.first { $0.category == c } }
}

public struct ScanAnalyzer {
    public var rules: ScanRules
    public var now: () -> Int64
    public init(rules: ScanRules = ScanRules(), now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.rules = rules; self.now = now
    }

    public func analyze(storage: StorageSnapshot, access: AccessReport, items: [MediaItem], exact: ExactDuplicateResult,
                        similar: [SimilarGroup], quality: [String: QualityMetrics], videoInfo: [String: VideoInfo] = [:],
                        appTempBytes: Int64 = 0) -> ScanResult {
        let now = now()
        let photos = items.filter { $0.kind == .photo }
        let videos = items.filter { $0.kind == .video }
        let files = items.filter { $0.kind == .document || $0.kind == .other }
        let t = rules.quality
        var cats: [CategoryResult] = []

        func add(_ c: CleanupCategory, _ list: [MediaItem], _ risk: RiskLevel, bytes: Int64? = nil, estimate: Bool = false) {
            var seen = Set<String>()
            let unique = list.filter { seen.insert($0.id).inserted }.sorted { $0.sizeBytes > $1.sizeBytes }
            guard !unique.isEmpty else { return }
            cats.append(CategoryResult(category: c, items: unique, risk: risk, bytes: bytes ?? unique.reduce(0) { $0 + $1.sizeBytes }, isEstimate: estimate))
        }

        let photoDups = exact.groups.filter { $0.items.allSatisfy { $0.kind == .photo } }
        let videoDups = exact.groups.filter { $0.items.allSatisfy { $0.kind == .video } }
        let fileDups = exact.groups.filter { !$0.items.contains { $0.kind == .photo || $0.kind == .video } }
        add(.exactDuplicatePhotos, photoDups.flatMap { $0.duplicates }, .low)
        add(.duplicateVideos, videoDups.flatMap { $0.duplicates }, .low)
        add(.duplicateFiles, fileDups.flatMap { $0.duplicates }, .low)

        let exactIds = Set(exact.groups.flatMap { $0.duplicates }.map { $0.id })
        add(.similarPhotos, similar.filter { !$0.isBurst }.flatMap { $0.candidates.map { $0.item } }.filter { !exactIds.contains($0.id) }, .medium)
        add(.burstPhotos, similar.filter { $0.isBurst }.flatMap { $0.candidates.map { $0.item } }, .medium)

        let nonFav = photos.filter { !$0.isFavorite }
        add(.screenshots, nonFav.filter { $0.isScreenshot }, .medium)
        let keepers = Set(similar.map { $0.keeper.item.id })
        let pool = nonFav.filter { !$0.isScreenshot && !keepers.contains($0.id) }
        add(.blurryPhotos, pool.filter { quality[$0.id]?.isBlurry(t) == true }, .medium)
        add(.darkPhotos, pool.filter { quality[$0.id]?.isDark(t) == true }, .medium)
        add(.lowQualityPhotos, pool.filter { ($0.pixelCount > 0 && $0.pixelCount < t.lowQualityMaxPixels) || quality[$0.id]?.isOverexposed(t) == true }, .medium)
        add(.oldLargePhotos, nonFav.filter { $0.sizeBytes >= rules.oldLargePhotoBytes && $0.dateTaken > 0 && $0.dateTaken <= now - rules.oldAgeMillis }, .high)

        add(.largeVideos, videos.filter { $0.sizeBytes >= rules.largeVideoBytes }, .high)
        add(.screenRecordings, videos.filter { $0.isScreenRecording }, .medium)
        add(.oldVideos, videos.filter { $0.dateTaken > 0 && $0.dateTaken <= now - rules.oldAgeMillis }, .high)

        let compressible: [(MediaItem, Int64)] = videos.compactMap { v in
            guard let info = videoInfo[v.id], let e = try? VideoCompressionPlanner.estimate(info, preset: rules.compressionPreset), e.worthIt else { return nil }
            return (v, e.estimatedSavingsBytes)
        }
        add(.compressibleVideos, compressible.map { $0.0 }, .medium, bytes: compressible.reduce(0) { $0 + $1.1 }, estimate: true)
        add(.largeFiles, files.filter { $0.sizeBytes >= rules.largeFileBytes }, .high)

        let deletionCats: Set<CleanupCategory> = [.exactDuplicatePhotos, .duplicateVideos, .duplicateFiles, .similarPhotos, .burstPhotos,
                                                  .screenshots, .blurryPhotos, .darkPhotos, .lowQualityPhotos, .screenRecordings]
        var flagged: [String: MediaItem] = [:]
        for c in cats where deletionCats.contains(c.category) { for i in c.items { flagged[i.id] = i } }
        let compressionOnly = compressible.filter { flagged[$0.0.id] == nil }.reduce(0) { $0 + $1.1 }
        let recoverable = flagged.values.reduce(0) { $0 + $1.sizeBytes } + compressionOnly + appTempBytes

        return ScanResult(storage: storage, access: access,
                          photoCount: photos.count, photoBytes: photos.reduce(0) { $0 + $1.sizeBytes },
                          videoCount: videos.count, videoBytes: videos.reduce(0) { $0 + $1.sizeBytes },
                          fileCount: files.count, fileBytes: files.reduce(0) { $0 + $1.sizeBytes },
                          appTempBytes: appTempBytes, categories: cats, exactDuplicates: exact.groups, similarGroups: similar,
                          unreadable: exact.unreadable, potentiallyRecoverableBytes: recoverable,
                          recommendations: RecommendationEngine.build(cats, appTempBytes: appTempBytes))
    }
}

public enum RecommendationEngine {
    public static let minBytes: Int64 = 5 * 1024 * 1024
    public static func build(_ cats: [CategoryResult], appTempBytes: Int64) -> [Recommendation] {
        var recs = cats.filter { $0.bytes >= minBytes }.map {
            Recommendation(category: $0.category, count: $0.items.count, bytes: $0.bytes, risk: $0.risk, isEstimate: $0.isEstimate)
        }
        if appTempBytes >= 1024 * 1024 {
            recs.append(Recommendation(category: .appTempFiles, count: 0, bytes: appTempBytes, risk: .low, isEstimate: false))
        }
        return recs.sorted { $0.risk != $1.risk ? $0.risk < $1.risk : $0.bytes > $1.bytes }
    }
}
