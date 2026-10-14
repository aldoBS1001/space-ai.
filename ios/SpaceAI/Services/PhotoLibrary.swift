import Foundation
import Photos
import AVFoundation
import UIKit
import SpaceAICore

/// Acceso a la fototeca con PhotoKit. Con acceso limitado, PhotoKit solo devuelve lo que el usuario eligió.
final class PhotoLibrary: @unchecked Sendable {
    private let lock = NSLock()
    private var assets: [String: PHAsset] = [:]

    static func authorizationState() -> AccessState {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return .full
        case .limited: return .limited
        case .denied, .restricted: return .denied
        case .notDetermined: return .notRequested
        @unknown default: return .denied
        }
    }

    static func requestAuthorization() async -> AccessState {
        _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return authorizationState()
    }

    func asset(for id: String) -> PHAsset? { lock.lock(); defer { lock.unlock() }; return assets[id] }

    /// Lista fotos y videos. El tamaño se obtiene del recurso original (PHAssetResource);
    /// si iOS no lo expone, se omite el elemento en lugar de inventar un tamaño.
    func fetchAll(cancel: CancellationSignal) throws -> [MediaItem] {
        let opts = PHFetchOptions()
        opts.includeAllBurstAssets = true
        opts.includeHiddenAssets = false
        let result = PHAsset.fetchAssets(with: opts)
        var out: [MediaItem] = []
        out.reserveCapacity(result.count)
        var map: [String: PHAsset] = [:]
        var cancelled = false
        result.enumerateObjects { asset, idx, stop in
            if idx % 200 == 0 && cancel.isCancelled { cancelled = true; stop.pointee = true; return }
            guard asset.mediaType == .image || asset.mediaType == .video else { return }
            guard let size = Self.originalSize(asset), size > 0 else { return }
            let isVideo = asset.mediaType == .video
            let created = Int64((asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000)
            let modified = Int64((asset.modificationDate?.timeIntervalSince1970 ?? 0) * 1000)
            let resource = PHAssetResource.assetResources(for: asset).first
            let item = MediaItem(
                id: asset.localIdentifier,
                displayName: resource?.originalFilename ?? asset.localIdentifier,
                sizeBytes: size,
                kind: isVideo ? .video : .photo,
                mimeType: resource?.uniformTypeIdentifier,
                dateTaken: created,
                dateModified: modified,
                width: asset.pixelWidth,
                height: asset.pixelHeight,
                durationMillis: Int64(asset.duration * 1000),
                isScreenshot: asset.mediaSubtypes.contains(.photoScreenshot),
                isScreenRecording: isVideo && Self.isScreenRecording(resource?.originalFilename),
                isFavorite: asset.isFavorite,
                burstId: asset.representsBurst || asset.burstIdentifier != nil ? asset.burstIdentifier : nil,
                deletable: asset.canPerform(.delete)
            )
            out.append(item)
            map[asset.localIdentifier] = asset
        }
        if cancelled { throw CoreError.cancelled }
        lock.lock(); assets = map; lock.unlock()
        return out
    }

    /// PhotoKit no expone un subtipo público para grabaciones de pantalla; iOS las guarda como "RPReplay_*.mp4".
    private static func isScreenRecording(_ filename: String?) -> Bool {
        filename?.lowercased().hasPrefix("rpreplay") ?? false
    }

    /// Tamaño del recurso original. PhotoKit no tiene API pública documentada para el tamaño;
    /// se usa la propiedad `fileSize` de PHAssetResource (ampliamente usada) y, si no existe, se descarta.
    static func originalSize(_ asset: PHAsset) -> Int64? {
        let resources = PHAssetResource.assetResources(for: asset)
        let preferred = resources.first { $0.type == .photo || $0.type == .video || $0.type == .fullSizePhoto || $0.type == .fullSizeVideo } ?? resources.first
        guard let r = preferred else { return nil }
        if let n = r.value(forKey: "fileSize") as? NSNumber { return n.int64Value }
        return nil
    }

    /// Miniatura de 512 px en escala de grises para dHash y calidad (no descarga de iCloud).
    func gray(for item: MediaItem) async -> GrayImage? {
        guard let asset = asset(for: item.id) else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = false
        opts.deliveryMode = .highQualityFormat
        opts.resizeMode = .fast
        opts.isSynchronous = false
        let image: UIImage? = await withCheckedContinuation { cont in
            var resumed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 512, height: 512), contentMode: .aspectFit, options: opts) { img, info in
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if degraded || resumed { return }
                resumed = true
                cont.resume(returning: img)
            }
        }
        guard let cg = image?.cgImage else { return nil }
        return Self.grayImage(from: cg)
    }

    static func grayImage(from cg: CGImage) -> GrayImage? {
        let w = cg.width, h = cg.height
        guard w > 0, h > 0 else { return nil }
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = rgba.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? try? GrayImage.fromRGBA(width: w, height: h, rgba: rgba) : nil
    }

    func thumbnail(for id: String, side: CGFloat) async -> UIImage? {
        guard let asset = asset(for: id) else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = true
        opts.deliveryMode = .opportunistic
        return await withCheckedContinuation { cont in
            var resumed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: side, height: side), contentMode: .aspectFill, options: opts) { img, info in
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if degraded || resumed { return }
                resumed = true
                cont.resume(returning: img)
            }
        }
    }

    func avAsset(for id: String, allowNetwork: Bool) async -> AVAsset? {
        guard let asset = asset(for: id) else { return nil }
        let opts = PHVideoRequestOptions()
        opts.isNetworkAccessAllowed = allowNetwork
        opts.deliveryMode = .highQualityFormat
        opts.version = .original
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestAVAsset(forVideo: asset, options: opts) { av, _, _ in cont.resume(returning: av) }
        }
    }

    /// true si el asset ya no existe en la fototeca (borrado o en "Eliminados recientemente").
    static func isGone(_ id: String) -> Bool {
        PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).count == 0
    }
}

