import XCTest
import CoreGraphics
import SpaceAICore
@testable import SpaceAI

/// Pruebas de los servicios de plataforma en el simulador (APIs reales de iOS).
final class DeviceServicesTests: XCTestCase {
    func testStorageMeterReturnsRealVolume() throws {
        let s = try IOSStorageMeter().measure()
        XCTAssertGreaterThan(s.totalBytes, 0)
        XCTAssertGreaterThan(s.freeBytes, 0)
        XCTAssertLessThanOrEqual(s.freeBytes, s.totalBytes)
    }

    func testHistoryIsEncryptedOnDisk() throws {
        let store = HistoryStore()
        store.clear()
        store.append(HistoryEntry(timestamp: 1, status: "verifiedFreed", deletedCount: 2, trashedCount: 0, failedCount: 0,
                                  deletedBytes: 10, trashedBytes: 0, measuredFreedBytes: 10))
        XCTAssertEqual(store.load().count, 1)
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("history.bin")
        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data("verifiedFreed".utf8)), "El historial no debe guardarse en texto plano")
        store.clear()
    }

    func testGrayImageFromCGImage() throws {
        let w = 64, h = 48
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let img = try XCTUnwrap(PhotoLibrary.grayImage(from: ctx.makeImage()!))
        XCTAssertEqual(img.width, w); XCTAssertEqual(img.luma.first ?? 0, 255, accuracy: 0.5)
    }

    func testAppTempClearRemovesOnlyOwnFilesAndMeasures() throws {
        let f = FileManager.default.temporaryDirectory.appendingPathComponent("spaceai_test.bin")
        try Data(count: 50_000).write(to: f)
        XCTAssertGreaterThanOrEqual(AppTempFiles.sizeBytes(), 50_000)
        XCTAssertGreaterThanOrEqual(AppTempFiles.clear(), 50_000)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.path))
    }

    func testLocalizationParity() throws {
        let bundle = Bundle(for: AppModel.self)
        func keys(_ lang: String) throws -> Set<String> {
            let path = try XCTUnwrap(bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: lang))
            return Set((NSDictionary(contentsOfFile: path) as? [String: String] ?? [:]).keys)
        }
        XCTAssertEqual(try keys("en"), try keys("es-MX"))
    }
}
