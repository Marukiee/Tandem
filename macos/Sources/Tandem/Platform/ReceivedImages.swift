import AppKit
import ApplicationServices
import UniformTypeIdentifiers

/// What happens to a picture that arrives from a phone: it can be put on the clipboard, and
/// pasted where the cursor is. Both are settings, so nobody gets a paste they did not ask for.
enum ReceivedImages {
    static let copyKey = "copyReceivedImages"
    static let pasteKey = "pasteReceivedImages"

    static var copyEnabled: Bool { UserDefaults.standard.object(forKey: copyKey) as? Bool ?? true }
    static var pasteEnabled: Bool { UserDefaults.standard.bool(forKey: pasteKey) }

    static func isImage(_ url: URL) -> Bool {
        UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) ?? false
    }

    /// Puts the picture on the clipboard. Returns whether it did.
    @MainActor
    static func copy(_ url: URL) -> Bool {
        guard copyEnabled, isImage(url), let image = NSImage(contentsOf: url) else { return false }
        let board = NSPasteboard.general
        board.clearContents()
        board.writeObjects([image])
        if pasteEnabled { pasteSoon() }
        return true
    }

    /// Command V, a moment after the clipboard changed so the app in front has seen it. Needs
    /// the same Accessibility permission as the trackpad.
    @MainActor
    private static func pasteSoon() {
        guard AXIsProcessTrusted() else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            let source = CGEventSource(stateID: .combinedSessionState)
            for down in [true, false] {
                let event = CGEvent(keyboardEventSource: source, virtualKey: 9, keyDown: down) // V
                event?.flags = .maskCommand
                event?.post(tap: .cghidEventTap)
            }
        }
    }
}
