import AppKit
import Foundation
import NetFS
import Observation
import TandemCore

/// The files of a device as a drive in Finder.
///
/// The core serves them as WebDAV on this Mac, on the loopback address and behind a password made for the occasion, and
/// the system mounts that address like any network drive. Ejecting stops the serving too, and a drive is ejected before
/// its device goes away or the app quits, because a drive whose server is gone makes everything that touches it wait.
@MainActor @Observable
final class DriveMount {
    enum State: Equatable {
        case off
        case mounting
        case mounted(URL)
        case failed(String)
    }

    private(set) var states: [String: State] = [:]

    private static let remembered = "mountedDrives"

    /// The drives of an earlier run that is gone, which are still there but have no server: they go, so Finder is not
    /// left with a drive that fails at everything.
    static func removeLeftovers() {
        let paths = UserDefaults.standard.stringArray(forKey: remembered) ?? []
        guard !paths.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            for path in paths where isMounted(path) { unmount(URL(fileURLWithPath: path)) }
            UserDefaults.standard.removeObject(forKey: remembered)
        }
    }

    /// Read from the table of mounts, which asks no drive anything.
    private nonisolated static func isMounted(_ path: String) -> Bool {
        let volumes = FileManager.default.mountedVolumeURLs(includingResourceValuesForKeys: [], options: []) ?? []
        return volumes.contains { $0.path == path }
    }

    private func remember(_ paths: [String]) {
        UserDefaults.standard.set(paths, forKey: Self.remembered)
    }

    private func mountedPaths() -> [String] {
        states.values.compactMap { if case let .mounted(url) = $0 { url.path } else { nil } }
    }

    func state(of device: String) -> State { states[device] ?? .off }

    func mount(device: TandemDevice, engine: TandemEngine) {
        guard !isBusy(device.id) else { return }
        states[device.id] = .mounting
        Task {
            do {
                let share = try await engine.serveFilesAsDrive(id: device.id, name: device.name)
                guard let url = URL(string: share.url) else { throw TandemError.Failed(reason: "no address") }
                let mounted = try await Task.detached { try Self.mountSync(url: url, user: share.user, password: share.password) }.value
                // Nothing in here may touch the drive itself: a program that reads a network volume has to be allowed to by the
                // person, in a window of the system, and until then it waits. Finder is allowed already, so it is left to Finder.
                states[device.id] = .mounted(mounted)
                remember(mountedPaths())
            } catch {
                engine.stopServingFilesAsDrive(id: device.id)
                states[device.id] = .failed(error.localizedDescription)
            }
        }
    }

    /// Takes the drive away. The system asks every program that may care whether that is all right, this one too, so the
    /// waiting is done off the main thread, which has to be free to answer.
    func eject(device: String, engine: TandemEngine?) async {
        if case let .mounted(url) = state(of: device) {
            // When it does not work the drive stays until it is ejected in Finder, and the next start tries again.
            _ = await Task.detached { Self.unmount(url) }.value
        }
        engine?.stopServingFilesAsDrive(id: device)
        states[device] = .off
        remember(mountedPaths())
    }

    /// Before the app quits: every drive goes, so no volume is left that nothing answers for.
    func ejectAll(engine: TandemEngine?) async {
        for device in Array(states.keys) { await eject(device: device, engine: engine) }
    }

    /// Takes the drive away, and by force when it is in use, waiting a few seconds for each try at most: what has the
    /// drive open must not keep the drive, and a window that waits for a drive that has no answer must not wait for ever.
    /// `umount` does it at once for a drive that answers, where `diskutil` took the whole time and then gave up.
    @discardableResult
    private nonisolated static func unmount(_ url: URL) -> Bool {
        run("/sbin/umount", [url.path]) || run("/sbin/umount", ["-f", url.path])
    }

    private nonisolated static func run(_ tool: String, _ arguments: [String]) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        let done = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in done.signal() }
        guard (try? process.run()) != nil else { return false }
        if done.wait(timeout: .now() + 3) == .timedOut {
            process.terminate()
            return false
        }
        return process.terminationStatus == 0
    }

    private func isBusy(_ device: String) -> Bool {
        switch state(of: device) {
        case .mounting, .mounted: true
        case .off, .failed: false
        }
    }

    /// Asks the system to mount the address. It waits for the answer, so it runs off the main thread.
    private nonisolated static func mountSync(url: URL, user: String, password: String) throws -> URL {
        var mountpoints: Unmanaged<CFArray>?
        // No window of the system for a password: it is given, and nothing is to be asked of a person.
        let options = NSMutableDictionary()
        options[kNAUIOptionKey as String] = kNAUIOptionNoUI
        let status = NetFSMountURLSync(url as CFURL, nil, user as CFString, password as CFString, options as CFMutableDictionary, nil, &mountpoints)
        guard status == 0 || status == EEXIST else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(status), userInfo: [NSLocalizedDescriptionKey: String(cString: strerror(status))])
        }
        // What comes back is a list of the places it was mounted at: as text, or as addresses, depending on the system.
        if let list = mountpoints?.takeRetainedValue() as? [Any] {
            for item in list {
                if let path = item as? String, !path.isEmpty { return URL(fileURLWithPath: path) }
                if let url = item as? URL { return url }
            }
        }
        // Not told: it is the folder of that name among the drives, which is where the system puts it.
        let name = url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
        let drives = FileManager.default.mountedVolumeURLs(includingResourceValuesForKeys: [], options: []) ?? []
        if let found = drives.first(where: { $0.lastPathComponent == name }) { return found }
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(ENOENT), userInfo: [NSLocalizedDescriptionKey: "The drive opened, but not where it was expected."])
    }
}
