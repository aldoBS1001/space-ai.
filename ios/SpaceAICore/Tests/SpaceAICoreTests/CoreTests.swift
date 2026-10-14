import XCTest
@testable import SpaceAICore

// MARK: - Fixtures

func photo(_ id: String, size: Int64 = 3_000_000, taken: Int64 = 1_700_000_000_000, w: Int = 4032, h: Int = 3024,
           favorite: Bool = false, screenshot: Bool = false, burst: String? = nil) -> MediaItem {
    MediaItem(id: id, displayName: "IMG_\(id).jpg", sizeBytes: size, kind: .photo, mimeType: "image/jpeg", dateTaken: taken,
              dateModified: taken, width: w, height: h, isScreenshot: screenshot, isFavorite: favorite, burstId: burst)
}

func video(_ id: String, size: Int64, taken: Int64 = 1_700_000_000_000, recording: Bool = false) -> MediaItem {
    MediaItem(id: id, displayName: "VID_\(id).mov", sizeBytes: size, kind: .video, dateTaken: taken, dateModified: taken,
              width: 1920, height: 1080, durationMillis: 60_000, isScreenRecording: recording)
}

final class MemorySource: ContentSource {
    let data: [String: Data]
    var opens = 0
    init(_ data: [String: Data]) { self.data = data }
    struct Unreadable: Error {}
    func read(_ item: MediaItem, chunk: (Data) throws -> Bool) throws {
        guard let d = data[item.id] else { throw Unreadable() }
        opens += 1
        var offset = 0
        while offset < d.count {
            let end = min(offset + 16_384, d.count)
            if try !chunk(d.subdata(in: offset..<end)) { return }
            offset = end
        }
    }
}

/// Generador determinista (LCG) para no depender del RNG del sistema.
struct LCG { var s: UInt64; mutating func next() -> UInt64 { s = s &* 6364136223846793005 &+ 1442695040888963407; return s }
    mutating func int(_ lo: Int, _ hi: Int) -> Int { lo + Int(next() >> 33) % (hi - lo) } }

func randomData(_ seed: UInt64, _ n: Int) -> Data { var g = LCG(s: seed); return Data((0..<n).map { _ in UInt8(truncatingIfNeeded: g.next() >> 40) }) }

func scene(_ seed: UInt64, _ w: Int = 320, _ h: Int = 240) -> GrayImage {
    var g = LCG(s: seed)
    let blobs = (0..<12).map { _ in (g.int(0, w), g.int(0, h), g.int(20, 90), Float(g.int(30, 230))) }
    var l = [Float](repeating: 0, count: w * h)
    for y in 0..<h { for x in 0..<w {
        var v: Float = 40 + 140 * Float(x) / Float(w)
        for b in blobs { let dx = x - b.0, dy = y - b.1; if dx * dx + dy * dy < b.2 * b.2 { v = b.3 } }
        l[y * w + x] = v
    } }
    return try! GrayImage(width: w, height: h, luma: l)
}

func mapImg(_ img: GrayImage, _ f: (Float) -> Float) -> GrayImage {
    try! GrayImage(width: img.width, height: img.height, luma: img.luma.map { min(255, max(0, f($0))) })
}

func boxBlur(_ img: GrayImage, _ r: Int) -> GrayImage {
    var out = [Float](repeating: 0, count: img.luma.count)
    for y in 0..<img.height { for x in 0..<img.width {
        var s: Float = 0, n: Float = 0
        for dy in -r...r { for dx in -r...r {
            let xx = x + dx, yy = y + dy
            if xx >= 0 && xx < img.width && yy >= 0 && yy < img.height { s += img[xx, yy]; n += 1 }
        } }
        out[y * img.width + x] = s / n
    } }
    return try! GrayImage(width: img.width, height: img.height, luma: out)
}

struct FakeMeter: StorageMeter {
    final class Box { var values: [Int64]; init(_ v: [Int64]) { values = v } }
    let box: Box
    init(_ v: Int64...) { box = Box(v) }
    func measure() throws -> StorageSnapshot { try StorageSnapshot(totalBytes: 128_000_000_000, freeBytes: box.values.removeFirst(), takenAt: 0) }
}

struct FixedExecutor: CleanupExecutor {
    let outcome: (MediaItem) -> ItemOutcome
    func execute(_ items: [MediaItem], cancel: CancellationSignal) async -> [ItemResult] { items.map { ItemResult(item: $0, outcome: outcome($0)) } }
}

