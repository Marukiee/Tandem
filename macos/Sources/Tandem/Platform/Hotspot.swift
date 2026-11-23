import AppKit
import CoreWLAN
import Foundation
import Network
import TandemCore
import TandemLogic

enum HotspotStatus: Equatable {
    case idle
    case asking
    case starting
    /// The phone cannot turn the hotspot on alone: someone has to tap a notification.
    case needsTap
    case joining(String)
    case connected(String)
    case failed(String)
    /// The phone's daily limit is used up. Not a plain failure: there is something to do about it from here.
    case limitReached

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
        case .limitReached: String(localized: "Daily hotspot limit reached")
        }
    }
}

/// Why the phone could not turn its hotspot on, as it says it: the third byte of its state over Bluetooth, or the key after
/// "failed:" over the normal connection. Keep in step with docs/HOTSPOT.md and FailReason in HotspotProtocol.kt.
enum HotspotFailReason: UInt8 {
    case shizukuMissing = 1
    case shizukuStopped = 2
    case shizukuPermission = 3
    case shizukuError = 4
    case notUp = 5
    case noTap = 6
    /// Not from the phone: it did not answer at all.
    case noAnswer = 200
    /// Not from the phone: it took the request over Bluetooth and did nothing with it.
    case noStart = 201

    init?(key: String) {
        switch key {
        case "shizuku-missing": self = .shizukuMissing
        case "shizuku-stopped": self = .shizukuStopped
        case "shizuku-permission": self = .shizukuPermission
        case "shizuku-error": self = .shizukuError
        case "not-up": self = .notUp
        case "no-tap": self = .noTap
        default: return nil
        }
    }

