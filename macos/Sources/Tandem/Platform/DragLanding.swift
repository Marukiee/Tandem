import AppKit
import TandemCore

/// Files that another computer dropped on the edge of its screen, to this one. The computer shares its pointer with this Mac, so it is
/// the person who put them here, at that moment: they are taken without asking and put where the drop was, which is the folder of the
/// window of Finder in front, or else the Desktop.
@MainActor
final class DragLanding {
    static let shared = DragLanding()

    private var offers: [UInt64: String] = [:]

    /// An offer arrived with the drag origin. Taken only from a computer that shares its pointer with this one.
    func take(offer: UInt64, from device: String) -> Bool {
        guard PointerShare.shared.isPeer(device), let engine = EngineModel.shared.tandem else { return false }
        offers[offer] = device
        if offers.count > 40 { offers.removeValue(forKey: offers.keys.min() ?? offer) }
        do {
            try engine.acceptOffer(from: device, offer: offer)
            return true
        } catch {
            offers.removeValue(forKey: offer)
            return false
        }
    }

    /// A file of such an offer is in. True when it was one of ours and was dealt with here.
    func finished(offer: UInt64, from device: String, name: String, location: String?, error: String?) -> Bool {
        guard offers[offer] == device else { return false }
        let from = EngineModel.shared.device(device)?.name ?? "?"
        guard error == nil, let location else {
            EngineModel.shared.showToast(String(localized: "A file from \(from) did not come in"))
            return true
        }
        // Asking Finder which folder is in front can take a moment (or a question on the first time), so it is not done on the main thread.
        Task { @MainActor in
            let folder = await Task.detached(priority: .userInitiated) { Self.dropFolder() }.value
            let placed = Self.place(URL(fileURLWithPath: location), in: folder)
            EngineModel.shared.showToast(String(localized: "\(name) from \(from) is in \(placed.deletingLastPathComponent().lastPathComponent)"))
            // Said where it is: the file is shown in Finder, so it is easy to find.
            NSWorkspace.shared.activateFileViewerSelecting([placed])
        }
        return true
    }

    /// Moves the file to the folder where it was dropped, with a name that is free there.
    static func place(_ source: URL, in folder: URL) -> URL {
        let manager = FileManager.default
        var target = folder.appendingPathComponent(source.lastPathComponent)
        var n = 2
        while manager.fileExists(atPath: target.path) {
            let base = source.deletingPathExtension().lastPathComponent
            let ext = source.pathExtension
            target = folder.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        do {
            try manager.moveItem(at: source, to: target)
            return target
        } catch {
            return source
        }
    }

    /// The folder of the window of Finder that is in front, when there is one that shows a folder, or else the Desktop.
    nonisolated private static func dropFolder() -> URL {
        let script = NSAppleScript(source: """
        tell application "Finder"
            if (count of Finder windows) > 0 then
                return POSIX path of (target of front Finder window as alias)
            end if
        end tell
        return ""
        """)
        var problem: NSDictionary?
        let text = script?.executeAndReturnError(&problem).stringValue ?? ""
        if !text.isEmpty, FileManager.default.fileExists(atPath: text) { return URL(fileURLWithPath: text, isDirectory: true) }
        return FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask).first ?? FileManager.default.homeDirectoryForCurrentUser
    }
}
