import AppKit
import Foundation
import Observation
import TandemCore

struct TransferItem: Identifiable, Equatable {
    enum State { case active, done, failed }

    let id: String
    let peer: String
    var name: String
    var done: UInt64
    var total: UInt64
    let incoming: Bool
    var state: State
    var location: String?
    var error: String?
    var started: Date
    var updated: Date
    /// Bytes per second, smoothed so the number does not jitter.
    var speed: Double = 0

    var fraction: Double {
        if total == 0 { return state == .done ? 1 : 0 }
        return min(1, Double(done) / Double(total))
    }
}

struct PairingState: Equatable {
    var uri: String?
    var expiresAt: Date?
    var joinedName: String?
    var error: String?
    var busy = false
}

@MainActor
@Observable
final class EngineModel {
    static let shared = EngineModel()

    var devices: [TandemDevice] = []
    var transfers: [TransferItem] = []
    var pairing = PairingState()
    var myName = EngineModel.savedName ?? Host.current().localizedName ?? "Mac"
    var myId = ""
    var ready = false
    var startError: String?
    var removedFromCircle = false
    var isOnline = true
    var hotspotStatus: HotspotStatus = .idle
    /// A short message shown as a toast at the bottom of the window.
    var toast: String?
    /// What your phones have shown as notifications, newest first.
    var mirrored: [MirroredNotification] = []
    /// The icon a person picked for a device, by device id. A device without one uses its platform's.
    var deviceIcons: [String: String] = UserDefaults.standard.dictionary(forKey: "deviceIcons") as? [String: String] ?? [:]
    /// A received file the person asked to move to the Trash, waiting for a yes.
    var pendingTrash: TransferItem?

    nonisolated static let nameKey = "deviceName"
    /// The name the person chose in Settings. Without it the Mac's own name is used.
    nonisolated static var savedName: String? {
        UserDefaults.standard.string(forKey: nameKey).flatMap { $0.isEmpty ? nil : $0 }
    }

    @ObservationIgnored private var engine: TandemEngine?
    @ObservationIgnored private var eventTask: Task<Void, Never>?
    @ObservationIgnored private var continuation: AsyncStream<TandemEvent>.Continuation?
    @ObservationIgnored private var clipboard: ClipboardMonitor?
    @ObservationIgnored private let network = NetworkWatcher()
    @ObservationIgnored private let injector = InputInjector()
    @ObservationIgnored private var statusTimer: Timer?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored let hotspot = HotspotCoordinator()
    @ObservationIgnored let bleMessenger = BleMessenger()
    @ObservationIgnored private var bleWatch: Task<Void, Never>?
    /// Where a sent file came from, by the name it travels under, so a finished
    /// transfer can be opened later. The core reports no location for outgoing files.
    @ObservationIgnored private var outgoingSources: [String: String] = [:]

    var isTransferring: Bool { transfers.contains { $0.state == .active } }
    /// True while this Mac's connection is a phone's personal hotspot.
    var networkUsesHotspot: Bool { network.usesCellularHotspot }
    var onlineCount: Int { devices.filter(\.online).count }
    /// "2 of 3 connected", for the sidebar and the menu bar panel.
    var connectionSummary: String {
        String(localized: "\(onlineCount) of \(devices.count) connected")
    }

    func device(_ id: String) -> TandemDevice? { devices.first { $0.id == id } }

    // MARK: Lifecycle

