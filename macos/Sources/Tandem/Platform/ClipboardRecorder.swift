import AppKit

/// Watches the pasteboard for the history. Separate from `ClipboardMonitor`, which only runs while a device is
/// connected and only cares about text: the history is for everything that is copied, whoever is there to hear it.
///
/// macOS has no notification for a pasteboard change, so this reads the change counter, which is a cheap integer.
/// It does not run at all while the history is off.
@MainActor
final class ClipboardRecorder {
    static let shared = ClipboardRecorder()

    private var lastCount = NSPasteboard.general.changeCount
    private var timer: Timer?

    /// Starts or stops with the setting.
    func refresh() {
        if ClipboardHistory.shared.enabled {
            guard timer == nil else { return }
            lastCount = NSPasteboard.general.changeCount
            timer = Timer.scheduledTimer(withTimeInterval: 0.75, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.poll() }
            }
            timer?.tolerance = 0.5
        } else {
            timer?.invalidate()
            timer = nil
        }
    }

    /// What this app has just put on the pasteboard itself is not a new copy.
    func ignoreCurrent() {
        lastCount = NSPasteboard.general.changeCount
    }

    private func poll() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastCount else { return }
        lastCount = pasteboard.changeCount

        let history = ClipboardHistory.shared
        let types = Set((pasteboard.types ?? []).map(\.rawValue))
        // Secrets, one-time things and what an app made by itself.
        guard types.isDisjoint(with: ClipboardMonitor.concealed) else { return }
        // Files copied in Finder come with their names as text, which is noise.
        guard !types.contains(NSPasteboard.PasteboardType.fileURL.rawValue) else { return }

        let front = NSWorkspace.shared.frontmostApplication
        // Some apps say which app a copy is from (the nspasteboard.org convention); that beats guessing.
        let declared = pasteboard.string(forType: NSPasteboard.PasteboardType("org.nspasteboard.source"))
        let bundle = declared ?? front?.bundleIdentifier
        if let bundle, history.ignoredApps.contains(bundle) { return }
        let name = bundle.map { $0 == front?.bundleIdentifier ? (front?.localizedName ?? history.name(ofApp: $0)) : history.name(ofApp: $0) }

        if let text = pasteboard.string(forType: .string), !text.isEmpty {
            guard text.utf8.count <= 1_000_000 else { return }
            history.record(text: text, app: bundle, appName: name)
        } else if history.recordsImages, let (data, isPNG) = Self.imageData(on: pasteboard) {
            Task.detached(priority: .utility) {
                guard let prepared = ClipImage.prepare(data: data, isPNG: isPNG) else { return }
                await MainActor.run {
                    ClipboardHistory.shared.record(
                        image: prepared.png, thumbnail: prepared.thumbnail,
                        width: prepared.width, height: prepared.height, hash: prepared.hash,
                        app: bundle, appName: name
                    )
                }
            }
        }
    }

    private static func imageData(on pasteboard: NSPasteboard) -> (Data, Bool)? {
        if let png = pasteboard.data(forType: .png) { return (png, true) }
        if let tiff = pasteboard.data(forType: .tiff) { return (tiff, false) }
        return nil
    }
}