let MB: Int64 = 1024 * 1024

// MARK: - Tests

final class Sha256Tests: XCTestCase {
    func testKnownVector() {
        XCTAssertEqual(Sha256.of(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
    func testStreamingMatchesInMemory() throws {
        let d = randomData(1, 1_000_003)
        XCTAssertEqual(try Sha256.of(source: MemorySource(["x": d]), item: photo("x")), Sha256.of(d))
    }
    func testLimitHashesPrefix() throws {
        let d = randomData(2, 200_000)
        XCTAssertEqual(try Sha256.of(source: MemorySource(["x": d]), item: photo("x"), limit: 65_536), Sha256.of(d.prefix(65_536)))
    }
}

final class ExactDuplicateTests: XCTestCase {
    func testEmptyLibrary() throws {
        let r = try ExactDuplicateFinder(source: MemorySource([:])).find([])
        XCTAssertTrue(r.groups.isEmpty); XCTAssertEqual(r.bytesHashed, 0)
    }
    func testFindsDuplicatesKeepsOne() throws {
        let a = randomData(1, 300_000)
        let r = try ExactDuplicateFinder(source: MemorySource(["1": a, "2": a, "3": randomData(9, 300_000)]))
            .find([photo("1", size: 300_000), photo("2", size: 300_000), photo("3", size: 300_000)])
        XCTAssertEqual(r.groups.count, 1)
        XCTAssertEqual(Set(r.groups[0].items.map(\.id)), ["1", "2"])
        XCTAssertEqual(r.groups[0].reclaimableBytes, 300_000)
    }
    func testDifferentTailIsNotDuplicate() throws {
        let a = randomData(3, 200_000)
        var b = a; b[199_999] = b[199_999] &+ 1
        let r = try ExactDuplicateFinder(source: MemorySource(["a": a, "b": b])).find([photo("a", size: 200_000), photo("b", size: 200_000)])
        XCTAssertTrue(r.groups.isEmpty)
    }
    func testDifferentSizesNeverOpened() throws {
        let s = MemorySource(["a": Data(count: 10), "b": Data(count: 20)])
        _ = try ExactDuplicateFinder(source: s).find([photo("a", size: 10), photo("b", size: 20)])
        XCTAssertEqual(s.opens, 0)
    }
    func testUnreadableReported() throws {
        let d = randomData(5, 5000)
        let r = try ExactDuplicateFinder(source: MemorySource(["ok1": d, "ok2": d]))
            .find([photo("ok1", size: 5000), photo("ok2", size: 5000), photo("missing", size: 5000)])
        XCTAssertEqual(r.unreadable.map(\.id), ["missing"])
        XCTAssertEqual(r.groups.count, 1)
    }
    func testFavoriteKept() throws {
        let d = randomData(6, 5000)
        let r = try ExactDuplicateFinder(source: MemorySource(["old": d, "fav": d]))
            .find([photo("old", size: 5000, taken: 1), photo("fav", size: 5000, taken: 999, favorite: true)])
        XCTAssertEqual(r.groups[0].keeper.id, "fav")
    }
    func testCacheAvoidsRereading() throws {
        let d = randomData(7, 150_000)
        let s = MemorySource(["a": d, "b": d]); let cache = HashCache()
        let items = [photo("a", size: 150_000), photo("b", size: 150_000)]
        _ = try ExactDuplicateFinder(source: s, cache: cache).find(items)
        let first = s.opens
        let r = try ExactDuplicateFinder(source: s, cache: cache).find(items)
        XCTAssertEqual(s.opens, first); XCTAssertEqual(r.groups.count, 1)
    }
    func testCancellation() {
        let d = randomData(8, 100)
        XCTAssertThrowsError(try ExactDuplicateFinder(source: MemorySource(["a": d, "b": d]))
            .find([photo("a", size: 100), photo("b", size: 100)], cancel: CancellationSignal { true }))
    }
    func testCachePersistsAtomically() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("c.tsv")
        let c = HashCache(url: url); c.put(photo("x", size: 10), .full, "abc"); try c.flush()
        XCTAssertEqual(HashCache(url: url).get(photo("x", size: 10), .full), "abc")
    }
}

final class ImageTests: XCTestCase {
    func testResizedNearlyIdentical() {
        let a = scene(1, 640, 480)
        XCTAssertLessThanOrEqual(PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(a.resized(320, 240))), 2)
    }
    func testMonotonicBrightnessIsNearExact() {
        let a = scene(31)
        XCTAssertLessThanOrEqual(PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(mapImg(a) { $0 * 0.8 + 30 })), 2)
    }
    func testDifferentScenesFar() {
        var minD = 64
        for i in 0..<20 { for j in (i + 1)..<20 {
            minD = min(minD, PerceptualHash.hamming(PerceptualHash.dHash(scene(UInt64(100 + i))), PerceptualHash.dHash(scene(UInt64(100 + j)))))
        } }
        XCTAssertGreaterThan(minD, Sensitivity.normal.maxHamming)
    }
    func testHexRoundTrip() {
        for h: UInt64 in [0, .max, 0x0123456789abcdef] { XCTAssertEqual(PerceptualHash.fromHex(PerceptualHash.toHex(h)), h) }
    }
    func testBlurDetection() {
        var g = LCG(s: 3)
        let textured = mapImg(scene(3, 512, 384)) { $0 + Float(g.int(-25, 25)) }
        let blurred = boxBlur(textured, 4)
        let qs = QualityAnalyzer.analyze(textured), qb = QualityAnalyzer.analyze(blurred)
        XCTAssertGreaterThan(qs.sharpness, qb.sharpness * 5)
        XCTAssertFalse(qs.isBlurry()); XCTAssertTrue(qb.isBlurry())
    }
    func testDarkDetection() {
        XCTAssertTrue(QualityAnalyzer.analyze(mapImg(scene(4)) { $0 * 0.08 }).isDark())
        XCTAssertFalse(QualityAnalyzer.analyze(scene(4)).isDark())
    }
    func testMetricsCodec() {
        let m = QualityMetrics(sharpness: 12.5, meanLuma: 100, darkFraction: 0.1, brightFraction: 0.2)
        XCTAssertEqual(QualityMetrics.decode(m.encode()), m); XCTAssertNil(QualityMetrics.decode("roto"))
    }
}

