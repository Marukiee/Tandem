import AppKit
import Observation

/// The icons of the apps on the phones, as the phones send them, so a notification and the list of notifications can
/// show which app they came from. Kept on disk, because a phone sends an icon once and not at every start.
@MainActor @Observable
final class AppIcons {
    static let shared = AppIcons()

    /// Counts the icons that have arrived, so a view that showed a placeholder shows the icon when it comes.
    private(set) var version = 0
    @ObservationIgnored private var cache: [String: NSImage] = [:]
    @ObservationIgnored private let folder: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let folder = base.appendingPathComponent("AppIcons", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }()

    func store(appId: String, png: Data) {
        guard let image = NSImage(data: png) else { return }
        try? png.write(to: file(for: appId), options: .atomic)
        cache[appId] = image
        version += 1
    }

    func image(for appId: String) -> NSImage? {
        _ = version
        if let image = cache[appId] { return image }
        guard let image = NSImage(contentsOf: file(for: appId)) else { return nil }
        cache[appId] = image
        return image
    }

    /// A copy of the icon to hand to a notification, which takes the file it is given away.
    func attachmentCopy(for appId: String) -> URL? {
        let source = file(for: appId)
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        do {
            try FileManager.default.copyItem(at: source, to: copy)
            return copy
        } catch {
            return nil
        }
    }

    private func file(for appId: String) -> URL {
        let safe = appId.map { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" ? String($0) : "_" }.joined()
        return folder.appendingPathComponent(safe + ".png")
    }
}