    func start() {
        guard engine == nil else { return }
        Notifier.shared.setUp()
        wireNotifier()
        injector.onPermissionNeeded = { [weak self] in
            self?.showToast(String(localized: "Allow Tandem to control this Mac in System Settings, under Accessibility"))
        }

        let directory = Self.supportDirectory()
        let config = TandemConfig(
            dataDir: directory.path,
            deviceName: myName,
            platform: .macOs,
            model: Self.modelIdentifier(),
            appVersion: Bundle.main.appVersion,
            port: 47820,
            enableMdns: true,
            caps: ["clipboard", "share", "notify", "call", "input", "battery", "hotspot"],
            lowPower: false
        )

        let (stream, continuation) = AsyncStream.makeStream(of: TandemEvent.self)
        self.continuation = continuation
        let sink = EventForwarder(continuation: continuation)

        eventTask = Task { [weak self] in
            for await event in stream {
                guard let self else { return }
                self.handle(event)
            }
        }

        Task.detached(priority: .userInitiated) { [weak self] in
            tandemInitLogging(verbose: false)
            do {
                let engine = try TandemEngine.start(config: config, vault: FileVault(), files: MacFiles(), sink: sink)
                await self?.engineStarted(engine)
            } catch {
                await self?.engineFailed(error)
            }
        }
    }

    func stop() async {
        injector.releaseAll()
        clipboard?.stop()
        network.stop()
        statusTimer?.invalidate()
        continuation?.finish()
        await engine?.shutdown()
        engine = nil
    }

