import AppKit
import Darwin

/// A screenshot taken on this Mac goes to the clipboards of the devices that share the clipboard with it, as a copied picture does.
/// macOS says nothing when it takes one, so the folder it saves them in is watched (`com.apple.screencapture`, the Desktop unless
/// changed), and a new file counts when macOS marked it as a screen capture, whatever language its name is in. A screenshot taken
/// with Control goes to the pasteboard instead, and from there through `ClipboardFiles` like any other copy.
@MainActor
final class ScreenshotSender {
    static let shared = ScreenshotSender()
    static let key = "sendScreenshots"

    var enabled: Bool {
        get { UserDefaults.standard.object(forKey: Self.key) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: Self.key); refresh() }
    }

    private var source: DispatchSourceFileSystemObject?
    private var folder: URL?
    /// What was in the folder already, and what was sent: not news.
    private var known: Set<String> = []
    private var scanPending = false

    /// Watches only while it is on and a device that shares the clipboard is there. Watching the Desktop is what asks for access to
    /// it, so nobody is asked who does not use this.
    func refresh() {
        let wanted = enabled && EngineModel.shared.devices.contains { $0.online && $0.clipboardEnabled }
        let target = wanted ? Self.screenshotFolder() : nil
        guard target != folder else { return }
        stop()
        if let target { start(target) }
    }

    private func start(_ url: URL) {
        let descriptor = open(url.path, O_EVTONLY)
        guard descriptor >= 0 else { return }
        folder = url
        known = Set(Self.names(in: url))
        let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: descriptor, eventMask: [.write, .rename], queue: .main)
        source.setEventHandler { [weak self] in
            MainActor.assumeIsolated { self?.scheduleScan(after: 0.15) }
        }
        source.setCancelHandler { close(descriptor) }
        source.resume()
        self.source = source
    }

    private func stop() {
        source?.cancel()
        source = nil
        folder = nil
        known = []
    }

    private func scheduleScan(after delay: TimeInterval) {
        guard !scanPending else { return }
        scanPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            MainActor.assumeIsolated {
                self?.scanPending = false
                self?.scan()
            }
        }
    }

    private func scan() {
        guard let folder else { return }
        var unsure = false
        for name in Self.names(in: folder) where !known.contains(name) {
            let file = folder.appendingPathComponent(name)
            guard Self.isPicture(file) else { known.insert(name); continue }
            let created = (try? file.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
            // A file that was moved or renamed into the folder is not a new screenshot.
            guard Date().timeIntervalSince(created) < 30 else { known.insert(name); continue }
            guard Self.isScreenCapture(file) else {
                // The mark may come a moment after the file: looked at once more before it is let go.
                if Date().timeIntervalSince(created) < 2 { unsure = true } else { known.insert(name) }
                continue
            }
            known.insert(name)
            send(file)
        }
        if unsure { scheduleScan(after: 0.6) }
    }

    private func send(_ file: URL) {
        let model = EngineModel.shared
        let targets = model.devices.filter { $0.online && $0.clipboardEnabled }.map(\.id)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? .max
        // As large as the other side takes for its clipboard without asking.
        guard enabled, !targets.isEmpty, size <= ClipboardFiles.filesLimit else { return }
        model.send(urls: [file], to: targets, origin: .clipboard)
    }

    // MARK: Where and what

    /// Where macOS saves screenshots, or nil when it puts them on the clipboard, in an app, or on a network volume (which would
    /// keep this waiting for an answer from that server).
    static func screenshotFolder() -> URL? {
        if let debug = ProcessInfo.processInfo.environment["TANDEM_DEBUG_SCREENSHOTS"], !debug.isEmpty {
            return URL(fileURLWithPath: debug, isDirectory: true)
        }
        let domain = "com.apple.screencapture" as CFString
        CFPreferencesAppSynchronize(domain)
        // `target-screenshot` since macOS 26, `target` before; "file" or nothing is a file.
        let target = (CFPreferencesCopyAppValue("target-screenshot" as CFString, domain) as? String)
            ?? (CFPreferencesCopyAppValue("target" as CFString, domain) as? String)
        if let target, !target.isEmpty, target != "file" { return nil }
        let path = (CFPreferencesCopyAppValue("location" as CFString, domain) as? String).map { ($0 as NSString).expandingTildeInPath }
        let folder = path.flatMap { $0.isEmpty ? nil : URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask).first
        guard let folder, isLocal(folder.path) else { return nil }
        return folder
    }

    /// From the list of mounts, which the system keeps without asking any volume.
    private static func isLocal(_ path: String) -> Bool {
        var mounts: UnsafeMutablePointer<statfs>?
        let count = getmntinfo(&mounts, MNT_NOWAIT)
        guard count > 0, let mounts else { return true }
        var best: (length: Int, local: Bool) = (0, true)
        for index in 0..<Int(count) {
            var entry = mounts[index]
            let mountPoint = withUnsafePointer(to: &entry.f_mntonname) { $0.withMemoryRebound(to: CChar.self, capacity: Int(MAXPATHLEN)) { String(cString: $0) } }
            let inside = mountPoint == "/" || path == mountPoint || path.hasPrefix(mountPoint + "/")
            if inside, mountPoint.count > best.length {
                best = (mountPoint.count, entry.f_flags & UInt32(MNT_LOCAL) != 0)
            }
        }
        return best.local
    }

    private static func names(in folder: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).filter { !$0.hasPrefix(".") }
    }

    private static func isPicture(_ file: URL) -> Bool {
        ["png", "jpg", "jpeg", "heic", "tif", "tiff", "gif", "bmp"].contains(file.pathExtension.lowercased())
    }

    /// macOS puts this mark on every screenshot it saves. It stays when the file is renamed, so it says what the file is.
    static func isScreenCapture(_ file: URL) -> Bool {
        getxattr(file.path, "com.apple.metadata:kMDItemIsScreenCapture", nil, 0, 0, 0) > 0
    }
}