    /// What went wrong and what to do about it, in a sentence.
    func text(detail: String? = nil) -> String {
        switch self {
        case .shizukuMissing:
            String(localized: "Your phone could not turn on its hotspot by itself: Shizuku is not installed. Tap the notification on your phone next time, or install Shizuku.")
        case .shizukuStopped:
            String(localized: "Your phone could not turn on its hotspot by itself: Shizuku is not running. It stops when the phone restarts. Start it again in the Shizuku app.")
        case .shizukuPermission:
            String(localized: "Your phone could not turn on its hotspot by itself: Tandem is not allowed in Shizuku. Allow it in the Shizuku app.")
        case .shizukuError:
            if let detail, !detail.isEmpty {
                String(localized: "Your phone could not turn on its hotspot by itself. Shizuku said: \(detail)")
            } else {
                String(localized: "Your phone could not turn on its hotspot by itself: Shizuku gave an error. Tandem on your phone shows it under Hotspot.")
            }
        case .notUp:
            String(localized: "Your phone tried to turn on its hotspot, but it did not come on within 20 seconds. Turn it on once by hand on your phone and try again.")
        case .noTap:
            String(localized: "Nobody tapped the notification on your phone in time. Tap it next time, or start Shizuku so the phone can do it by itself.")
        case .noAnswer:
            String(localized: "Your phone did not answer. Open Tandem on your phone and try again.")
        case .noStart:
            String(localized: "Your phone got the request but did not start its hotspot within 20 seconds. Open Tandem on your phone and try again.")
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
    /// Why it failed, with that answer, and what Shizuku said for an error of its own.
    private var quicReason: HotspotFailReason?
    private var quicDetail: String?
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
    private var sleepObservers: [NSObjectProtocol] = []
    /// Set from the moment the screens go dark until they light up again. A Mac with its lid closed wakes for a few seconds now and then
    /// (Power Nap) without lighting them, and without a wake notice for apps.
    private var screensAsleep = false
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

    var autoEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    func attach(model: EngineModel) {
        self.model = model
        let center = NSWorkspace.shared.notificationCenter
        wakeObserver = center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.settlingSince = Date()
                self?.screensAsleep = false
                self?.lookAgainAfterWake()
            }
        }
        // Going to sleep: what was being asked cannot finish (Bluetooth goes off with the Mac), and waking up to its failure, or to
        // "Asking your phone" from before, said something that was not so (2026-11-23). It is asked again after the wake, if still needed.
        sleepObservers.append(center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.screensAsleep = true
                self.offlineTask?.cancel()
                // Only a request to turn it on: a stop or a leave on its way to the phone may still get there.
                guard let model = self.model, model.hotspotStatus.isBusy else { return }
                self.runTask?.cancel()
                self.runTask = nil
                model.hotspotStatus = .idle
            }
        })
        sleepObservers.append(center.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.screensAsleep = true
                self?.offlineTask?.cancel()
            }
        })
        sleepObservers.append(center.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.screensAsleep = false
                self?.lookAgainAfterWake()
            }
        })
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
        let profiles = HotspotProfiles.shared
        let names = profiles.allNames
        let phones = model.devices.filter { $0.platform == .android }
        let atGateway = phones.first { phone in
            phone.online && (model.engineHandle?.deviceAddress(id: phone.id)).map(gateways.contains) == true
        }
        if let atGateway, let address = model.engineHandle?.deviceAddress(id: atGateway.id) { hotspotGateway = address }
        // A phone on mobile data with its hotspot on, reached over the local network: the only network the two share is that hotspot.
        let sharing = phones.first { $0.online && $0.route == .lan && $0.status.hotspot == true && $0.status.network?.kind == .cellular }
        let current = names.isEmpty ? nil : await Task.detached { CWWiFiClient.shared().interface()?.ssid() }.value
        let byName = current.flatMap { name in phones.first { profiles.ssid(for: $0.id) == name } }
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
                await moveToKnownNetwork(hotspots: names)
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
    private func moveToKnownNetwork(hotspots: Set<String>) async {
        guard !keepHotspot, Date().timeIntervalSince(lastScan) >= Self.scanEvery, let id = activeDevice else { return }
        lastScan = Date()
        let skip = Set(triedKnown.filter { Date().timeIntervalSince($0.value) < Self.triedKnownFor }.keys).union(hotspots)
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
            let hotspots = HotspotProfiles.shared.allNames
            // Nobody at this Mac: a closed lid that wakes in the dark for a moment (Power Nap) has no network and needs none. Asking then
            // turned the hotspot on at home, in a bag (2026-11-02). The time since the last key alone does not tell: it stands still
            // while the Mac sleeps, so 12 and 16 minutes after the lid closed it still read as just now, and the phone was asked in the
            // dark, cut off a few seconds later by the Mac falling asleep again (2026-11-23). The screen is what does tell.
            guard self.someoneAtThisMac else { return }
            // Still on a Wi-Fi of its own (home, school): that network is out for a moment, or asks for a login. The hotspot is no help.
            if await Task.detached(operation: { Self.onOtherWiFi(than: hotspots) }).value { return }
            if self.waitedForKnown < Self.knownTries, await Task.detached(operation: { Self.joinKnownNearby(except: hotspots) }).value {
                self.waitedForKnown += 1
                return self.networkChanged(online: model.isOnline)
            }
            self.waitedForKnown = 0
            // The phone that has its hotspot filled in and is in reach, not just the first one: a new phone may sit beside the old one.
            if let target = model.hotspotPhone {
                self.requestNow(target.id, byPerson: false)
            }
        }
    }

    /// The screen is lit and a key or the pointer was used in the last few minutes: only then is the phone asked by itself.
    private var someoneAtThisMac: Bool {
        !screensAsleep && CGDisplayIsAsleep(CGMainDisplayID()) == 0 && Self.someoneUsedThisMac()
    }

    /// The screen lit up again (the lid opened, a key woke it): a Mac that is still without a network is looked at once more, since
    /// nothing changed on the network side to call for it. Right after a wake it first waits for macOS to join a network it knows.
    private func lookAgainAfterWake() {
        guard let model, !model.isOnline else { return }
        networkChanged(online: false)
    }

    /// A key or the pointer was used in the last few minutes of the time this Mac was awake.
    nonisolated static func someoneUsedThisMac() -> Bool {
        guard let any = CGEventType(rawValue: ~0) else { return true }
        return CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: any) < 5 * 60
    }

    /// Joined to a Wi-Fi network that is not a hotspot of one of the phones.
    nonisolated static func onOtherWiFi(than hotspots: Set<String>) -> Bool {
        guard let interface = CWWiFiClient.shared().interface(), interface.wlanChannel() != nil else { return false }
        // Without the right to see names, a joined network still has a channel; the hotspot is only told apart by its name.
        guard let ssid = interface.ssid() else { return hotspots.isEmpty }
        return !hotspots.contains(ssid)
    }

    /// The seconds after a start or a wake in which the phone is not asked.
    private static let settleSeconds = 40.0
    /// How many looks a known network nearby may hold the hotspot off: the delay apart, so about half a minute.
    private static let knownTries = 3

    /// Whether a network this Mac has saved (not the phone's own hotspot) is in range; if so, this Mac is asked to join it. macOS
    /// names the networks of a scan only to an app that may see the location, which Tandem may for sharing Wi-Fi; without that it
    /// finds none here and the phone is asked as before.
    nonisolated static func joinKnownNearby(except hotspots: Set<String>) -> Bool {
        // Only a Mac on no Wi-Fi at all: one on a network without internet is not moved to another here.
        guard let interface = CWWiFiClient.shared().interface(), interface.powerOn(), interface.wlanChannel() == nil else { return false }
        let known = Set(WifiShare.known()).subtracting(hotspots)
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
    /// Once, for today: the phone adds to its daily limit and turns the hotspot on.
    func requestExtra(_ id: String) { requestNow(id, extra: true) }

    func requestNow(_ id: String, byPerson: Bool = true, extra: Bool = false) {
        guard let model, let engine = model.engineHandle else { return }
        heldOffUntil = .distantPast
        keepHotspot = byPerson
        runTask?.cancel()
        activeDevice = id
        quicAnswer = nil
        quicReason = nil
        quicDetail = nil
        model.hotspotStatus = .asking
        runTask = Task { [weak self] in
            await self?.run(id: id, engine: engine, byPerson: byPerson, extra: extra)
        }
    }

    private func run(id: String, engine: TandemEngine, byPerson: Bool, extra: Bool) async {
        guard let model else { return }
        let name = model.device(id)?.name ?? "Phone"
        do {
            let answer: HotspotBleState
            let why: HotspotFailReason?
            var detail: String?
            if model.device(id)?.online == true {
                answer = try await askOverNetwork(id: id, engine: engine, reason: extra ? "extra" : byPerson ? "asked" : "mac-offline")
                why = quicReason
                detail = quicDetail
            } else {
                answer = try await ble.run(action: extra ? BleHotspotClient.actionOnExtra : byPerson ? BleHotspotClient.actionOnAsked : BleHotspotClient.actionOn, phoneId: id, engine: engine) { [weak self] state in
                    Task { @MainActor in self?.showProgress(state) }
                }
                // Still starting when the time was up: it did not come on, whatever the phone thinks.
                why = answer == .starting ? .notUp : ble.reason
            }
            switch answer {
            case .on:
                await joinAndConnect(id: id, name: name, engine: engine)
            case .manual:
                model.hotspotStatus = .failed(HotspotFailReason.noTap.text())
            case .failed, .starting, .off:
                // What the phone said went wrong, where it said it; an older phone says nothing more than that it failed.
                model.hotspotStatus = .failed(why?.text(detail: detail) ?? Self.reason(for: answer))
                heldOffUntil = Date().addingTimeInterval(Self.holdOff)
            default:
                model.hotspotStatus = answer == .refusedLimit ? .limitReached : .failed(Self.reason(for: answer))
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
    private func askOverNetwork(id: String, engine: TandemEngine, reason: String) async throws -> HotspotBleState {
        try await engine.sendHotspot(target: id, hotspot: .request(reason: reason))
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
        // Not a word back in all that time: that is what to say, not that the phone could not do it.
        if quicReason == nil { quicReason = .noAnswer }
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
        let profiles = HotspotProfiles.shared
        guard let profile = profiles.profile(for: id), profile.complete else {
            // A phone this Mac has no hotspot of: said once, with the way to fill it in. Said before, it is what the status says.
            model.hotspotStatus = noticeForFailure(id) ? .idle : .failed(String(localized: "Enter the hotspot name and password in Settings first"))
            return
        }
        model.hotspotStatus = .joining(profile.ssid)
        do {
            let gateway = try await HotspotWiFi.join(ssid: profile.ssid, password: profile.password)
            hotspotGateway = gateway
            // It works with what is saved: a later failure is news again.
            profiles.clearTold(id)
            if model.hotspotNotice?.phone == id { dismissNotice() }
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
            // Not found, or refused: the name or the password is not what the phone has now (a new phone, a renamed hotspot). That is for
            // the person to fix, and said once. A timeout or no route is not, and stays a plain failure with its retry.
            if let failure = error as? HotspotWiFiError, failure.pointsAtCredentials, noticeForFailure(id) {
                model.hotspotStatus = .idle
            } else {
                model.hotspotStatus = .failed(error.localizedDescription)
            }
        }
    }

    // MARK: A hotspot this Mac does not know

    static let noticeCategory = "tandem.hotspot.notice"

    /// A phone said what its hotspot is called (unasked, or with its answer). A name this Mac has no password for says so, once for each
    /// name: a new phone, or a hotspot that was renamed. Only for a Mac that uses the hotspot: one that never set it up is not told about
    /// every phone it meets.
    private func learned(_ raw: String, from id: String) {
        guard let model, let ssid = HotspotRules.usableName(raw), let phone = model.device(id), phone.platform == .android else { return }
        let profiles = HotspotProfiles.shared
        profiles.hear(ssid, from: id)
        guard autoEnabled || profiles.anySaved else { return }
        switch HotspotRules.judge(reported: ssid, stored: profiles.profile(for: id), told: profiles.told(id)) {
        case .fine:
            if profiles.profile(for: id)?.complete == true, profiles.ssid(for: id) == ssid, model.hotspotNotice?.phone == id { dismissNotice() }
        case let .tell(reason):
            raise(reason, phone: phone, ssid: ssid, marker: ssid)
        }
    }

    /// Joining did not work in a way that points at the name or the password. True when that was said (it had not been, for this name).
    private func noticeForFailure(_ id: String) -> Bool {
        guard let model, let phone = model.device(id) else { return false }
        let profiles = HotspotProfiles.shared
        let stored = profiles.profile(for: id)
        let reported = profiles.reported[id]
        guard case let .tell(reason) = HotspotRules.judgeFailure(stored: stored, reported: reported, told: profiles.told(id)) else { return false }
        raise(reason, phone: phone, ssid: reported, marker: HotspotRules.failureMarker(stored: stored, reported: reported))
        return true
    }

    private func raise(_ reason: HotspotRules.Reason, phone: TandemDevice, ssid: String?, marker: String) {
        guard let model else { return }
        HotspotProfiles.shared.markTold(marker, for: phone.id)
        let notice = HotspotNotice(phone: phone.id, phoneName: phone.name, reason: reason, ssid: ssid, canFetch: HotspotProfiles.shared.reported[phone.id] != nil)
        model.hotspotNotice = notice
        Notifier.shared.post(
            id: "hotspot.notice.\(phone.id)",
            title: notice.title,
            body: notice.body,
            category: Self.noticeCategory,
            userInfo: ["phone": phone.id],
            thread: "hotspot"
        )
    }

    /// "Later", or it is done: the row goes, and a notification still in the list with it.
    func dismissNotice() {
        guard let notice = model?.hotspotNotice else { return }
        model?.hotspotNotice = nil
        Notifier.shared.remove(id: "hotspot.notice.\(notice.phone)")
    }

    /// The button of the row and the click on the notification: the page of that phone, with the setup open and the name filled in.
    func openSetup(phone: String? = nil) {
        guard let model, let id = phone ?? model.hotspotNotice?.phone, model.device(id) != nil else { return }
        model.hotspotSetupFor = id
        AppScenes.showMain(.device(id))
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Asks the phone for the password of its hotspot, after a yes here. It can only answer when it reads its own hotspot (Shizuku), which
    /// is also what lets it say the name; the answer is claimed in `handleWifi` and saved, not joined.
    func askPassword(phone id: String) {
        guard let engine = model?.engineHandle, let ssid = HotspotProfiles.shared.reported[id] else { return }
        let profiles = HotspotProfiles.shared
        profiles.startAsking(id, for: ssid)
        Task { @MainActor in
            let sent = (try? await engine.sendWifi(target: id, wifi: .ask(ssid: ssid))) != nil
            guard sent else {
                profiles.stopAsking(id, refusal: String(localized: "Your phone cannot be reached right now"))
                return
            }
            try? await Task.sleep(for: .seconds(20))
            if profiles.asking[id] == ssid {
                profiles.stopAsking(id, refusal: String(localized: "Your phone did not send the password. Type it instead."))
            }
        }
    }

    /// What comes back from a phone that was asked for its hotspot (see `askPassword`). True when it was that, so the Wi-Fi sharing
    /// does not take it for a network to join.
    func handleWifi(from id: String, message: TandemWifi) -> Bool {
        let profiles = HotspotProfiles.shared
        guard let asked = profiles.asking[id] else { return false }
        switch message {
        case let .share(network, password) where network.ssid == asked:
            profiles.stopAsking(id)
            profiles.save(ssid: network.ssid, password: password, for: id)
            if model?.hotspotNotice?.phone == id { dismissNotice() }
            let name = model?.device(id)?.name ?? String(localized: "your phone")
            FloatingToast.show(String(localized: "Hotspot of \(name) saved"), symbol: "personalhotspot")
            // This Mac has no connection and the hotspot was just made joinable: do what it was waiting for.
            if let model, autoEnabled, !model.isOnline, !model.hotspotStatus.isBusy, someoneAtThisMac { requestNow(id, byPerson: false) }
            return true
        case let .refuse(ssid, why) where ssid == asked:
            profiles.stopAsking(id, refusal: Self.refusalText(why))
            return true
        default:
            return false
        }
    }

    private static func refusalText(_ why: TandemWifiRefusal) -> String {
        switch why {
        case .cannotShare: String(localized: "Your phone cannot hand over its hotspot password. Sharing with this Mac may be off, or Shizuku is not running.")
        case .notOnIt: String(localized: "Your phone says its hotspot is not called that anymore. Type the name and password.")
        case .unreadable: String(localized: "Your phone could not read the password of its hotspot. Type it instead.")
        default: String(localized: "Your phone did not send the password. Type it instead.")
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
        case let .name(ssid):
            learned(ssid, from: id)
        case let .state(on, ssid, _, _, error):
            if let ssid { learned(ssid, from: id) }
            if on {
                quicAnswer = .on
            } else {
                switch error {
                case "manual": quicAnswer = .manual
                case "battery": quicAnswer = .refusedBattery
                case "roaming": quicAnswer = .refusedRoaming
                case "limit": quicAnswer = .refusedLimit
                case "disabled": quicAnswer = .disabled
                case let .some(code):
                    // "failed:<reason>", and for an error of Shizuku's own what it said after one more colon.
                    let parts = code.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
                    quicReason = parts.count > 1 ? HotspotFailReason(key: parts[1]) : nil
                    quicDetail = parts.count > 2 ? parts[2] : nil
                    quicAnswer = .failed
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
