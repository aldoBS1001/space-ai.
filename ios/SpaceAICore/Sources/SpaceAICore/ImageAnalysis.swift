import Foundation

/// Imagen en escala de grises (luma 0...255). La app la obtiene de una miniatura de PHImageManager.
public struct GrayImage: Sendable {
    public let width: Int
    public let height: Int
    public let luma: [Float]

    public init(width: Int, height: Int, luma: [Float]) throws {
        guard width > 0, height > 0 else { throw CoreError.invalidArgument("Dimensiones inválidas") }
        guard luma.count == width * height else { throw CoreError.invalidArgument("Tamaño de buffer inválido") }
        self.width = width; self.height = height; self.luma = luma
    }

    @inline(__always) public subscript(x: Int, y: Int) -> Float { luma[y * width + x] }

    /// Reducción por promedio de área (idéntica a la versión Kotlin para resultados iguales).
    public func resized(_ newW: Int, _ newH: Int) -> GrayImage {
        var out = [Float](repeating: 0, count: newW * newH)
        let sx = Double(width) / Double(newW), sy = Double(height) / Double(newH)
        for oy in 0..<newH {
            let y0 = Double(oy) * sy, y1 = Double(oy + 1) * sy
            for ox in 0..<newW {
                let x0 = Double(ox) * sx, x1 = Double(ox + 1) * sx
                var sum = 0.0, area = 0.0
                var y = Int(y0)
                while Double(y) < y1 && y < height {
                    let wy = min(Double(y) + 1, y1) - max(Double(y), y0)
                    var x = Int(x0)
                    while Double(x) < x1 && x < width {
                        let wx = min(Double(x) + 1, x1) - max(Double(x), x0)
                        sum += Double(self[x, y]) * wx * wy; area += wx * wy
                        x += 1
                    }
                    y += 1
                }
                out[oy * newW + ox] = area > 0 ? Float(sum / area) : 0
            }
        }
        return try! GrayImage(width: newW, height: newH, luma: out)
    }

    public func normalized(maxSide: Int) -> GrayImage {
        let longest = max(width, height)
        guard longest > maxSide else { return self }
        let f = Double(maxSide) / Double(longest)
        return resized(max(1, Int((Double(width) * f).rounded())), max(1, Int((Double(height) * f).rounded())))
    }

    /// Desde RGBA de 8 bits (formato de CGContext con premultipliedLast).
    public static func fromRGBA(width: Int, height: Int, rgba: [UInt8]) throws -> GrayImage {
        guard rgba.count == width * height * 4 else { throw CoreError.invalidArgument("Buffer RGBA inválido") }
        var l = [Float](repeating: 0, count: width * height)
        for i in 0..<l.count {
            l[i] = 0.299 * Float(rgba[i * 4]) + 0.587 * Float(rgba[i * 4 + 1]) + 0.114 * Float(rgba[i * 4 + 2])
        }
        return try GrayImage(width: width, height: height, luma: l)
    }
}

public enum PerceptualHash {
    /// dHash de 64 bits (bit 63 = primera comparación), igual que en Android.
    public static func dHash(_ img: GrayImage) -> UInt64 {
        let s = img.resized(9, 8)
        var hash: UInt64 = 0
        var bit = 63
        for y in 0..<8 { for x in 0..<8 {
            if s[x, y] < s[x + 1, y] { hash |= (1 << UInt64(bit)) }
            bit -= 1
        } }
        return hash
    }
    public static func hamming(_ a: UInt64, _ b: UInt64) -> Int { (a ^ b).nonzeroBitCount }
    public static func toHex(_ h: UInt64) -> String { let s = String(h, radix: 16); return String(repeating: "0", count: 16 - s.count) + s }
    public static func fromHex(_ s: String) -> UInt64? { UInt64(s, radix: 16) }
}

public struct QualityMetrics: Equatable, Sendable {
    public var sharpness: Double
    public var meanLuma: Double
    public var darkFraction: Double
    public var brightFraction: Double

    public init(sharpness: Double, meanLuma: Double, darkFraction: Double, brightFraction: Double) {
        self.sharpness = sharpness; self.meanLuma = meanLuma; self.darkFraction = darkFraction; self.brightFraction = brightFraction
    }

    public func isBlurry(_ t: QualityThresholds = .default) -> Bool { sharpness < t.blurVariance }
    public func isDark(_ t: QualityThresholds = .default) -> Bool { meanLuma < t.darkMeanLuma && darkFraction > t.darkFraction }
    public func isOverexposed(_ t: QualityThresholds = .default) -> Bool { brightFraction > t.overexposedFraction }

    public var score: Double {
        let sharp = min(max(sharpness / 400, 0), 1)
        let exposure = 1 - abs(meanLuma - 128) / 128
        let clipping = 1 - min(max(darkFraction + brightFraction, 0), 1)
        return 0.6 * sharp + 0.25 * exposure + 0.15 * clipping
    }

    public func encode() -> String { "\(sharpness),\(meanLuma),\(darkFraction),\(brightFraction)" }
    public static func decode(_ s: String) -> QualityMetrics? {
        let p = s.split(separator: ",").compactMap { Double($0) }
        guard p.count == 4 else { return nil }
        return QualityMetrics(sharpness: p[0], meanLuma: p[1], darkFraction: p[2], brightFraction: p[3])
    }
}

public struct QualityThresholds: Sendable {
    public var blurVariance: Double
    public var darkMeanLuma: Double
    public var darkFraction: Double
    public var overexposedFraction: Double
    public var lowQualityMaxPixels: Int64
    public static let `default` = QualityThresholds(blurVariance: 60, darkMeanLuma: 45, darkFraction: 0.70, overexposedFraction: 0.60, lowQualityMaxPixels: 640 * 480)
    public static let conservative = QualityThresholds(blurVariance: 30, darkMeanLuma: 30, darkFraction: 0.85, overexposedFraction: 0.80, lowQualityMaxPixels: 320 * 240)
}

public enum QualityAnalyzer {
    public static let analysisSide = 512

    public static func analyze(_ source: GrayImage) -> QualityMetrics {
        let img = source.normalized(maxSide: analysisSide)
        let w = img.width, h = img.height
        var sum = 0.0, dark = 0, bright = 0
        for v in img.luma { sum += Double(v); if v < 40 { dark += 1 }; if v > 245 { bright += 1 } }
        let n = Double(img.luma.count)
        var sharpness = 0.0
        if w >= 3 && h >= 3 {
            var mean = 0.0, m2 = 0.0, count = 0.0
            for y in 1..<(h - 1) { for x in 1..<(w - 1) {
                let lap = Double(img[x - 1, y] + img[x + 1, y] + img[x, y - 1] + img[x, y + 1] - 4 * img[x, y])
                count += 1
                let d = lap - mean
                mean += d / count
                m2 += d * (lap - mean)
            } }
            sharpness = count > 1 ? m2 / count : 0
        }
        return QualityMetrics(sharpness: sharpness, meanLuma: sum / n, darkFraction: Double(dark) / n, brightFraction: Double(bright) / n)
    }
}
