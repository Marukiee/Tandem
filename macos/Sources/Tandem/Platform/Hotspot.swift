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
    /// The hotspot went away while this Mac used it: most often the person turned it off on the phone, because a network of its own is
    /// there (school, home). Asking for it again by itself right then turned it back on against them (2026-11-02); it waits until this
    /// time, or until another network is there. The button still asks at once.
    private var heldOffUntil = Date.distantPast
    /// When the networks around were last looked at while on the hotspot.
    private var lastScan = Date.distantPast
    /// The gateways of the network this Mac is on now, without asking a tool for them every few seconds.
    private var gateways: Set<String> = []
    private var networkMovedAt = Date()
    /// The gateway the phone was last tried at, so it is tried once per network.
    private var triedGateway: String?
    /// Asked for with a button: the person wants the hotspot, known network nearby or not.
    private var keepHotspot = false
    /// The networks this Mac moved to from the hotspot, and when. One that did not hold (no internet, a password that is gone) is not
    /// tried again right away, which would keep throwing this Mac off the hotspot.
    private var triedKnown: [String: Date] = [:]
    private static let scanEvery: TimeInterval = 60
    private static let triedKnownFor: TimeInterval = 30 * 60
    private static let holdOff: TimeInterval = 15 * 60

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
            let gateways = Set(path.gateways.compactMap { gateway -> String? in
                guard case let .hostPort(host, _) = gateway, case let .ipv4(address) = host else { return nil }
                return "\(address)"
            })
            Task { @MainActor in
                self?.gateways = gateways
                self?.pathChanged(wired: wired, satisfied: satisfied)
                self?.watch()
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "tandem.hotspot.path"))
        watch()
    }

    /// Looks at once and then keeps looking: quickly while something is going on with the network or the hotspot, with long gaps when not.
    private func watch() {
        networkMovedAt = Date()
        watchTask?.cancel()
        watchTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.syncWithCurrentNetwork()
                let quiet = self?.nothingGoingOn ?? true
                try? await Task.sleep(for: .seconds(quiet ? 20 : 4), tolerance: .seconds(quiet ? 5 : 1))
            }
        }
    }

    private var nothingGoingOn: Bool {
        guard model?.hotspotStatus == .idle else { return false }
        return Date().timeIntervalSince(networkMovedAt) > 60
    }

    /// Whether this Mac is on the phone's hotspot, joined by Tandem, by hand or by macOS. macOS hides the name of the current network from
    /// an app without Location access, also from `networksetup`, while Android lets a phone be on Wi-Fi and share at once. What always
    /// holds: the gateway of a hotspot is the phone, at the address the connection with it runs over.
    private func syncWithCurrentNetwork() async {
        guard let model, Date() >= ignoreNetworkUntil else { return }
        let saved = UserDefaults.standard.string(forKey: Self.ssidKey) ?? ""
        let phones = model.devices.filter { $0.platform == .android }
        let atGateway = phones.first { phone in
            phone.online && (model.engineHandle?.deviceAddress(id: phone.id)).map(gateways.contains) == true
        }
        if let atGateway, let address = model.engineHandle?.deviceAddress(id: atGateway.id) { hotspotGateway = address }
        // A phone on mobile data with its hotspot on, reached over the local network: the only network the two share is that hotspot.
        let sharing = phones.first { $0.online && $0.route == .lan && $0.status.hotspot == true && $0.status.network?.kind == .cellular }
        let current = saved.isEmpty ? nil : await Task.detached { CWWiFiClient.shared().interface()?.ssid() }.value
        let byName = !saved.isEmpty && current == saved ? phones.first : nil
        let joined = hotspotGateway.map(gateways.contains) == true
        switch model.hotspotStatus {
        case .idle, .failed:
            guard let phone = atGateway ?? sharing ?? byName else {
                keepHotspot = false
                tryPhoneAtGateway(phones)
                return
            }
            activeDevice = phone.id
            model.hotspotStatus = .connected(phone.name)
        case .connected:
            if atGateway != nil || sharing != nil || byName != nil || joined {
                await moveToKnownNetwork(hotspot: saved)
                return
            }
            // No gateway says nothing yet: `pathChanged` is there for a network that went. Another one means this Mac moved on (by hand, or
            // macOS did): the phone turns the hotspot off when nobody else is on it.
            guard !gateways.isEmpty else { return }
            model.hotspotStatus = .idle
            hotspotGateway = nil
            if let id = activeDevice { leave(id) }
        default:
            break
        }
    }

    /// Joined by macOS or by hand, the phone is not found by mDNS, which does not cross a hotspot reliably. Dialing its port at the gateway
    /// finds it there; the key of the phone is checked as with any address, so a router that happens to answer is nothing.
    private func tryPhoneAtGateway(_ phones: [TandemDevice]) {
        guard let gateway = gateways.sorted().first, gateway != triedGateway, let engine = model?.engineHandle else { return }
        triedGateway = gateway
        for phone in phones where !phone.online && (phone.status.hotspot == true || phone.ble) {
            try? engine.addAddress(id: phone.id, addr: "\(gateway):\(HotspotWiFi.port)")
        }
    }

    /// A network of its own is near (school, home): that one goes first, and the phone keeps its battery. Not when the person asked for the
    /// hotspot with a button: they may well know why.
    private func moveToKnownNetwork(hotspot: String) async {
        guard !keepHotspot, Date().timeIntervalSince(lastScan) >= Self.scanEvery, let id = activeDevice else { return }
        lastScan = Date()
        let skip = Set(triedKnown.filter { Date().timeIntervalSince($0.value) < Self.triedKnownFor }.keys).union([hotspot])
        guard let name = await Task.detached(operation: { Self.joinKnown(except: skip) }).value else { return }
        NSLog("Tandem: this Mac moves from the hotspot to %@", name)
        triedKnown[name] = Date()
        heldOffUntil = Date().addingTimeInterval(Self.holdOff)
        leave(id)
    }

    /// Called whenever the network changes. Waits a few seconds before acting, because a Mac that lost Wi-Fi often gets it back on
    /// its own: longer right after it started or woke, when macOS is still joining a network it knows. And a network it knows that is
    /// near goes first: it is joined, and the phone is only asked when there is none.
    func networkChanged(online: Bool) {
        offlineTask?.cancel()
        if online {
            waitedForKnown = 0
            // Online through something else than the hotspot: the next time it is missed, it may be asked for again.
            if case .connected = model?.hotspotStatus {} else { heldOffUntil = .distantPast }
        }
        guard let model, !online, autoEnabled, Date() >= heldOffUntil else { return }
        if model.hotspotStatus.isBusy { return }
        if case .connected = model.hotspotStatus { return }
        let chosen = UserDefaults.standard.object(forKey: Self.delayKey) as? Double ?? 8
        let settling = Self.settleSeconds - Date().timeIntervalSince(settlingSince)
        let delay = max(chosen, settling)
        offlineTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self, let model = self.model, !model.isOnline, Date() >= self.heldOffUntil else { return }
            let hotspot = UserDefaults.standard.string(forKey: Self.ssidKey)
            // Nobody at this Mac: a closed lid that wakes in the dark for a moment (Power Nap) has no network and needs none. Asking then
            // turned the hotspot on at home, in a bag (2026-11-02).
            guard Self.someoneUsedThisMac() else { return }
            // Still on a Wi-Fi of its own (home, school): that network is out for a moment, or asks for a login. The hotspot is no help.
            if await Task.detached(operation: { Self.onOtherWiFi(than: hotspot) }).value { return }
            if self.waitedForKnown < Self.knownTries, await Task.detached(operation: { Self.joinKnownNearby(except: hotspot) }).value {
                self.waitedForKnown += 1
                return self.networkChanged(online: model.isOnline)
            }
            self.waitedForKnown = 0
            if let target = model.devices.first(where: { $0.platform == .android }) {
                self.requestNow(target.id, byPerson: false)
            }
        }
    }

    /// A key or the pointer was used in the last few minutes.
    nonisolated static func someoneUsedThisMac() -> Bool {
        guard let any = CGEventType(rawValue: ~0) else { return true }
        return CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: any) < 5 * 60
    }

    /// Joined to a Wi-Fi network that is not the hotspot.
    nonisolated static func onOtherWiFi(than hotspot: String?) -> Bool {
        guard let interface = CWWiFiClient.shared().interface(), interface.wlanChannel() != nil else { return false }
        // Without the right to see names, a joined network still has a channel; the hotspot is only told apart by its name.
        guard let ssid = interface.ssid() else { return hotspot == nil }
        return ssid != hotspot
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

    /// On the hotspot, with a saved network of this Mac's own in good reach: this Mac goes there, the first of them in the order of macOS.
    /// The names of a scan and of the saved networks come without Location access, unlike the name of the current network. Should joining
    /// not work (a login macOS keeps to itself), leaving the hotspot makes macOS join one by itself. The name, or nil when none is near.
    nonisolated static func joinKnown(except skip: Set<String>) -> String? {
        guard let interface = CWWiFiClient.shared().interface(), interface.powerOn() else { return nil }
        let known = WifiShare.known().filter { !skip.contains($0) }
        guard !known.isEmpty, let found = try? interface.scanForNetworks(withSSID: nil) else { return nil }
        let inReach = found.filter { $0.rssiValue > -75 }
        guard let name = known.first(where: { name in inReach.contains { $0.ssid == name } }),
              let best = inReach.filter({ $0.ssid == name }).max(by: { $0.rssiValue < $1.rssiValue })
        else { return nil }
        let enterprise = [CWSecurity.wpaEnterprise, .wpa2Enterprise, .wpa3Enterprise, .enterprise].contains { best.supportsSecurity($0) }
        do {
            if enterprise {
                try interface.associate(toEnterpriseNetwork: best, identity: nil, username: nil, password: nil)
            } else {
                try interface.associate(to: best, password: nil)
            }
        } catch {
            interface.disassociate()
        }
        return name
    }

    /// The phone connected to the core: over the hotspot, that is the moment it works.
    func deviceConnected(_ id: String) {}

    // MARK: Asking

    /// `byPerson`: a button was pressed here. Only then does the phone turn it on again right after it was turned off there by hand.
    func requestNow(_ id: String, byPerson: Bool = true) {
        guard let model, let engine = model.engineHandle else { return }
        heldOffUntil = .distantPast
        keepHotspot = byPerson
        runTask?.cancel()
        activeDevice = id
        quicAnswer = nil
        model.hotspotStatus = .asking
        runTask = Task { [weak self] in
            await self?.run(id: id, engine: engine, byPerson: byPerson)
        }
    }

    private func run(id: String, engine: TandemEngine, byPerson: Bool) async {
        guard let model else { return }
        let name = model.device(id)?.name ?? "Phone"
        do {
            let answer: HotspotBleState
            if model.device(id)?.online == true {
                answer = try await askOverNetwork(id: id, engine: engine, byPerson: byPerson)
            } else {
                answer = try await ble.run(action: byPerson ? BleHotspotClient.actionOnAsked : BleHotspotClient.actionOn, phoneId: id, engine: engine) { [weak self] state in
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
                // The phone said no (turned off by hand a moment ago, battery, data limit): asking again in a few seconds is the same no.
                heldOffUntil = Date().addingTimeInterval(Self.holdOff)
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
    private func askOverNetwork(id: String, engine: TandemEngine, byPerson: Bool) async throws -> HotspotBleState {
        try await engine.sendHotspot(target: id, hotspot: .request(reason: byPerson ? "asked" : "mac-offline"))
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
        case .disabled: String(localized: "Your phone does not share its hotspot now: sharing is off there, or you turned the hotspot off a moment ago")
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
        // Stopped or cancelled by the person: not asked for again by itself right after.
        heldOffUntil = Date().addingTimeInterval(Self.holdOff)
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
                self.heldOffUntil = Date().addingTimeInterval(Self.holdOff)
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
                    // The phone turned it off: ours to follow, once we were using it, and not to ask for again right away.
                    if case .connected = model?.hotspotStatus {
                        model?.hotspotStatus = .idle
                        heldOffUntil = Date().addingTimeInterval(Self.holdOff)
                    } else if case .joining = model?.hotspotStatus {
                        // Turned off while this Mac was still joining it: just as much the person's no.
                        runTask?.cancel()
                        model?.hotspotStatus = .idle
                        heldOffUntil = Date().addingTimeInterval(Self.holdOff)
                    }
                    quicAnswer = .off
                }
            }
        case .request, .stop, .leave:
            break
        }
    }
}
