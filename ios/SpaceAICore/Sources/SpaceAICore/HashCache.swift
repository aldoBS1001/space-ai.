import Foundation

/// Caché incremental de hashes/métricas (reanudación tras cierre inesperado). Solo ids y hashes.
public final class HashCache: @unchecked Sendable {
    public enum Kind: String { case partial = "PARTIAL", full = "FULL", dhash = "DHASH", blur = "BLUR" }
    public static let header = "SPACEAI-HASHCACHE-V1"

    private var map: [String: String] = [:]
    private var dirty = 0
    private let url: URL?
    private let lock = NSLock()
    private let flushEvery = 200

    public init(url: URL? = nil) {
        self.url = url
        load()
    }

    private func key(_ item: MediaItem, _ kind: Kind) -> String {
        "\(kind.rawValue)|\(item.id)|\(item.sizeBytes)|\(item.dateModified)"
    }

    public func get(_ item: MediaItem, _ kind: Kind) -> String? {
        lock.lock(); defer { lock.unlock() }
        return map[key(item, kind)]
    }

    public func put(_ item: MediaItem, _ kind: Kind, _ value: String) {
        precondition(!value.contains("\n") && !value.contains("\t"), "Valor de caché inválido")
        lock.lock()
        map[key(item, kind)] = value
        dirty += 1
        let shouldFlush = dirty >= flushEvery
        lock.unlock()
        if shouldFlush { try? flush() }
    }

    public var count: Int { lock.lock(); defer { lock.unlock() }; return map.count }

    /// Escritura atómica (Data.write(options: .atomic) usa archivo temporal + rename).
    public func flush() throws {
        guard let url else { return }
        lock.lock()
        var text = Self.header + "\n"
        for (k, v) in map { text += k + "\t" + v + "\n" }
        dirty = 0
        lock.unlock()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    public func clear() {
        lock.lock(); map.removeAll(); dirty = 0; lock.unlock()
        if let url { try? FileManager.default.removeItem(at: url) }
    }

    private func load() {
        guard let url, let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else { return }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true).makeIterator()
        guard lines.next().map(String.init) == Self.header else { return }
        while let line = lines.next() {
            if let tab = line.firstIndex(of: "\t") {
                map[String(line[..<tab])] = String(line[line.index(after: tab)...])
            }
        }
    }
}