final class SimilarityTests: XCTestCase {
    func sig(_ id: String, _ img: GrayImage, taken: Int64 = 1_700_000_000_000, screenshot: Bool = false, burst: String? = nil, w: Int = 4032, h: Int = 3024) -> PhotoSignature {
        PhotoSignature(item: photo(id, taken: taken, w: w, h: h, screenshot: screenshot, burst: burst), dHash: PerceptualHash.dHash(img), quality: QualityAnalyzer.analyze(img))
    }
    func variant(_ base: GrayImage, _ lo: Int, _ hi: Int) -> GrayImage {
        let h0 = PerceptualHash.dHash(base)
        for seed in 0..<500 {
            var g = LCG(s: UInt64(seed)); let amp = 5 + seed % 60
            let v = mapImg(base) { $0 + Float(g.int(-amp, amp + 1)) }
            let d = PerceptualHash.hamming(h0, PerceptualHash.dHash(v))
            if d >= lo && d <= hi { return v }
        }
        XCTFail("sin variante"); return base
    }
    func testGroupsAndKeepsSharpest() throws {
        var g = LCG(s: 10)
        let sharp = mapImg(scene(10, 400, 300)) { $0 + Float(g.int(-15, 15)) }
        let groups = try SimilarityGrouper().group([sig("soft", boxBlur(sharp, 2)), sig("sharp", sharp, taken: 1_700_000_005_000), sig("other", scene(77, 400, 300))])
        XCTAssertEqual(groups.count, 1); XCTAssertEqual(groups[0].keeper.item.id, "sharp")
        XCTAssertEqual(groups[0].candidates.map(\.item.id), ["soft"])
    }
    func testScreenshotsExcluded() throws {
        let i = scene(11)
        XCTAssertTrue(try SimilarityGrouper().group([sig("a", i, screenshot: true), sig("b", i, screenshot: true)]).isEmpty)
    }
    func testDistantDates() throws {
        let a = scene(12, 400, 300), b = variant(a, 3, 8)
        let far: Int64 = 1_700_000_000_000 + 400 * 24 * 3600 * 1000
        XCTAssertEqual(try SimilarityGrouper().group([sig("a", a), sig("b", b, taken: 1_700_000_060_000)]).count, 1)
        XCTAssertTrue(try SimilarityGrouper().group([sig("a", a), sig("b", b, taken: far)]).isEmpty)
        XCTAssertEqual(try SimilarityGrouper().group([sig("a", a), sig("c", a, taken: far)]).count, 1)
    }
    func testAspectGuard() throws {
        let a = scene(13)
        XCTAssertTrue(try SimilarityGrouper().group([sig("h", a), sig("v", a, w: 3024, h: 4032)]).isEmpty)
    }
    func testBurst() throws {
        let g = try SimilarityGrouper().group([sig("1", scene(20), burst: "B"), sig("2", scene(21), burst: "B"), sig("3", scene(22), burst: "B")])
        XCTAssertEqual(g.count, 1); XCTAssertTrue(g[0].isBurst)
    }
    func testSensitivity() throws {
        let a = scene(30, 400, 300), b = variant(a, Sensitivity.strict.maxHamming + 1, Sensitivity.relaxed.maxHamming)
        XCTAssertTrue(try SimilarityGrouper(sensitivity: .strict).group([sig("a", a), sig("b", b)]).isEmpty)
        XCTAssertEqual(try SimilarityGrouper(sensitivity: .relaxed).group([sig("a", a), sig("b", b)]).count, 1)
    }
    func testBKTreeMatchesBruteForce() {
        var g = LCG(s: 42)
        var hashes = (0..<3000).map { _ in g.next() }
        for i in 0..<300 { hashes[i * 10] = hashes[i * 10 + 1] ^ (1 << (g.next() % 64)) ^ (1 << (g.next() % 64)) }
        let t = BKTree(); for (i, h) in hashes.enumerated() { t.add(h, i) }
        for q in 0..<200 {
            let expected = Set(hashes.indices.filter { PerceptualHash.hamming(hashes[q], hashes[$0]) <= 8 })
            XCTAssertEqual(Set(t.query(hashes[q], radius: 8)), expected)
        }
    }
    func testTenThousandFast() throws {
        var g = LCG(s: 7)
        let sigs = (0..<10_000).map { i in PhotoSignature(item: photo("p\(i)", taken: 1_700_000_000_000 + Int64(i) * 60_000), dHash: g.next(), quality: nil) }
        let t0 = Date()
        _ = try SimilarityGrouper().group(sigs)
        XCTAssertLessThan(Date().timeIntervalSince(t0), 5)
    }
}