/// Contenido original de un asset para el SHA-256 (sin descargar de iCloud: si no es local, falla y se reporta).
final class PhotoContentSource: ContentSource {
    private let library: PhotoLibrary
    init(library: PhotoLibrary) { self.library = library }

    func read(_ item: MediaItem, chunk: (Data) throws -> Bool) throws {
        guard let asset = library.asset(for: item.id) else { throw CoreError.invalidArgument("Asset no disponible") }
        let resources = PHAssetResource.assetResources(for: asset)
        guard let r = resources.first(where: { $0.type == .photo || $0.type == .video }) ?? resources.first else {
            throw CoreError.invalidArgument("Sin recurso")
        }
        let opts = PHAssetResourceRequestOptions()
        opts.isNetworkAccessAllowed = false
        // PhotoKit entrega los datos de forma asíncrona; se ponen en una cola acotada y se consumen aquí.
        let queue = ChunkQueue(limit: 8)
        let requestId = PHAssetResourceManager.default().requestData(for: r, options: opts, dataReceivedHandler: { data in
            queue.push(data)
        }, completionHandler: { error in
            queue.finish(error)
        })
        var completed = false
        defer {
            if !completed { // parada anticipada, cancelación o error: liberar al productor
                queue.abandon()
                PHAssetResourceManager.default().cancelDataRequest(requestId)
            }
        }
        while let next = try queue.pop() {
            if try !chunk(next) { return }
        }
        completed = true
    }
}

/// Cola productor/consumidor con contrapresión para no acumular un video completo en memoria.
private final class ChunkQueue: @unchecked Sendable {
    private var items: [Data] = []
    private var done = false
    private var abandoned = false
    private var error: Error?
    private let cond = NSCondition()
    private let limit: Int
    init(limit: Int) { self.limit = limit }

    func push(_ d: Data) {
        cond.lock()
        while items.count >= limit && !abandoned { cond.wait() }
        if !abandoned { items.append(d) }
        cond.signal(); cond.unlock()
    }
    func finish(_ e: Error?) { cond.lock(); done = true; error = e; cond.broadcast(); cond.unlock() }
    func abandon() { cond.lock(); abandoned = true; items.removeAll(); cond.broadcast(); cond.unlock() }
    func pop() throws -> Data? {
        cond.lock(); defer { cond.unlock() }
        while items.isEmpty && !done { cond.wait() }
        if !items.isEmpty { let d = items.removeFirst(); cond.signal(); return d }
        if let error { throw error }
        return nil
    }
}
