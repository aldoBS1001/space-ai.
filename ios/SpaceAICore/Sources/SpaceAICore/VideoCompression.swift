import Foundation

public enum CompressionPreset: String, CaseIterable, Sendable {
    case high, balanced, small
    public var maxShortSide: Int {
        switch self {
        case .high: return 1080
        case .balanced: return 720
        case .small: return 540
        }
    }
    var bitsPerPixelPerFrame: Double {
        switch self {
        case .high: return 0.075
        case .balanced: return 0.070
        case .small: return 0.060
        }
    }
    public var audioBitrate: Int {
        switch self {
        case .high: return 128_000
        case .balanced: return 96_000
        case .small: return 64_000
        }
    }
}

public struct VideoInfo: Sendable, Equatable {
    public var sizeBytes: Int64
    public var durationMillis: Int64
    public var width: Int
    public var height: Int
    public var frameRate: Double
    public var hasAudio: Bool
    public init(sizeBytes: Int64, durationMillis: Int64, width: Int, height: Int, frameRate: Double = 30, hasAudio: Bool = true) {
        self.sizeBytes = sizeBytes; self.durationMillis = durationMillis; self.width = width; self.height = height
        self.frameRate = frameRate; self.hasAudio = hasAudio
    }
}

public struct CompressionEstimate: Sendable, Equatable {
    public var targetWidth: Int
    public var targetHeight: Int
    public var targetVideoBitrate: Int
    public var targetAudioBitrate: Int
    public var estimatedBytes: Int64
    public var estimatedSavingsBytes: Int64
    public var worthIt: Bool
    public var savingsFraction: Double {
        let t = estimatedBytes + estimatedSavingsBytes
        return t == 0 ? 0 : Double(estimatedSavingsBytes) / Double(t)
    }
}

public enum VideoCompressionPlanner {
    public static let minSavingsFraction = 0.20
    public static let minSavingsBytes: Int64 = 10 * 1024 * 1024

    public static func estimate(_ info: VideoInfo, preset: CompressionPreset = .balanced) throws -> CompressionEstimate {
        guard info.durationMillis > 0 else { throw CoreError.invalidArgument("Duración inválida") }
        let shortSide = min(info.width, info.height)
        let scale = shortSide > preset.maxShortSide && shortSide > 0 ? Double(preset.maxShortSide) / Double(shortSide) : 1
        let tw = even(Int((Double(info.width) * scale).rounded()))
        let th = even(Int((Double(info.height) * scale).rounded()))
        let fps = min(max(info.frameRate, 1), 60)
        let videoBitrate = max(250_000, Int(Double(tw) * Double(th) * fps * preset.bitsPerPixelPerFrame))
        let audio = info.hasAudio ? preset.audioBitrate : 0
        let seconds = Double(info.durationMillis) / 1000
        let sourceBitrate = Double(info.sizeBytes) * 8 / seconds
        let effectiveVideo = Int(min(Double(videoBitrate), max(0, sourceBitrate - Double(audio))))
        let estimated = Int64(Double(effectiveVideo + audio) * seconds / 8 * 1.02)
        let savings = max(0, info.sizeBytes - estimated)
        let worth = savings >= minSavingsBytes && Double(savings) / Double(info.sizeBytes) >= minSavingsFraction
        return CompressionEstimate(targetWidth: tw, targetHeight: th, targetVideoBitrate: effectiveVideo, targetAudioBitrate: audio,
                                   estimatedBytes: estimated, estimatedSavingsBytes: savings, worthIt: worth)
    }

    private static func even(_ v: Int) -> Int { max(2, v - (v % 2)) }
}

public struct CompressedOutput: Sendable, Equatable {
    public var sizeBytes: Int64
    public var durationMillis: Int64
    public var width: Int
    public var height: Int
    public var decodable: Bool
    public init(sizeBytes: Int64, durationMillis: Int64, width: Int, height: Int, decodable: Bool) {
        self.sizeBytes = sizeBytes; self.durationMillis = durationMillis; self.width = width; self.height = height; self.decodable = decodable
    }
}

public enum CompressionValidation: Equatable, Sendable {
    case valid
    case invalid(Reason)
    public enum Reason: String, Sendable { case notDecodable, durationMismatch, notSmaller, emptyFile, badDimensions }
}

public enum CompressionValidator {
    public static func validate(original: VideoInfo, output: CompressedOutput) -> CompressionValidation {
        if output.sizeBytes <= 0 { return .invalid(.emptyFile) }
        if !output.decodable { return .invalid(.notDecodable) }
        if output.width <= 0 || output.height <= 0 { return .invalid(.badDimensions) }
        let tolerance = max(1000, Int64(Double(original.durationMillis) * 0.02))
        if abs(output.durationMillis - original.durationMillis) > tolerance { return .invalid(.durationMismatch) }
        if output.sizeBytes >= original.sizeBytes { return .invalid(.notSmaller) }
        return .valid
    }

    public static func requiredFreeBytes(_ e: CompressionEstimate) -> Int64 {
        Int64(Double(e.estimatedBytes) * 1.10) + 200 * 1024 * 1024
    }
}
