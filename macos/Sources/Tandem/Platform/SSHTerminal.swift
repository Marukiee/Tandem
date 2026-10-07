import AppKit
import Network
import SwiftTerm
import SwiftUI
import TandemCore

/// Logging in to another computer of the circle from inside Tandem (see docs/ROADMAP.md, 4c). The login itself is the ssh that
/// comes with the system, in a terminal of the app, so keys, the agent and the known hosts are the ones the person already has.
/// The button is grey, with the reason, until the computer answers on the ssh port.
enum SSHAccess {
    /// The address of this device that answers on port 22, if any answers.
    @MainActor static func reachableAddress(of device: TandemDevice) async -> String? {
        guard device.online, device.platform != .android, device.platform != .ios else { return nil }
        let ips = EngineModel.shared.tandem?.deviceIps(id: device.id) ?? []
        // All at once, so one that does not answer costs a second and a half and not a second and a half each.
        let candidates = Array(ips.prefix(4))
        let open = await withTaskGroup(of: (Int, Bool).self) { group in
            for (index, ip) in candidates.enumerated() {
                group.addTask { (index, await portOpen(ip, 22)) }
            }
            var answered: [Int] = []
            for await (index, ok) in group where ok { answered.append(index) }
            return answered.min()
        }
        return open.map { candidates[$0] }
    }

    /// Why the button is grey, in words that say what to do.
    static func reason(for device: TandemDevice) -> String {
        if !device.online { return String(localized: "The computer has to be online to log in to it") }
        switch device.platform {
        case .macOs: return String(localized: "Turn on Remote Login on that Mac, in System Settings under General and Sharing, to log in to it here")
        case .windows: return String(localized: "Install the OpenSSH Server on that PC, in Windows under Optional features, to log in to it here")
        case .linux: return String(localized: "Start the SSH server (sshd) on that computer to log in to it here")
        default: return String(localized: "This device does not offer SSH")
        }
    }

    nonisolated static func portOpen(_ ip: String, _ port: UInt16) async -> Bool {
        await withCheckedContinuation { continuation in
            let connection = NWConnection(host: NWEndpoint.Host(ip), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            let lock = NSLock()
            nonisolated(unsafe) var done = false
            let finish: @Sendable (Bool) -> Void = { ok in
                lock.lock()
                defer { lock.unlock() }
                guard !done else { return }
                done = true
                connection.cancel()
                continuation.resume(returning: ok)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .cancelled: finish(false)
                default: break
                }
            }
            connection.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) { finish(false) }
        }
    }

    /// Asks who to log in as (the name is kept for the next time), then opens the terminal.
    @MainActor static func open(_ device: TandemDevice, at address: String) {
        let key = "sshUser.\(device.id)"
        let alert = NSAlert()
        alert.messageText = String(localized: "Log in to \(device.name)")
        alert.informativeText = String(localized: "The name of the account on that computer.")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 240, height: 24))
        field.stringValue = UserDefaults.standard.string(forKey: key) ?? NSUserName()
        alert.accessoryView = field
        alert.addButton(withTitle: String(localized: "Log in"))
        alert.addButton(withTitle: String(localized: "Cancel"))
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let user = field.stringValue.trimmingCharacters(in: .whitespaces)
        guard !user.isEmpty else { return }
        UserDefaults.standard.set(user, forKey: key)
        SSHTerminalWindow.show(title: device.name, user: user, host: address)
    }
}

/// A window with one login in it. It stays open after the login ends, so what was printed can be read, and goes when it is closed.
@MainActor
final class SSHTerminalWindow: NSObject, NSWindowDelegate, LocalProcessTerminalViewDelegate {
    private static var open: [SSHTerminalWindow] = []

    private let window: NSWindow
    private let terminal: LocalProcessTerminalView

    static func show(title: String, user: String, host: String) {
        let one = SSHTerminalWindow(title: title, user: user, host: host)
        open.append(one)
        one.window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private init(title: String, user: String, host: String) {
        terminal = LocalProcessTerminalView(frame: NSRect(x: 0, y: 0, width: 820, height: 520))
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 820, height: 520),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false
        )
        super.init()
        window.title = "\(user)@\(title)"
        window.contentView = terminal
        window.isReleasedWhenClosed = false
        window.center()
        window.delegate = self
        terminal.processDelegate = self
        terminal.font = NSFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        terminal.nativeBackgroundColor = NSColor(calibratedWhite: 0.07, alpha: 1)
        terminal.nativeForegroundColor = NSColor(calibratedWhite: 0.92, alpha: 1)
        // A host that is new is trusted the first time and then pinned, which is what ssh does for a person who answers yes.
        terminal.startProcess(
            executable: "/usr/bin/ssh",
            args: ["-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=20", "\(user)@\(host)"],
            environment: nil, execName: nil
        )
    }

    nonisolated func windowWillClose(_ notification: Notification) {
        Task { @MainActor in
            Self.open.removeAll { $0 === self }
        }
    }

    nonisolated func sizeChanged(source: LocalProcessTerminalView, newCols: Int, newRows: Int) {}
    nonisolated func setTerminalTitle(source: LocalProcessTerminalView, title: String) {}
    nonisolated func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    nonisolated func processTerminated(source: TerminalView, exitCode: Int32?) {
        Task { @MainActor in
            terminal.feed(text: "\r\n[" + String(localized: "The connection ended") + "]\r\n")
        }
    }
}
