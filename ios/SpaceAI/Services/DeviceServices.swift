import Foundation
import Photos
import CryptoKit
import Security
import UniformTypeIdentifiers
import SpaceAICore

/// Volumen del dispositivo. `volumeAvailableCapacityForImportantUsage` es la cifra que usa Ajustes > Almacenamiento
/// (incluye espacio purgable que iOS puede liberar). Motivo declarado en PrivacyInfo.xcprivacy: 85F4.1 / E174.1.
struct IOSStorageMeter: StorageMeter {
    func measure() throws -> StorageSnapshot {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let v = try url.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        let total = Int64(v.volumeTotalCapacity ?? 0)
        let free = v.volumeAvailableCapacityForImportantUsage ?? Int64(v.volumeAvailableCapacity ?? 0)
        return try StorageSnapshot(totalBytes: total, freeBytes: min(free, total), takenAt: Int64(Date().timeIntervalSince1970 * 1000))
    }
}

/// Elimina con APIs oficiales y verifica después:
/// - Fotos/videos: PHAssetChangeRequest.deleteAssets -> iOS muestra su diálogo y los mueve a "Eliminados recientemente".
/// - Archivos de carpetas autorizadas: FileManager.removeItem dentro del ámbito de seguridad.
struct IOSCleanupExecutor: CleanupExecutor {
    let folders: AuthorizedFolders

    func execute(_ items: [MediaItem], cancel: CancellationSignal) async -> [ItemResult] {
        let media = items.filter { $0.kind == .photo || $0.kind == .video }
        let files = items.filter { $0.kind == .document || $0.kind == .other }
        var results: [ItemResult] = []
        if !media.isEmpty && !cancel.isCancelled {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: media.map(\.id), options: nil)
            var accepted = true
            var errorText: String?
            do {
                try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
            } catch {
                accepted = false
                errorText = error.localizedDescription
            }
            for m in media {
                let gone = PhotoLibrary.isGone(m.id)
                if gone {
                    // iOS siempre conserva lo borrado ~30 días en "Eliminados recientemente": no es espacio liberado aún.
                    results.append(ItemResult(item: m, outcome: accepted ? .movedToTrash : .notFound))
                } else if !accepted {
                    // El usuario rechazó el diálogo del sistema (PHPhotosError.userCancelled) u otro error: nada se borró.
                    results.append(ItemResult(item: m, outcome: .userDenied, error: errorText))
                } else {
                    results.append(ItemResult(item: m, outcome: .failed, error: "Sigue en la fototeca"))
                }
            }
        }
        for f in files {
            if cancel.isCancelled { break }
            results.append(folders.delete(f))
        }
        return results
    }
}

/// Carpetas que el usuario eligió en el selector de Archivos (marcadores con ámbito de seguridad).
final class AuthorizedFolders: @unchecked Sendable {
    private let key = "folders.bookmarks.v1"

    var urls: [URL] {
        (UserDefaults.standard.array(forKey: key) as? [Data] ?? []).compactMap { data in
            var stale = false
            return try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        }
    }

    func add(_ url: URL) throws {
        guard url.startAccessingSecurityScopedResource() else { throw CoreError.invalidArgument("Sin acceso a la carpeta") }
        defer { url.stopAccessingSecurityScopedResource() }
        let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        var list = UserDefaults.standard.array(forKey: key) as? [Data] ?? []
        list.append(data)
        UserDefaults.standard.set(list, forKey: key)
    }

    func remove(_ url: URL) {
        let keep = (UserDefaults.standard.array(forKey: key) as? [Data] ?? []).filter { data in
            var stale = false
            return (try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)) != url
        }
        UserDefaults.standard.set(keep, forKey: key)
    }

    /// Enumera archivos regulares. Los ids son rutas absolutas dentro de una carpeta autorizada.
    func scan(cancel: CancellationSignal) throws -> [MediaItem] {
        var out: [MediaItem] = []
        for root in urls {
            guard root.startAccessingSecurityScopedResource() else { continue }
            defer { root.stopAccessingSecurityScopedResource() }
            let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .contentTypeKey, .isWritableKey]
            guard let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys, options: [.skipsPackageDescendants]) else { continue }
            for case let url as URL in e {
                try cancel.throwIfCancelled()
                guard let v = try? url.resourceValues(forKeys: Set(keys)), v.isRegularFile == true, let size = v.fileSize, size > 0 else { continue }
                let type = v.contentType
                let isMediaFile = type?.conforms(to: .image) == true || type?.conforms(to: .movie) == true
                out.append(MediaItem(
                    id: url.path, displayName: url.lastPathComponent, sizeBytes: Int64(size),
                    kind: isMediaFile ? .other : .document, mimeType: type?.preferredMIMEType,
                    dateModified: Int64((v.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000),
                    deletable: v.isWritable ?? false))
            }
        }
        return out
    }

    private func root(for path: String) -> URL? { urls.first { path.hasPrefix($0.path) } }

    func delete(_ item: MediaItem) -> ItemResult {
        guard let root = root(for: item.id) else { return ItemResult(item: item, outcome: .failed, error: "Carpeta no autorizada") }
        guard root.startAccessingSecurityScopedResource() else { return ItemResult(item: item, outcome: .failed, error: "Sin acceso") }
        defer { root.stopAccessingSecurityScopedResource() }
        let url = URL(fileURLWithPath: item.id)
        do { try FileManager.default.removeItem(at: url) } catch {
            return ItemResult(item: item, outcome: FileManager.default.fileExists(atPath: url.path) ? .failed : .notFound, error: error.localizedDescription)
        }
        return ItemResult(item: item, outcome: FileManager.default.fileExists(atPath: url.path) ? .failed : .deleted)
    }

    /// Lector para SHA-256 de archivos autorizados.
    func contentSource() -> ContentSource { FolderContentSource(folders: self) }

    func withAccess<T>(to path: String, _ body: () throws -> T) throws -> T {
        guard let root = root(for: path), root.startAccessingSecurityScopedResource() else { throw CoreError.invalidArgument("Sin acceso") }
        defer { root.stopAccessingSecurityScopedResource() }
        return try body()
    }
}

