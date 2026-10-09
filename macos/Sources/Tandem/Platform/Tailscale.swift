import AppKit
import Foundation

/// Turns Tailscale on when a device of the circle cannot be reached on the network this Mac is on, and Tailscale is what would reach
/// it. The core says so only for a device that does not answer on the local network, that is known by a Tailscale address, while this
/// Mac has none of its own, so a device on the same network never gets here. Only `tailscale up` is run: the sign-in and the network
/// stay Tailscale's business.
@MainActor
final class TailscaleAuto {
    static let shared = TailscaleAuto()
    static let enabledKey = "autoTailscale"

    private var busy = false

    /// On unless the person turned it off.
    static var enabled: Bool {
        UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true
    }

    func needed(for device: String) {
        guard Self.enabled, !busy else { return }
        busy = true
        let name = EngineModel.shared.device(device)?.name ?? String(localized: "a device")
        Task.detached(priority: .utility) {
            let outcome = Self.turnOn()
            await MainActor.run {
                TailscaleAuto.shared.busy = false
                switch outcome {
                case .turnedOn:
                    EngineModel.shared.showToast(String(localized: "\(name) could not be reached on this network, so Tandem turned Tailscale on"))
                case .needsLogin:
                    EngineModel.shared.showToast(String(localized: "Sign in to Tailscale to reach \(name)"))
                case .nothing:
                    break
                }
            }
        }
    }

    enum Outcome { case turnedOn, needsLogin, nothing }

    /// The command line tool: the one inside the app of Tailscale, or one that was installed with a package manager.
    nonisolated private static func cli() -> String? {
        let candidates = [
            "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/opt/homebrew/bin/tailscale",
            "/usr/local/bin/tailscale",
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    nonisolated private static func run(_ path: String, _ args: [String]) -> (ok: Bool, text: String)? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        process.standardInput = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        // A time limit: a sign-in that is asked for must not keep this waiting for ever.
        let deadline = Date().addingTimeInterval(20)
        while process.isRunning, Date() < deadline { Thread.sleep(forTimeInterval: 0.1) }
        if process.isRunning { process.terminate() }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return (process.terminationStatus == 0, String(decoding: data, as: UTF8.self))
    }

    /// What `tailscale status --json` says about the state of Tailscale.
    nonisolated static func state(of json: String) -> String? {
        guard let data = json.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return object["BackendState"] as? String
    }

    nonisolated private static func turnOn() -> Outcome {
        guard let cli = cli(), let status = run(cli, ["status", "--json"]) else { return .nothing }
        switch state(of: status.text) {
        case "Stopped":
            guard let up = run(cli, ["up", "--timeout=15s"]) else { return .nothing }
            return up.ok ? .turnedOn : .nothing
        case "NeedsLogin", "NeedsMachineAuth":
            return .needsLogin
        default:
            return .nothing
        }
    }
}
