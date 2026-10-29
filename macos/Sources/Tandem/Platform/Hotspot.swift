import AppKit
import CoreWLAN
import Foundation
import Network
import TandemCore

enum HotspotStatus: Equatable {
    case idle
    case asking
    case starting
    /// The phone cannot turn the hotspot on alone: someone has to tap a notification.
    case needsTap
    case joining(String)
    case connected(String)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .asking, .starting, .needsTap, .joining: true
        default: false
        }
    }

    var text: String {
        switch self {
        case .idle: ""
        case .asking: String(localized: "Asking your phone")
        case .starting: String(localized: "Your phone is turning on its hotspot")
        case .needsTap: String(localized: "Tap the notification on your phone to turn on the hotspot")
        case let .joining(name): String(localized: "Joining \(name)")
        case let .connected(name): String(localized: "Using the hotspot of \(name)")
        case let .failed(reason): reason
        }
    }
}

/// Turns the phone's hotspot on when the Mac has no connection, and reports it.
///
/// When the Mac is offline it cannot reach the phone over the network, so the
/// request goes over Bluetooth Low Energy. When it can still reach the phone
/// (both on the same network, say) the request goes over the normal connection.
/// Once the phone says the hotspot is on, the Mac joins it, tells the core where the
/// phone is, and leaves again when a better connection appears.
@MainActor
final class HotspotCoordinator {
    private weak var model: EngineModel?
    private var offlineTask: Task<Void, Never>?
    private var runTask: Task<Void, Never>?
    private var pathTask: Task<Void, Never>?
    private var activeDevice: String?
    private var hotspotGateway: String?
    /// The last answer that came over the normal connection, for the QUIC route.
    private var quicAnswer: HotspotBleState?
    /// Set when the person lets the hotspot go, so the Mac still sitting on it for a few
    /// seconds is not taken for a new connection.
    private var ignoreNetworkUntil = Date.distantPast
    private var watchTask: Task<Void, Never>?
    private let ble = BleHotspotClient()
    private let pathMonitor = NWPathMonitor()
    /// When this Mac started or last woke: macOS needs a while then to join a network it knows, and asking the phone in the meantime
    /// turned its hotspot on for nothing (seen 2026-10-29, after a restart at home).
    private var settlingSince = Date()
    private var wakeObserver: NSObjectProtocol?
    /// Looks in a row that found a known network nearby, so a network macOS will not join does not keep the hotspot away for good.
    private var waitedForKnown = 0

    static let enabledKey = "autoHotspot"
    static let delayKey = "hotspotDelay"
    static let ssidKey = "hotspotSSID"

