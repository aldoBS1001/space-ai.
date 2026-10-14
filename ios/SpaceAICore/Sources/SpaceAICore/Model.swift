import Foundation

/// Tipo de elemento que la plataforma entrega al motor.
public enum MediaKind: String, Codable, Sendable { case photo, video, document, other }

/// Elemento accesible. En iOS `id` es `PHAsset.localIdentifier` o la ruta de un archivo autorizado.
public struct MediaItem: Hashable, Sendable {
    public var id: String
    public var displayName: String
    public var sizeBytes: Int64
    public var kind: MediaKind
    public var mimeType: String?
    public var dateTaken: Int64          // milisegundos epoch, 0 = desconocida
    public var dateModified: Int64
    public var width: Int
    public var height: Int
    public var durationMillis: Int64
    public var isScreenshot: Bool
    public var isScreenRecording: Bool
    public var isFavorite: Bool
    public var burstId: String?
    public var deletable: Bool

    public init(id: String, displayName: String, sizeBytes: Int64, kind: MediaKind, mimeType: String? = nil,
                dateTaken: Int64 = 0, dateModified: Int64 = 0, width: Int = 0, height: Int = 0,
                durationMillis: Int64 = 0, isScreenshot: Bool = false, isScreenRecording: Bool = false,
                isFavorite: Bool = false, burstId: String? = nil, deletable: Bool = true) {
        self.id = id; self.displayName = displayName; self.sizeBytes = sizeBytes; self.kind = kind
        self.mimeType = mimeType; self.dateTaken = dateTaken; self.dateModified = dateModified
        self.width = width; self.height = height; self.durationMillis = durationMillis
        self.isScreenshot = isScreenshot; self.isScreenRecording = isScreenRecording
        self.isFavorite = isFavorite; self.burstId = burstId; self.deletable = deletable
    }

    public var pixelCount: Int64 { Int64(width) * Int64(height) }
    public var aspectRatio: Double { height == 0 ? 0 : Double(width) / Double(height) }
}

public enum RiskLevel: Int, Comparable, Sendable {
    case low = 0, medium = 1, high = 2
    public static func < (a: RiskLevel, b: RiskLevel) -> Bool { a.rawValue < b.rawValue }
    public var requiresExplicitApproval: Bool { self != .low }
}

public enum CleanupCategory: String, CaseIterable, Sendable {
    case exactDuplicatePhotos, similarPhotos, burstPhotos, screenshots, blurryPhotos, darkPhotos,
         lowQualityPhotos, oldLargePhotos, largeVideos, duplicateVideos, screenRecordings, oldVideos,
         compressibleVideos, largeFiles, duplicateFiles, appTempFiles
}

public enum AccessState: String, Sendable { case full, limited, denied, restrictedByOS, notRequested }

public struct AccessReport: Sendable, Equatable {
    public var photos: AccessState
    public var videos: AccessState
    public var files: AccessState
    public var otherAppsCaches: AccessState
    public init(photos: AccessState, videos: AccessState, files: AccessState, otherAppsCaches: AccessState = .restrictedByOS) {
        self.photos = photos; self.videos = videos; self.files = files; self.otherAppsCaches = otherAppsCaches
    }
}

public enum CoreError: Error, Equatable {
    case cancelled
    case invalidArgument(String)
    case unsafePlan(String)
}

/// Medición real del volumen.
public struct StorageSnapshot: Sendable, Equatable {
    public let totalBytes: Int64
    public let freeBytes: Int64
    public let takenAt: Int64

    public init(totalBytes: Int64, freeBytes: Int64, takenAt: Int64) throws {
        guard totalBytes >= 0, freeBytes >= 0 else { throw CoreError.invalidArgument("Valores negativos de almacenamiento") }
        guard freeBytes <= totalBytes else { throw CoreError.invalidArgument("Libre > total: medición inválida") }
        self.totalBytes = totalBytes; self.freeBytes = freeBytes; self.takenAt = takenAt
    }
    public var usedBytes: Int64 { totalBytes - freeBytes }
    public var usedFraction: Double { totalBytes == 0 ? 0 : Double(usedBytes) / Double(totalBytes) }
}

/// Cancelación cooperativa.
public struct CancellationSignal: Sendable {
    private let check: @Sendable () -> Bool
    public init(_ check: @escaping @Sendable () -> Bool) { self.check = check }
    public var isCancelled: Bool { check() }
    public static let none = CancellationSignal { false }
    public func throwIfCancelled() throws { if check() { throw CoreError.cancelled } }
}
