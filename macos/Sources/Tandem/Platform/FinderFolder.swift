import AppKit
import CoreServices

/// The folder of the Finder window in front, for files dropped on the edge towards this Mac (R-D16). Asked through `osascript` with a
/// time limit, off the main thread: `NSAppleScript` belongs on the main thread, and could hang there. The question whether Tandem may use
/// Finder comes when a computer is allowed to use this Mac, not the first time in the middle of a drag.
enum FinderFolder {
    enum Access {
        case allowed, notAsked, refused
    }

    nonisolated static func access(ask: Bool) -> Access {
        var target = AEAddressDesc()
        let bundle = "com.apple.finder"
        let made = bundle.withCString { AECreateDesc(typeApplicationBundleID, $0, strlen($0), &target) }
        guard made == noErr else { return .refused }
        defer { AEDisposeDesc(&target) }
        switch AEDeterminePermissionToAutomateTarget(&target, typeWildCard, typeWildCard, ask) {
        case noErr: return .allowed
        case OSStatus(errAEEventWouldRequireUserConsent): return .notAsked
        default: return .refused
        }
    }

    /// Asks once, in the background, when the answer is not known yet.
    @MainActor static func askAhead() {
        Task.detached(priority: .utility) {
            if access(ask: false) == .notAsked { _ = access(ask: true) }
        }
    }

    /// The folder of the front Finder window, or nil (no window, not allowed, not asked yet, or no answer within `timeout`).
    nonisolated static func front(timeout: TimeInterval = 2) -> URL? {
        guard access(ask: false) == .allowed else { return nil }
        let script = """
        tell application "Finder"
            if (count of Finder windows) > 0 then
                return POSIX path of (target of front Finder window as alias)
            end if
        end tell
        return ""
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        let done = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in done.signal() }
        do { try process.run() } catch { return nil }
        if done.wait(timeout: .now() + timeout) == .timedOut {
            process.terminate()
            return nil
        }
        let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard process.terminationStatus == 0, !text.isEmpty, FileManager.default.fileExists(atPath: text) else { return nil }
        return URL(fileURLWithPath: text, isDirectory: true)
    }
}