final class VideoTests: XCTestCase {
    func testFourK() throws {
        let e = try VideoCompressionPlanner.estimate(VideoInfo(sizeBytes: 400 * MB, durationMillis: 60_000, width: 3840, height: 2160))
        XCTAssertEqual(e.targetWidth, 1280); XCTAssertEqual(e.targetHeight, 720); XCTAssertTrue(e.worthIt)
    }
    func testEfficientNotOffered() throws {
        XCTAssertFalse(try VideoCompressionPlanner.estimate(VideoInfo(sizeBytes: 8 * MB, durationMillis: 60_000, width: 1280, height: 720)).worthIt)
    }
    func testZeroDuration() {
        XCTAssertThrowsError(try VideoCompressionPlanner.estimate(VideoInfo(sizeBytes: 10, durationMillis: 0, width: 10, height: 10)))
    }
    func testValidator() {
        let o = VideoInfo(sizeBytes: 100 * MB, durationMillis: 60_000, width: 1920, height: 1080)
        XCTAssertEqual(CompressionValidator.validate(original: o, output: CompressedOutput(sizeBytes: 30 * MB, durationMillis: 60_400, width: 1280, height: 720, decodable: true)), .valid)
        XCTAssertEqual(CompressionValidator.validate(original: o, output: CompressedOutput(sizeBytes: 30 * MB, durationMillis: 60_000, width: 1280, height: 720, decodable: false)), .invalid(.notDecodable))
        XCTAssertEqual(CompressionValidator.validate(original: o, output: CompressedOutput(sizeBytes: 30 * MB, durationMillis: 50_000, width: 1280, height: 720, decodable: true)), .invalid(.durationMismatch))
        XCTAssertEqual(CompressionValidator.validate(original: o, output: CompressedOutput(sizeBytes: 100 * MB, durationMillis: 60_000, width: 1280, height: 720, decodable: true)), .invalid(.notSmaller))
    }
}

final class AnalyzerAndCleanupTests: XCTestCase {
    let storage = try! StorageSnapshot(totalBytes: 128_000_000_000, freeBytes: 10_000_000_000, takenAt: 0)
    let access = AccessReport(photos: .full, videos: .full, files: .limited)

