import Foundation
import AVFoundation
import Photos
import SpaceAICore

struct InsufficientSpace: Error { let required: Int64; let free: Int64 }
struct ExportFailed: Error { let message: String }

struct CompressionJob {
    let outputURL: URL
    let output: CompressedOutput
    let validation: CompressionValidation
}

/// Compresión real con AVAssetExportSession (codificador de hardware de Apple, H.264/HEVC según preset).
/// El original nunca se toca: la copia se valida, el usuario la reproduce, se guarda y solo entonces se ofrece borrar el original.
final class VideoCompressorIOS {
    private let library: PhotoLibrary
    private let meter = IOSStorageMeter()
    init(library: PhotoLibrary) { self.library = library }

    static func exportPreset(_ p: CompressionPreset) -> String {
        switch p {
        case .high: return AVAssetExportPreset1920x1080
        case .balanced: return AVAssetExportPreset1280x720
        case .small: return AVAssetExportPreset960x540
        }
    }

    func info(for item: MediaItem) async -> VideoInfo? {
        guard let asset = await library.avAsset(for: item.id, allowNetwork: false) else { return nil }
        do {
            let duration = try await asset.load(.duration)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else { return nil }
            let (size, transform, fps) = try await track.load(.naturalSize, .preferredTransform, .nominalFrameRate)
            let r = size.applying(transform)
            let hasAudio = !(try await asset.loadTracks(withMediaType: .audio)).isEmpty
            return VideoInfo(sizeBytes: item.sizeBytes, durationMillis: Int64(duration.seconds * 1000),
                             width: Int(abs(r.width)), height: Int(abs(r.height)), frameRate: Double(fps > 0 ? fps : 30), hasAudio: hasAudio)
        } catch { return nil }
    }

    func compress(_ item: MediaItem, info: VideoInfo, preset: CompressionPreset, progress: @escaping @Sendable (Double) -> Void) async throws -> CompressionJob {
        let estimate = try VideoCompressionPlanner.estimate(info, preset: preset)
        let required = CompressionValidator.requiredFreeBytes(estimate)
        let free = try meter.measure().freeBytes
        guard free >= required else { throw InsufficientSpace(required: required, free: free) }
        guard let asset = await library.avAsset(for: item.id, allowNetwork: false),
              let session = AVAssetExportSession(asset: asset, presetName: Self.exportPreset(preset)) else {
            throw ExportFailed(message: "El video no está disponible localmente")
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("spaceai_\(UUID().uuidString).mp4")
        session.outputURL = out
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true

        let poller = Task { while !Task.isCancelled { progress(Double(session.progress)); try? await Task.sleep(nanoseconds: 250_000_000) } }
        defer { poller.cancel() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    switch session.status {
                    case .completed: cont.resume()
                    case .cancelled: cont.resume(throwing: CancellationError())
                    default: cont.resume(throwing: session.error ?? ExportFailed(message: "Exportación fallida"))
                    }
                }
            }
        } onCancel: {
            session.cancelExport()
        }
        progress(1)
        let output = await Self.inspect(out)
        let validation = CompressionValidator.validate(original: info, output: output)
        if validation != .valid { try? FileManager.default.removeItem(at: out) }
        return CompressionJob(outputURL: out, output: output, validation: validation)
    }

    /// Medición real: tamaño en disco, duración, dimensiones y decodificación del primer y último cuadro.
    static func inspect(_ url: URL) async -> CompressedOutput {
        let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        let asset = AVURLAsset(url: url)
        do {
            let duration = try await asset.load(.duration)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else {
                return CompressedOutput(sizeBytes: size, durationMillis: 0, width: 0, height: 0, decodable: false)
            }
            let (natural, transform) = try await track.load(.naturalSize, .preferredTransform)
            let r = natural.applying(transform)
            let gen = AVAssetImageGenerator(asset: asset)
            gen.requestedTimeToleranceBefore = .positiveInfinity
            gen.requestedTimeToleranceAfter = .positiveInfinity
            let first = try? await gen.image(at: .zero)
            let lastTime = CMTimeSubtract(duration, CMTime(seconds: 0.5, preferredTimescale: 600))
            let last = try? await gen.image(at: CMTimeMaximum(.zero, lastTime))
            return CompressedOutput(sizeBytes: size, durationMillis: Int64(duration.seconds * 1000),
                                    width: Int(abs(r.width)), height: Int(abs(r.height)), decodable: first != nil && last != nil)
        } catch {
            return CompressedOutput(sizeBytes: size, durationMillis: 0, width: 0, height: 0, decodable: false)
        }
    }

    /// Guarda la copia validada en Fotos conservando la fecha original.
    func saveToLibrary(_ url: URL, original: MediaItem) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let opts = PHAssetResourceCreationOptions()
            opts.shouldMoveFile = true
            req.addResource(with: .video, fileURL: url, options: opts)
            if original.dateTaken > 0 { req.creationDate = Date(timeIntervalSince1970: Double(original.dateTaken) / 1000) }
        }
    }
}