private struct FolderContentSource: ContentSource {
    let folders: AuthorizedFolders
    func read(_ item: MediaItem, chunk: (Data) throws -> Bool) throws {
        try folders.withAccess(to: item.id) {
            let h = try FileHandle(forReadingFrom: URL(fileURLWithPath: item.id))
            defer { try? h.close() }
            while let d = try h.read(upToCount: 64 * 1024), !d.isEmpty {
                if try !chunk(d) { return }
            }
        }
    }
}

/// Archivos temporales propios (lo único de "caché" que iOS permite borrar a una app).
enum AppTempFiles {
    static var dirs: [URL] {
        [FileManager.default.temporaryDirectory] + FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
    }
    static func sizeBytes() -> Int64 {
        dirs.reduce(0) { total, dir in
            let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey])
            var sum: Int64 = 0
            while let u = e?.nextObject() as? URL { sum += Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
            return total + sum
        }
    }
    /// Devuelve los bytes borrados (medidos por archivo).
    static func clear() -> Int64 {
        var freed: Int64 = 0
        for dir in dirs {
            for u in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] {
                let size = folderSize(u)
                if (try? FileManager.default.removeItem(at: u)) != nil { freed += size }
            }
        }
        return freed
    }
    private static func folderSize(_ u: URL) -> Int64 {
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir)
        if !isDir.boolValue { return Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        var sum: Int64 = 0
        let e = FileManager.default.enumerator(at: u, includingPropertiesForKeys: [.fileSizeKey])
        while let f = e?.nextObject() as? URL { sum += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        return sum
    }
}

/// Historial cifrado con AES-GCM (CryptoKit); la clave vive en el Llavero, solo en este dispositivo.
struct HistoryEntry: Codable, Identifiable {
    var id: Double { timestamp }
    var timestamp: Double
    var status: String
    var deletedCount: Int, trashedCount: Int, failedCount: Int
    var deletedBytes: Int64, trashedBytes: Int64, measuredFreedBytes: Int64
}

final class HistoryStore: @unchecked Sendable {
    private let url: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var u = dir.appendingPathComponent("history.bin")
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
        return u
    }()
    private let lock = NSLock()
    private var cachedKey: SymmetricKey?

    func record(_ r: CleanupReport) {
        append(HistoryEntry(timestamp: Date().timeIntervalSince1970, status: r.status.rawValue, deletedCount: r.deletedCount,
                            trashedCount: r.trashedCount, failedCount: r.failed.count, deletedBytes: r.deletedBytes,
                            trashedBytes: r.trashedBytes, measuredFreedBytes: r.measuredFreedBytes))
    }

    func append(_ e: HistoryEntry) {
        lock.lock(); defer { lock.unlock() }
        var all = loadUnlocked(); all.append(e)
        if all.count > 200 { all.removeFirst(all.count - 200) }
        guard let plain = try? JSONEncoder().encode(all), let sealed = try? AES.GCM.seal(plain, using: key()).combined else { return }
        try? sealed.write(to: url, options: [.atomic, .completeFileProtection])
    }

    func load() -> [HistoryEntry] { lock.lock(); defer { lock.unlock() }; return loadUnlocked() }

    func clear() { lock.lock(); try? FileManager.default.removeItem(at: url); lock.unlock() }

    private func loadUnlocked() -> [HistoryEntry] {
        guard let data = try? Data(contentsOf: url), let box = try? AES.GCM.SealedBox(combined: data),
              let plain = try? AES.GCM.open(box, using: key()) else { return [] }
        return (try? JSONDecoder().decode([HistoryEntry].self, from: plain)) ?? []
    }

    /// Clave en el Llavero (solo este dispositivo). Se conserva en memoria para que una falla del Llavero
    /// (p. ej. binario sin firmar en simulador) no genere una clave distinta en cada operación.
    private func key() -> SymmetricKey {
        if let cachedKey { return cachedKey }
        let k = loadOrCreateKey()
        cachedKey = k
        return k
    }

    private func loadOrCreateKey() -> SymmetricKey {
        let tag = "com.businessshop.spaceai.history.key"
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: tag,
                                    kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data { return SymmetricKey(data: data) }
        let k = SymmetricKey(size: .bits256)
        let raw = k.withUnsafeBytes { Data($0) }
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: tag, kSecValueData as String: raw,
                                  kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        SecItemAdd(add as CFDictionary, nil)
        return k
    }
}