    func testEmptyIsZero() {
        let r = ScanAnalyzer(now: { 1_800_000_000_000 }).analyze(storage: storage, access: access, items: [], exact: ExactDuplicateResult(groups: [], unreadable: [], bytesHashed: 0), similar: [], quality: [:])
        XCTAssertEqual(r.potentiallyRecoverableBytes, 0); XCTAssertTrue(r.recommendations.isEmpty)
    }
    func testRecoverableCountsOnce() {
        let s = photo("s", size: 10 * MB, screenshot: true), s2 = photo("s2", size: 10 * MB, screenshot: true)
        let g = DuplicateGroup(hash: "h", items: [s, s2], keeper: s)
        let r = ScanAnalyzer(now: { 1_800_000_000_000 }).analyze(storage: storage, access: access, items: [s, s2], exact: ExactDuplicateResult(groups: [g], unreadable: [], bytesHashed: 0), similar: [], quality: [:])
        XCTAssertEqual(r.potentiallyRecoverableBytes, 20 * MB)
        XCTAssertEqual(r.recommendations.first?.risk, .low)
    }
    func testLowRiskVerified() async throws {
        let a = photo("a", size: 100 * MB), b = photo("b", size: 100 * MB)
        let g = DuplicateGroup(hash: "h", items: [a, b], keeper: a)
        let plan = try CleanupPlan.build([PlannedItem(item: b, risk: .low, category: .exactDuplicatePhotos)], exactGroups: [g])
        let r = try await SafeCleanupEngine(executor: FixedExecutor { _ in .deleted }, meter: FakeMeter(1_000 * MB, 1_100 * MB)).run(plan, approval: nil)
        XCTAssertEqual(r.status, .verifiedFreed)
    }
    func testMediumRequiresApproval() async throws {
        let plan = try CleanupPlan.build([PlannedItem(item: photo("b"), risk: .medium, category: .similarPhotos)])
        do { _ = try await SafeCleanupEngine(executor: FixedExecutor { _ in .deleted }, meter: FakeMeter(0, 0)).run(plan, approval: nil); XCTFail() }
        catch CoreError.unsafePlan(_) {}
    }
    func testWrongPlanApprovalRejected() async throws {
        let plan = try CleanupPlan.build([PlannedItem(item: photo("b"), risk: .high, category: .largeFiles)])
        do { _ = try await SafeCleanupEngine(executor: FixedExecutor { _ in .deleted }, meter: FakeMeter(0, 0))
                .run(plan, approval: CleanupApproval(planId: "otro", approvedAt: 0, acknowledgedRisk: .high)); XCTFail() }
        catch CoreError.unsafePlan(_) {}
    }
    func testCannotDeleteAllCopies() {
        let a = photo("a"), b = photo("b")
        XCTAssertThrowsError(try CleanupPlan.build([PlannedItem(item: a, risk: .low, category: .exactDuplicatePhotos), PlannedItem(item: b, risk: .low, category: .exactDuplicatePhotos)],
                                                   exactGroups: [DuplicateGroup(hash: "h", items: [a, b], keeper: a)]))
    }
    func testRecentlyDeletedIsPending() async throws {
        let plan = try CleanupPlan.build([PlannedItem(item: photo("b"), risk: .low, category: .exactDuplicatePhotos)])
        let r = try await SafeCleanupEngine(executor: FixedExecutor { _ in .movedToTrash }, meter: FakeMeter(5, 5)).run(plan, approval: nil)
        XCTAssertEqual(r.status, .pendingSystemTrash)
    }
    func testUserDeniedNothingDeleted() async throws {
        let plan = try CleanupPlan.build([PlannedItem(item: photo("b"), risk: .low, category: .exactDuplicatePhotos)])
        let r = try await SafeCleanupEngine(executor: FixedExecutor { _ in .userDenied }, meter: FakeMeter(5, 5)).run(plan, approval: nil)
        XCTAssertEqual(r.status, .nothingDeleted)
    }
    func testByteFormat() {
        XCTAssertEqual(ByteFormat.format(999, locale: Locale(identifier: "en_US")), "999 B")
        XCTAssertEqual(ByteFormat.format(2_400_000_000, locale: Locale(identifier: "es_MX")), "2.4 GB")
        XCTAssertEqual(ByteFormat.format(128_000_000_000, locale: Locale(identifier: "en_US")), "128 GB")
    }
}
