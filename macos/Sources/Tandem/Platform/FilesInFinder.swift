import AppKit
import FileProvider
import Foundation
import Observation
import TandemCore

/// The files of a phone as a fixed place in the Finder sidebar, through the File Provider extension (`TandemFiles.appex`).
///
/// Each phone that has it turned on is a domain of the system, which stays in the sidebar until it is turned off or the
/// phone is removed. The extension reaches the phone through the same WebDAV server on the loopback address as the
/// mounted drive, and this keeps that server running while the phone is online. Its address and password go into the app
/// group, which the extension reads for every request: the server gets a new port and password every time it starts, and
/// a domain's own `userInfo` only reaches an extension when it starts. When the phone is away the file is gone, and the
/// extension tells Finder the phone cannot be reached instead of making it wait.
///
/// Only a build signed with the Developer ID has the extension (scripts/build-macos.sh). Without it, or when the system
/// refuses the domain, the files page uses the mounted drive (`DriveMount`).
@MainActor @Observable
final class FilesInFinder {
    static let shared = FilesInFinder()

    enum State: Equatable {
        case off
        case adding
        case on
    }

    private(set) var states: [String: State] = [:]
    /// Why the system would not take a domain. This run then uses the drive.
    private(set) var refusal: String?

    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var loading = false
    @ObservationIgnored private var names: [String: String] = [:]
    /// The phones whose server runs for Finder now, with its address.
    @ObservationIgnored private var served: [String: String] = [:]
    @ObservationIgnored private var starting: Set<String> = []
    @ObservationIgnored private var lastTry: [String: Date] = [:]

    static let embedded: Bool = {
        guard let plugins = Bundle.main.builtInPlugInsURL else { return false }
        return FileManager.default.fileExists(atPath: plugins.appendingPathComponent("TandemFiles.appex").path)
    }()

    var available: Bool { Self.embedded && refusal == nil && folder != nil }

    /// Only asked for when the extension is there: the app has the group then, and without it macOS would ask the person.
    @ObservationIgnored private lazy var folder: URL? = {
        guard Self.embedded, let bundle = Bundle.main.bundleIdentifier else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "X8Y2M32MNQ.\(bundle)")?
            .appendingPathComponent("Files", isDirectory: true)
    }()

    func state(of device: String) -> State { states[device] ?? .off }

    // MARK: Following the devices

    /// Called whenever the devices change. Cheap when nothing is due.
    func devicesChanged(_ devices: [TandemDevice], engine: TandemEngine?) {
        // Before the engine runs there is no list of devices, which is not the same as every phone gone.
        guard Self.embedded, let engine else { return }
        guard loaded else {
            load(devices, engine: engine)
            return
        }
        let byID = Dictionary(devices.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for (id, state) in states where state == .on {
            guard let device = byID[id] else {
                // The phone was removed from the circle: its place in Finder goes with it.
                Task { await remove(id, engine: engine) }
                continue
            }
            if names[id] != device.name { rename(device) }
            if device.online {
                if served[id] == nil { serve(device, engine: engine) }
            } else if served[id] != nil {
                stopServing(id, engine: engine)
            }
        }
    }

    /// What the system already has, from an earlier run. Old addresses go first: that server stopped with the app.
    private func load(_ devices: [TandemDevice], engine: TandemEngine) {
        guard !loading else { return }
        loading = true
        if let folder, let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
            for file in files where file.lastPathComponent.hasSuffix(".json") && !file.lastPathComponent.hasSuffix(".ids.json") {
                try? FileManager.default.removeItem(at: file)
            }
        }
        Task {
            let domains = (try? await NSFileProviderManager.domains()) ?? []
            for domain in domains {
                states[domain.identifier.rawValue] = .on
                names[domain.identifier.rawValue] = domain.displayName
            }
            loaded = true
            loading = false
            devicesChanged(EngineModel.shared.devices, engine: EngineModel.shared.tandem)
        }
    }

    private func serve(_ device: TandemDevice, engine: TandemEngine) {
        let id = device.id
        guard !starting.contains(id) else { return }
        if let last = lastTry[id], Date().timeIntervalSince(last) < 30 { return }
        starting.insert(id)
        lastTry[id] = Date()
        Task {
            defer { starting.remove(id) }
            guard let share = try? await engine.serveFilesAsDrive(id: id, name: device.name), state(of: id) == .on else { return }
            do {
                try write(DriveAddress(url: share.url, user: share.user, password: share.password), for: id)
            } catch {
                engine.stopServingFilesAsDrive(id: id)
                return
            }
            served[id] = share.url
            lastTry[id] = nil
            // Finder looks again at the folders that were open, which may have changed on the phone while it was away.
            try? await NSFileProviderManager(for: Self.domain(id: id, name: device.name))?.signalEnumerator(for: .workingSet)
        }
    }

    private func stopServing(_ id: String, engine: TandemEngine?) {
        if let file = folder?.appendingPathComponent("\(id).json") { try? FileManager.default.removeItem(at: file) }
        if served.removeValue(forKey: id) != nil { engine?.stopServingFilesAsDrive(id: id) }
    }

    /// Before the app quits: the servers stop with the engine, so their addresses go. The places in Finder stay.
    func stopAll(engine: TandemEngine?) {
        for id in Array(served.keys) { stopServing(id, engine: engine) }
    }

    private func rename(_ device: TandemDevice) {
        names[device.id] = device.name
        // Adding a domain that is there already changes its name and nothing else.
        Task { try? await NSFileProviderManager.add(Self.domain(id: device.id, name: device.name)) }
    }

    // MARK: Turning it on and off

    /// Puts the phone in the Finder sidebar and opens it. False when the system refused, with the reason in `refusal`.
    func turnOn(_ device: TandemDevice, engine: TandemEngine) async -> Bool {
        states[device.id] = .adding
        do {
            try await NSFileProviderManager.add(Self.domain(id: device.id, name: device.name))
        } catch {
            states[device.id] = .off
            refusal = error.localizedDescription
            return false
        }
        states[device.id] = .on
        names[device.id] = device.name
        lastTry[device.id] = nil
        if device.online { serve(device, engine: engine) }
        open(device)
        return true
    }

    func turnOff(_ device: TandemDevice, engine: TandemEngine?) async {
        await remove(device.id, engine: engine)
    }

    private func remove(_ id: String, engine: TandemEngine?) async {
        stopServing(id, engine: engine)
        states[id] = .off
        names[id] = nil
        try? await NSFileProviderManager.remove(Self.domain(id: id, name: id))
        if let file = folder?.appendingPathComponent("\(id).ids.json") { try? FileManager.default.removeItem(at: file) }
    }

    func open(_ device: TandemDevice) {
        Task {
            guard let manager = NSFileProviderManager(for: Self.domain(id: device.id, name: device.name)),
                  let url = try? await manager.getUserVisibleURL(for: .rootContainer) else { return }
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: Helpers

    private static func domain(id: String, name: String) -> NSFileProviderDomain {
        NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier(rawValue: id), displayName: name)
    }

    /// The same shape the extension reads (`DavCredentials` in macos/FileProviderExtension).
    private struct DriveAddress: Codable {
        var url: String
        var user: String
        var password: String
    }

    private func write(_ address: DriveAddress, for id: String) throws {
        guard let folder else { throw CocoaError(.fileNoSuchFile) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("\(id).json")
        try JSONEncoder().encode(address).write(to: file, options: [.atomic])
        // It holds a password, if only one for this Mac's own loopback address.
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
}