    private func engineStarted(_ engine: TandemEngine) {
        self.engine = engine
        myId = engine.id()
        myName = engine.name()
        // A name chosen in Settings wins over whatever the engine kept.
        if let saved = Self.savedName, saved != myName {
            myName = saved
            Task { try? await engine.renameSelf(name: saved) }
        }
        ready = true
        refreshDevices()

        let monitor = ClipboardMonitor { [weak self] text, isURL in
            self?.localClipboardChanged(text, isURL: isURL)
        }
        monitor.start()
        clipboard = monitor

        network.onChange = { [weak self] in
            guard let self else { return }
            self.isOnline = self.network.isOnline
            self.engine?.networkChanged()
            self.hotspot.networkChanged(online: self.network.isOnline)
            self.pushStatus()
        }
        network.start()

        hotspot.attach(model: self)
        startBleWatch()
        pushStatus()
        statusTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.pushStatus() }
        }
    }

    private func engineFailed(_ error: Error) {
        startError = error.localizedDescription
    }

    private func wireNotifier() {
        let notifier = Notifier.shared
        notifier.onFileAction = { path in
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        }
        notifier.onCallAction = { [weak self] device, call, action in
            Task { try? await self?.engine?.callAction(target: device, id: call, action: action) }
        }
        notifier.onMirrorAction = { [weak self] device, key, button, reply, dismiss in
            Task { try? await self?.engine?.notificationAction(target: device, key: key, button: button, reply: reply, dismiss: dismiss) }
        }
    }

    /// The dev build (bundle id ending in .dev) keeps everything apart from the real app,
    /// so testing never touches the real identity, pairings or preferences.
    nonisolated static var isDevBuild: Bool {
        Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true
    }

    nonisolated static func supportDirectory() -> URL {
        let directory = AppIdentity.dataDirectory()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func modelIdentifier() -> String? {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &buffer, &size, nil, 0)
        return String(cString: buffer)
    }

    // MARK: Events

    func refreshDevices() {
        guard let engine else { return }
        devices = engine.devices()
    }

    private func handle(_ event: TandemEvent) {
        switch event {
        case .devicesChanged, .connected, .disconnected, .circleChanged:
            refreshDevices()
            if case let .connected(id) = event { hotspot.deviceConnected(id) }
            // A phone that drops while a button is held must not leave it held here.
            if case let .disconnected(id) = event { injector.sourceDisconnected(id) }

        case let .paired(id):
            refreshDevices()
            pairing.joinedName = device(id)?.name ?? String(localized: "New device")

        case .removedFromCircle:
            removedFromCircle = true

        case let .clipboard(from, text, _):
            clipboard?.apply(text)
            let name = device(from)?.name ?? "?"
            showToast(String(localized: "Clipboard from \(name)"))

        case let .shareText(from, text, isUrl, open):
            if open, isUrl, let url = URL(string: text) {
                NSWorkspace.shared.open(url)
            } else {
                clipboard?.apply(text)
                let name = device(from)?.name ?? "?"
                showToast(String(localized: "Text from \(name) is on your clipboard"))
            }

        case let .shareOffered(from, _, _, items):
            if let device = device(from), !device.autoAccept {
                Notifier.shared.post(
                    id: "offer.\(from)",
                    title: String(localized: "\(device.name) wants to send \(items.count) file(s)"),
                    body: items.map(\.name).joined(separator: ", ")
                )
            }

        case let .progress(offer, index, peer, incoming, name, done, total):
            updateTransfer(offer: offer, index: index, peer: peer, incoming: incoming, name: name, done: done, total: total)

        case let .finished(offer, index, peer, incoming, name, size, location, error):
            finishTransfer(offer: offer, index: index, peer: peer, incoming: incoming, name: name, size: size, location: location, error: error)

        case let .notification(from, notification):
            guard device(from)?.notificationsEnabled ?? true else { return }
            let deviceName = device(from)?.name ?? "Phone"
            if let code = notification.otp, UserDefaults.standard.object(forKey: "copyCodes") as? Bool ?? true {
                clipboard?.apply(code)
                showToast(String(localized: "Code \(code) copied"))
                Notifier.shared.postCodeCopied(code: code, deviceName: deviceName, key: notification.key)
            }
            Notifier.shared.postMirrored(device: from, deviceName: deviceName, notification: notification)
            remember(notification, from: from, deviceName: deviceName)

        case let .notificationRemoved(from, key):
            Notifier.shared.remove(id: "mirror.\(from).\(key)")
            mirrored.removeAll { $0.device == from && $0.key == key }

        case let .call(from, call):
            switch call.state {
            case .ringing, .dialing:
                Notifier.shared.postCall(device: from, id: call.id, name: call.name, number: call.number, incoming: call.incoming)
            case .active, .ended, .missed:
                Notifier.shared.remove(id: "call.\(call.id)")
                if call.state == .missed {
                    let who = call.name ?? call.number ?? String(localized: "Unknown number")
                    Notifier.shared.post(id: "missed.\(call.id)", title: String(localized: "Missed call"), body: who)
                }
            }

        case let .input(from, input):
            injector.handle(input, from: from)

        case let .hotspot(from, message):
            hotspot.handle(from: from, message: message)

        case .notificationAction, .appIcon, .callAction, .dial, .ring:
            break
        }
    }

    // MARK: Transfers

    private func transferID(offer: UInt64, index: UInt32, incoming: Bool) -> String {
        "\(offer)-\(index)-\(incoming ? "in" : "out")"
    }

    private func updateTransfer(offer: UInt64, index: UInt32, peer: String, incoming: Bool, name: String, done: UInt64, total: UInt64) {
        let id = transferID(offer: offer, index: index, incoming: incoming)
        let now = Date()
        if let position = transfers.firstIndex(where: { $0.id == id }) {
            var item = transfers[position]
            let elapsed = now.timeIntervalSince(item.updated)
            if elapsed > 0.05, done >= item.done {
                let instant = Double(done - item.done) / elapsed
                item.speed = item.speed == 0 ? instant : item.speed * 0.7 + instant * 0.3
            }
            item.done = done
            item.total = total
            item.updated = now
            item.state = .active
            transfers[position] = item
        } else {
            transfers.insert(
                TransferItem(id: id, peer: peer, name: name, done: done, total: total, incoming: incoming, state: .active, started: now, updated: now),
                at: 0
            )
        }
    }

    private func finishTransfer(offer: UInt64, index: UInt32, peer: String, incoming: Bool, name: String, size: UInt64, location: String?, error: String?) {
        let id = transferID(offer: offer, index: index, incoming: incoming)
        let now = Date()
        var item = transfers.first { $0.id == id } ?? TransferItem(
            id: id, peer: peer, name: name, done: size, total: size, incoming: incoming, state: .done, started: now, updated: now
        )
        item.state = error == nil ? .done : .failed
        item.error = error
        item.location = location
        item.done = error == nil ? max(item.total, size) : item.done
        item.total = max(item.total, size)
        item.speed = 0
        item.updated = now
        if let position = transfers.firstIndex(where: { $0.id == id }) {
            transfers[position] = item
        } else {
            transfers.insert(item, at: 0)
        }
        if transfers.count > 60 { transfers.removeLast(transfers.count - 60) }

        if incoming, error == nil, let location {
            let from = device(peer)?.name ?? "?"
            Notifier.shared.postFileReceived(name: name, from: from, location: location)
            if ReceivedImages.copy(URL(fileURLWithPath: location)) {
                showToast(ReceivedImages.pasteEnabled ? String(localized: "Picture copied and pasted") : String(localized: "Picture copied to the clipboard"))
            }
        }
    }

    func clearFinishedTransfers() {
        transfers.removeAll { $0.state != .active }
    }

    // MARK: Acting on a transfer

    /// Where the file of a transfer lives: where it was stored, or for a sent file
    /// where it was sent from.
    private func filePath(_ item: TransferItem) -> String? {
        item.location ?? (item.incoming ? nil : outgoingSources[item.name])
    }

    /// Whether the file behind a transfer can be found on disk right now.
    func canOpen(_ item: TransferItem) -> Bool {
        guard item.state == .done, let path = filePath(item) else { return false }
        return FileManager.default.fileExists(atPath: path)
    }

    func open(_ item: TransferItem) {
        guard canOpen(item), let path = filePath(item) else {
            if item.state == .done { showToast(String(localized: "The file is no longer there")) }
            return
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: path))
    }

    func reveal(_ item: TransferItem) {
        guard canOpen(item), let path = filePath(item) else { return }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }

    func copyPath(_ item: TransferItem) {
        guard let path = filePath(item) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(path, forType: .string)
        showToast(String(localized: "Path copied"))
    }

    /// Takes transfers off the list. A transfer that is still running stays.
    func removeFromList(_ ids: Set<String>) {
        transfers.removeAll { ids.contains($0.id) && $0.state != .active }
    }

    /// Asks for a yes before a received file is trashed. Only files that arrived here
    /// qualify: a sent file is the person's own original.
    func requestTrash(_ item: TransferItem) {
        guard item.incoming, canOpen(item) else { return }
        pendingTrash = item
    }

    func confirmTrash(_ item: TransferItem) {
        pendingTrash = nil
        guard item.incoming, let path = item.location else { return }
        do {
            try FileManager.default.trashItem(at: URL(fileURLWithPath: path), resultingItemURL: nil)
            removeFromList([item.id])
            showToast(String(localized: "Moved to the Trash"))
        } catch {
            showToast(String(localized: "Could not move the file to the Trash"))
        }
    }

    // MARK: Sending

    func send(urls: [URL], to ids: [String]) {
        guard let engine, !urls.isEmpty, !ids.isEmpty else { return }
        Task {
            do {
                var files: [TandemOutgoingFile] = []
                for url in urls {
                    let prepared = try FileKind.prepareForSending(url)
                    let size = (try? prepared.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    outgoingSources[prepared.lastPathComponent] = url.path
                    files.append(TandemOutgoingFile(
                        source: prepared.path,
                        name: prepared.lastPathComponent,
                        size: UInt64(size),
                        mime: FileKind.mimeType(for: prepared)
                    ))
                }
                let report = try await engine.sendFiles(targets: ids, files: files, origin: .files)
                if report.sentTo.isEmpty {
                    showToast(String(localized: "That device is not connected right now"))
                } else if !report.offline.isEmpty {
                    showToast(String(localized: "\(report.offline.count) device(s) were offline and did not get it"))
                }
            } catch {
                showToast(error.localizedDescription)
            }
        }
    }

    func sendClipboard(to ids: [String]) {
        guard let engine, let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            showToast(String(localized: "There is no text on the clipboard"))
            return
        }
        Task {
            let reached = (try? await engine.sendClipboard(targets: ids, text: text, isUrl: ClipboardMonitor.looksLikeURL(text))) ?? []
            showToast(reached.isEmpty ? String(localized: "Not connected") : String(localized: "Clipboard sent"))
        }
    }

    private func localClipboardChanged(_ text: String, isURL: Bool) {
        guard let engine else { return }
        Task { await engine.clipboardChanged(text: text, isUrl: isURL) }
    }

    /// Phones that are ringing because of this Mac, so the button can turn into Stop. The
    /// phone does not report back, so it also lets go of the state after a minute.
    var ringing: Set<String> = []

    func ring(_ id: String, on: Bool) {
        if on {
            ringing.insert(id)
            Task {
                try? await Task.sleep(for: .seconds(60))
                ringing.remove(id)
            }
        } else {
            ringing.remove(id)
        }
        Task { try? await engine?.ring(target: id, on: on) }
    }

    // MARK: Devices

    func remove(_ id: String) {
        Task {
            try? await engine?.removeDevice(id: id)
            refreshDevices()
        }
    }

    /// Shows a name in the sidebar while it is still being typed, without telling the
    /// engine or saving anything yet.
    func previewName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { myName = trimmed }
    }

    /// Saves the name and sends it on. Kept in UserDefaults because the engine would
    /// otherwise start again from the Mac's own name on every launch.
    func rename(to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        UserDefaults.standard.set(trimmed, forKey: Self.nameKey)
        myName = trimmed
        Task { try? await engine?.renameSelf(name: trimmed) }
    }

    func setSettings(_ device: TandemDevice, clipboard: Bool? = nil, autoAccept: Bool? = nil, notifications: Bool? = nil) {
        try? engine?.setDeviceSettings(
            id: device.id,
            clipboard: clipboard ?? device.clipboardEnabled,
            autoAccept: autoAccept ?? device.autoAccept,
            notifications: notifications ?? device.notificationsEnabled
        )
        refreshDevices()
    }

    // MARK: Pairing

    func beginPairing() {
        guard let engine else { return }
        pairing = PairingState(busy: false)
        do {
            let offer = try engine.createPairingOffer()
            pairing.uri = offer.uri
            DebugSupport.write(offer.uri, named: "pairing-uri.txt")
            pairing.expiresAt = Date(timeIntervalSince1970: Double(offer.expiresAtMs) / 1000)
        } catch {
            pairing.error = error.localizedDescription
        }
    }

    func endPairing() {
        engine?.cancelPairingOffer()
        pairing = PairingState()
    }

    func pair(uri: String) {
        guard let engine else { return }
        pairing.busy = true
        pairing.error = nil
        Task {
            do {
                let id = try await engine.pairWithUri(uri: uri)
                refreshDevices()
                pairing.busy = false
                pairing.joinedName = device(id)?.name ?? String(localized: "New device")
            } catch {
                pairing.busy = false
                pairing.error = Self.pairingMessage(for: error)
            }
        }
    }

    /// The core reports why a join failed in plain but technical words. The three
    /// that a person can act on get a friendly message, the rest is shown as it is.
    nonisolated static func pairingMessage(for error: Error) -> String {
        let raw: String
        switch error as? TandemError {
        case let .Pairing(reason)?, let .Failed(reason)?: raw = reason
        default: raw = error.localizedDescription
        }
        let text = raw.lowercased()
        if text.contains("the pairing code has expired") {
            return String(localized: "This code has expired. Ask the other device to show a new QR code.")
        }
        if text.contains("the pairing code did not match") {
            return String(localized: "This code does not match. Ask the other device to show a new QR code and scan that one.")
        }
        if text.contains("this device is not showing a pairing code") {
            return String(localized: "The other device is not showing a pairing code. Choose Add device on it and try again.")
        }
        return raw
    }

    func handle(url: URL) {
        guard url.scheme == "tandem" else { return }
        pair(uri: url.absoluteString)
    }

    // MARK: Status

    func pushStatus() {
        guard let engine else { return }
        var status = TandemStatus(battery: nil, network: nil, hotspot: nil, dnd: nil, locked: nil, freeStorage: nil)
        status.battery = PowerReader.battery()
        status.network = TandemNetwork(
            kind: network.isOnline ? .wifi : .none,
            ssid: nil,
            metered: network.usesCellularHotspot,
            roaming: false,
            signal: nil
        )
        if let values = try? URL(fileURLWithPath: "/").resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let free = values.volumeAvailableCapacityForImportantUsage {
            status.freeStorage = UInt64(free)
        }
        Task { await engine.updateStatus(status: status) }
    }

    /// Forgets the identity and every pairing, then starts fresh.
    func resetEverything() {
        Task {
            await stop()
            try? FileManager.default.removeItem(at: Self.supportDirectory())
            relaunch()
        }
    }

    func relaunch() {
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: configuration) { _, _ in
            DispatchQueue.main.async { NSApp.terminate(nil) }
        }
    }

    // MARK: Helpers for other components

    var engineHandle: TandemEngine? { engine }

    /// Keeps a Bluetooth link to the phone while it is out of reach over the network, so
    /// clipboard and notifications still arrive. Two looks in a row must agree first, because a
    /// phone that just dropped off Wi-Fi usually comes straight back.
    private func startBleWatch() {
        bleWatch?.cancel()
        bleWatch = Task { @MainActor [weak self] in
            var lastOffline: String?
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self, let engine = self.engine else { continue }
                let on = UserDefaults.standard.object(forKey: "bleMessages") as? Bool ?? true
                let candidate = on
                    ? self.devices.first(where: { $0.platform == .android && !$0.online && engine.bleReady(id: $0.id) })?.id
                    : nil
                self.bleMessenger.want(phoneId: candidate != nil && candidate == lastOffline ? candidate : nil, engine: engine)
                lastOffline = candidate
            }
        }
    }

    private func remember(_ notification: TandemNotification, from device: String, deviceName: String) {
        // Ongoing ones (music, navigation, downloads) come and go; they do not belong on a list.
        guard !notification.ongoing else { return }
        let item = MirroredNotification(
            device: device, deviceName: deviceName, key: notification.key,
            appName: notification.appName, title: notification.title, text: notification.text,
            date: notification.ts > 0 ? Date(timeIntervalSince1970: TimeInterval(notification.ts) / 1000) : Date()
        )
        mirrored.removeAll { $0.device == device && $0.key == notification.key }
        mirrored.insert(item, at: 0)
        if mirrored.count > 200 { mirrored.removeLast(mirrored.count - 200) }
    }

    func clearMirrored() { mirrored.removeAll() }

    func setIcon(_ symbol: String?, for id: String) {
        if let symbol { deviceIcons[id] = symbol } else { deviceIcons.removeValue(forKey: id) }
        UserDefaults.standard.set(deviceIcons, forKey: "deviceIcons")
    }

    func showToast(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3.2))
            if !Task.isCancelled { toast = nil }
        }
    }
}

/// Bridges the Rust event thread to the main actor in order.
final class EventForwarder: TandemEventSink, @unchecked Sendable {
    private let continuation: AsyncStream<TandemEvent>.Continuation

    init(continuation: AsyncStream<TandemEvent>.Continuation) {
        self.continuation = continuation
    }

    func onEvent(event: TandemEvent) {
        continuation.yield(event)
    }
}

extension Bundle {
    var appVersion: String {
        (infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0.0.0"
    }
}


/// A notification a phone showed, kept for the Notifications page.
struct MirroredNotification: Identifiable, Equatable {
    var id: String { "\(device).\(key)" }
    let device: String
    let deviceName: String
    let key: String
    let appName: String
    let title: String
    let text: String
    let date: Date
}