    var autoEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    func attach(model: EngineModel) {
        self.model = model
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor [weak self] in self?.settlingSince = Date() }
        }
        // A wired connection is a reason to let the hotspot go. The model's own network
        // watcher only says online or not, so this looks at the path itself.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let wired = path.status == .satisfied && path.usesInterfaceType(.wiredEthernet)
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.pathChanged(wired: wired, satisfied: satisfied) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "tandem.hotspot.path"))
        watchTask?.cancel()
        watchTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.syncWithCurrentNetwork()
                try? await Task.sleep(for: .seconds(4))
            }
        }
    }

    /// The Mac may already be on the phone's hotspot, joined by hand or before Tandem started.
    /// Without this the card offered to turn it on while it was in use.
    private func syncWithCurrentNetwork() async {
        guard let model, Date() >= ignoreNetworkUntil else { return }
        let saved = UserDefaults.standard.string(forKey: Self.ssidKey) ?? ""
        guard !saved.isEmpty else { return }
        let current = await Task.detached { CurrentWiFi.ssid() }.value
        switch model.hotspotStatus {
        case .idle, .failed:
            guard current == saved, let phone = model.devices.first(where: { $0.platform == .android }) else { return }
            activeDevice = phone.id
            model.hotspotStatus = .connected(phone.name)
        case .connected:
            // An unreadable name is not proof of leaving: only a different network is. Moved on to it (by hand, or macOS did): the
            // phone turns the hotspot off when nobody else is on it.
            if let current, current != saved {
                model.hotspotStatus = .idle
                hotspotGateway = nil
                if let id = activeDevice { leave(id) }
            }
        default:
            break
        }
    }

    /// Called whenever the network changes. Waits a few seconds before acting, because a Mac that lost Wi-Fi often gets it back on
    /// its own: longer right after it started or woke, when macOS is still joining a network it knows. And a network it knows that is
    /// near goes first: it is joined, and the phone is only asked when there is none.
    func networkChanged(online: Bool) {
        offlineTask?.cancel()
        if online { waitedForKnown = 0 }
        guard let model, !online, autoEnabled else { return }
        if model.hotspotStatus.isBusy { return }
        if case .connected = model.hotspotStatus { return }
        let chosen = UserDefaults.standard.object(forKey: Self.delayKey) as? Double ?? 8
        let settling = Self.settleSeconds - Date().timeIntervalSince(settlingSince)
        let delay = max(chosen, settling)
        offlineTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self, let model = self.model, !model.isOnline else { return }
            let hotspot = UserDefaults.standard.string(forKey: Self.ssidKey)
            if self.waitedForKnown < Self.knownTries, await Task.detached(operation: { Self.joinKnownNearby(except: hotspot) }).value {
                self.waitedForKnown += 1
                return self.networkChanged(online: model.isOnline)
            }
            self.waitedForKnown = 0
            if let target = model.devices.first(where: { $0.platform == .android }) {
                self.requestNow(target.id)
            }
        }
    }

    /// The seconds after a start or a wake in which the phone is not asked.
    private static let settleSeconds = 40.0
    /// How many looks a known network nearby may hold the hotspot off: the delay apart, so about half a minute.
    private static let knownTries = 3

    /// Whether a network this Mac has saved (not the phone's own hotspot) is in range; if so, this Mac is asked to join it. macOS
    /// names the networks of a scan only to an app that may see the location, which Tandem may for sharing Wi-Fi; without that it
    /// finds none here and the phone is asked as before.
    nonisolated static func joinKnownNearby(except hotspot: String?) -> Bool {
        // Only a Mac on no Wi-Fi at all: one on a network without internet is not moved to another here.
        guard let interface = CWWiFiClient.shared().interface(), interface.powerOn(), interface.wlanChannel() == nil else { return false }
        let known = Set(WifiShare.known()).subtracting([hotspot].compactMap { $0 })
        guard !known.isEmpty, let found = try? interface.scanForNetworks(withSSID: nil) else { return false }
        let nearby = found.filter { $0.ssid.map(known.contains) ?? false }.sorted { $0.rssiValue > $1.rssiValue }
        guard let best = nearby.first else { return false }
        // The password of a saved network is in the keychain of the system, which CoreWLAN takes by itself; failing, macOS still
        // joins it on its own a moment later.
        try? interface.associate(to: best, password: nil)
        return true
    }

    /// The phone connected to the core: over the hotspot, that is the moment it works.
    func deviceConnected(_ id: String) {}

    // MARK: Asking

    func requestNow(_ id: String) {
        guard let model, let engine = model.engineHandle else { return }
        runTask?.cancel()
        activeDevice = id
        quicAnswer = nil
        model.hotspotStatus = .asking
        runTask = Task { [weak self] in
            await self?.run(id: id, engine: engine)
        }
    }

    private func run(id: String, engine: TandemEngine) async {
        guard let model else { return }
        let name = model.device(id)?.name ?? "Phone"
        do {
            let answer: HotspotBleState
            if model.device(id)?.online == true {
                answer = try await askOverNetwork(id: id, engine: engine)
            } else {
                answer = try await ble.run(action: BleHotspotClient.actionOn, phoneId: id, engine: engine) { [weak self] state in
                    Task { @MainActor in self?.showProgress(state) }
                }
            }
            switch answer {
            case .on:
                await joinAndConnect(id: id, name: name, engine: engine)
            case .manual:
                model.hotspotStatus = .failed(String(localized: "Nobody tapped the notification on your phone in time. Tap it next time, or start Shizuku so the phone can do it by itself."))
            default:
                model.hotspotStatus = .failed(Self.reason(for: answer))
            }
        } catch is CancellationError {
            // A newer request or a stop took over.
        } catch {
            model.hotspotStatus = .failed(error.localizedDescription)
        }
    }

    private func showProgress(_ state: HotspotBleState) {
        guard let model, model.hotspotStatus.isBusy || model.hotspotStatus == .idle else { return }
        switch state {
        case .starting: model.hotspotStatus = .starting
        case .manual: model.hotspotStatus = .needsTap
        default: break
        }
    }

    /// When the phone is reachable over the normal connection, the same request goes
    /// over it, and the answer comes back as a state message.
    private func askOverNetwork(id: String, engine: TandemEngine) async throws -> HotspotBleState {
        try await engine.sendHotspot(target: id, hotspot: .request(reason: "mac-offline"))
        var waited = 0.0
        var limit = 45.0
        while waited < limit {
            try Task.checkCancellation()
            if let answer = quicAnswer {
                if answer == .manual {
                    showProgress(.manual)
                    limit = 200
                    quicAnswer = nil
                } else {
                    return answer
                }
            }
            try await Task.sleep(for: .milliseconds(500))
            waited += 0.5
        }
        return .failed
    }

    private static func reason(for state: HotspotBleState) -> String {
        switch state {
        case .refusedAuth: String(localized: "Your phone did not recognise this Mac")
        case .refusedBattery: String(localized: "Your phone's battery is too low to share its hotspot")
        case .refusedRoaming: String(localized: "Your phone is roaming, so it does not share its hotspot")
        case .refusedLimit: String(localized: "Your phone's daily data limit for the hotspot is reached")
        case .disabled: String(localized: "Hotspot sharing is switched off on your phone")
        default: String(localized: "Your phone could not turn on its hotspot. Tap the notification on your phone, or make sure Shizuku is running so it can start by itself.")
        }
    }

    // MARK: Joining

    private func joinAndConnect(id: String, name: String, engine: TandemEngine) async {
        guard let model else { return }
        let ssid = UserDefaults.standard.string(forKey: Self.ssidKey) ?? ""
        guard !ssid.isEmpty, let password = HotspotCredentials.password(), !password.isEmpty else {
            model.hotspotStatus = .failed(String(localized: "Enter the hotspot name and password in Settings first"))
            return
        }
        model.hotspotStatus = .joining(ssid)
        do {
            let gateway = try await HotspotWiFi.join(ssid: ssid, password: password)
            hotspotGateway = gateway
            // The phone is the gateway. Without this the core would wait for mDNS, which
            // does not cross a hotspot reliably.
            try? engine.addAddress(id: id, addr: "\(gateway):\(HotspotWiFi.port)")
            model.hotspotStatus = .connected(name)
            Notifier.shared.post(
                id: "hotspot.connected",
                title: String(localized: "Connected through the hotspot of \(name)"),
                body: String(localized: "This Mac is online again")
            )
        } catch is CancellationError {
        } catch {
            model.hotspotStatus = .failed(error.localizedDescription)
        }
    }

    // MARK: Letting go

    func stop(_ id: String) {
        guard let model, let engine = model.engineHandle else { return }
        runTask?.cancel()
        offlineTask?.cancel()
        model.hotspotStatus = .idle
        hotspotGateway = nil
        ignoreNetworkUntil = Date().addingTimeInterval(25)
        let online = model.device(id)?.online == true
        runTask = Task { [weak self] in
            guard let self else { return }
            if online {
                try? await engine.sendHotspot(target: id, hotspot: .stop)
            } else {
                _ = try? await self.ble.run(action: BleHotspotClient.actionOff, phoneId: id, engine: engine) { _ in }
            }
        }
    }

    /// This Mac went on to a better network: the phone turns the hotspot off unless other devices are still on it. The button Stop
    /// is `stop`, which turns it off whatever else is on it.
    func leave(_ id: String) {
        guard let model, let engine = model.engineHandle else { return }
        runTask?.cancel()
        offlineTask?.cancel()
        model.hotspotStatus = .idle
        hotspotGateway = nil
        ignoreNetworkUntil = Date().addingTimeInterval(25)
        let online = model.device(id)?.online == true
        runTask = Task { [weak self] in
            guard let self else { return }
            if online {
                try? await engine.sendHotspot(target: id, hotspot: .leave)
            } else {
                _ = try? await self.ble.run(action: BleHotspotClient.actionLeave, phoneId: id, engine: engine) { _ in }
            }
        }
    }

    /// A better connection than the hotspot appeared: a cable, or another network with
    /// internet. The phone's battery is worth more than the hotspot.
    private func pathChanged(wired: Bool, satisfied: Bool) {
        guard let model, case .connected = model.hotspotStatus, let id = activeDevice else { return }
        pathTask?.cancel()
        pathTask = Task { @MainActor [weak self] in
            // Path updates come in bursts while a network settles.
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self, let model = self.model, case .connected = model.hotspotStatus else { return }
            if wired {
                self.leave(id)
                return
            }
            guard satisfied else {
                // The hotspot itself went away: the phone is off or out of reach.
                model.hotspotStatus = .idle
                self.hotspotGateway = nil
                return
            }
            if let route = await HotspotWiFi.defaultRoute(), let known = self.hotspotGateway, route.gateway != known {
                self.leave(id)
            }
        }
    }

    // MARK: Over the normal connection

    func handle(from id: String, message: TandemHotspot) {
        guard model != nil else { return }
        switch message {
        case let .state(on, _, _, _, error):
            if on {
                quicAnswer = .on
            } else {
                switch error {
                case "manual": quicAnswer = .manual
                case "battery": quicAnswer = .refusedBattery
                case "roaming": quicAnswer = .refusedRoaming
                case "limit": quicAnswer = .refusedLimit
                case "disabled": quicAnswer = .disabled
                case .some: quicAnswer = .failed
                case .none:
                    // The phone turned it off: ours to follow, once we were using it.
                    if case .connected = model?.hotspotStatus { model?.hotspotStatus = .idle }
                    quicAnswer = .off
                }
            }
        case .request, .stop, .leave:
            break
        }
    }
}
