import CoreLocation
import CoreWLAN
import Foundation
import Network
import TandemCore

/// Hands the Wi-Fi this Mac is on to another device, the way an iPhone offers a friend its password, and takes over the Wi-Fi of
/// another one. Every device says which network it is on. One without any network offers its person to take over a network of
/// another device that it does not know yet; that yes is the only question, the device that is asked hands the password over.
@MainActor
@Observable
final class WifiShare: NSObject {
    static let shared = WifiShare()

    @ObservationIgnored private var engine: TandemEngine?
    /// What was last read, so a question about another network is answered without reading again, and the button shows only where it helps.
    private(set) var current: TandemWifiNetwork?
    /// Its own client: the shared one has a single delegate, and other parts of the app may want it.
    @ObservationIgnored private let client = CWWiFiClient()
    @ObservationIgnored private var location: CLLocationManager?
    /// The network each other device said it is on.
    @ObservationIgnored private var theirs: [String: TandemWifiNetwork] = [:]
    /// Networks offered since this Mac last had a network, and ones the person said no to (until that device moves to another).
    @ObservationIgnored private var offered: Set<String> = []
    @ObservationIgnored private var declined: Set<String> = []
    /// The network asked for from each device, after a yes here: what comes back is joined without asking again.
    @ObservationIgnored private var pending: [String: String] = [:]
    @ObservationIgnored private var looking: Task<Void, Never>?
    /// A cable counts as a network: a Mac on Ethernet is not offered Wi-Fi.
    @ObservationIgnored private let wired = NWPathMonitor(requiredInterfaceType: .wiredEthernet)
    /// Whether macOS lets Tandem see the name of the network, kept from the callback so nobody has to ask the location service again.
    private(set) var locationAllowed = false

    func start(engine: TandemEngine) {
        guard self.engine == nil else { return }
        self.engine = engine
        client.delegate = self
        try? client.startMonitoringEvent(with: .ssidDidChange)
        try? client.startMonitoringEvent(with: .linkDidChange)
        // Allowing Location in System Settings makes the name readable: it is said at once, not at the next change.
        let manager = CLLocationManager()
        manager.delegate = self
        location = manager
        wired.pathUpdateHandler = { _ in Task { @MainActor in WifiShare.shared.evaluate() } }
        wired.start(queue: .global(qos: .utility))
        refresh()
    }

    /// Whether handing this Mac's Wi-Fi to `device` would give it something: when it has no Wi-Fi of its own, or is on another network
    /// whose name it says. A phone does not say the name of its network, and one that is on Wi-Fi is nearly always on this one (or far
    /// away, over Tailscale, where this network would be of no use). Without Location the name is hidden here too, and then the button
    /// stays, to say so when it is pressed.
    func worthSharing(with device: TandemDevice) -> Bool {
        guard let mine = current else { return !locationAllowed }
        guard let theirs = device.status.network else { return true }
        if let ssid = theirs.ssid { return ssid != mine.ssid }
        return theirs.kind != .wifi
    }

    /// Reads the network again and tells the core, which passes on only a change.
    func refresh() {
        Task { @MainActor in
            let network = await Task.detached { Self.read() }.value
            current = network
            await engine?.setWifiNetwork(network: network)
            evaluate()
        }
    }

    /// Whether this Mac has any network: Wi-Fi it is joined to (also when Location hides its name), or a cable.
    nonisolated static func connected(wired: Bool) -> Bool {
        wired || CWWiFiClient.shared().interface()?.wlanChannel() != nil
    }

    /// The networks this Mac has saved. macOS gives their names without Location.
    nonisolated static func known() -> [String] {
        let profiles = CWWiFiClient.shared().interface()?.configuration()?.networkProfiles.array as? [CWNetworkProfile] ?? []
        return profiles.compactMap(\.ssid)
    }

    /// Offers to take over the network of another device when this Mac has none. Losing the Wi-Fi for a moment, as when it moves to
    /// another access point, says nothing: this looks again a few seconds later, and only then asks.
    func evaluate() {
        looking?.cancel()
        looking = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(Self.graceSeconds))
            guard !Task.isCancelled, let self else { return }
            let wired = self.wired.currentPath.status == .satisfied
            let (connected, known) = await Task.detached { (Self.connected(wired: wired), Self.known()) }.value
            if connected {
                self.offered = []
                return
            }
            // Busy with an answer, or another question on screen: look again, as no event may come while this Mac has no network.
            guard pending.isEmpty, !GlassQuestion.shared.isShowing else {
                evaluate()
                return
            }
            for (id, network) in theirs where !offered.contains(network.ssid) && !declined.contains(network.ssid) {
                guard let device = EngineModel.shared.device(id), device.online || device.ble else { continue }
                guard wifiWorthOffering(network: network, connected: false, known: known) else { continue }
                offered.insert(network.ssid)
                offer(network, of: id)
                return
            }
        }
    }

    private static let graceSeconds = 10.0

    /// The network of the Wi-Fi interface. Without Location, macOS hides the name, and then there is nothing to share.
    nonisolated static func read() -> TandemWifiNetwork? {
        guard let interface = CWWiFiClient.shared().interface(), let ssid = CurrentWiFi.ssid() else { return nil }
        return TandemWifiNetwork(ssid: ssid, security: security(of: interface.security()), hidden: false)
    }

    nonisolated static func security(of security: CWSecurity) -> TandemWifiSecurity {
        switch security {
        case .none, .oweTransition: .open
        case .wpaPersonal, .wpaPersonalMixed, .wpa2Personal, .personal, .wpa3Transition: .wpa2
        case .wpa3Personal: .wpa3
        case .dynamicWEP, .wpaEnterprise, .wpaEnterpriseMixed, .wpa2Enterprise, .enterprise, .wpa3Enterprise: .enterprise
        default: .other
        }
    }

    /// The password macOS saved for a network. macOS asks the person first: CoreWLAN's own call names Tandem in that question and
    /// takes Touch ID, where the command line tool would show up as "security".
    nonisolated static func password(for ssid: String) -> String? {
        let name = Data(ssid.utf8)
        for domain in [CWKeychainDomain.system, .user] {
            var found: NSString?
            let status = CWKeychainFindWiFiPassword(domain, name, &found)
            if status == errSecSuccess, let found { return found as String }
            // A no from the person is the answer: the other keychain would only ask again.
            if status == errSecUserCanceled || status == errSecAuthFailed { return nil }
        }
        return nil
    }

    // MARK: Messages

    func handle(from id: String, message: TandemWifi) {
        switch message {
        case let .ask(ssid):
            asked(by: id, for: ssid)
        case let .share(network, password):
            if pending[id] == network.ssid {
                pending[id] = nil
                join(network, password: password)
            } else {
                handed(network, password: password, by: id)
            }
        case let .network(network):
            // A no holds until that device is on another network.
            if let before = theirs[id], before.ssid != network?.ssid { declined.remove(before.ssid) }
            theirs[id] = network
            evaluate()
        case let .refuse(ssid, why):
            guard pending[id] == ssid else { return }
            pending[id] = nil
            refused(ssid, why: why, by: id)
        }
    }

    /// Asks the person here whether this Mac, which has no network, takes over the one of another device.
    private func offer(_ network: TandemWifiNetwork, of id: String) {
        let who = name(of: id)
        GlassQuestion.shared.ask(
            symbol: "wifi",
            title: String(localized: "Use the Wi-Fi of \(who)?"),
            body: String(localized: "This Mac has no network. \(who) is on \(network.ssid)."),
            answers: [
                GlassQuestion.Answer(title: "Not Now", action: { [weak self] in self?.declined.insert(network.ssid) }),
                GlassQuestion.Answer(title: "Join Network", prominent: true, action: { [weak self] in self?.ask(id, for: network.ssid) }),
            ],
            closeAfter: .seconds(120)
        )
    }

    private func ask(_ id: String, for ssid: String) {
        let who = name(of: id)
        pending[id] = ssid
        Task { @MainActor in
            guard await reply(id, .ask(ssid: ssid)) else {
                pending[id] = nil
                FloatingToast.show(String(localized: "\(who) cannot be reached right now"), symbol: "wifi.exclamationmark")
                return
            }
            try? await Task.sleep(for: .seconds(100))
            guard pending[id] == ssid else { return }
            pending[id] = nil
            FloatingToast.show(String(localized: "\(who) did not send the Wi-Fi"), symbol: "wifi.exclamationmark")
        }
    }

    private func refused(_ ssid: String, why: TandemWifiRefusal, by id: String) {
        let who = name(of: id)
        let text = switch why {
        case .cannotShare: String(localized: "\(who) cannot hand over the password of \(ssid)")
        case .notOnIt: String(localized: "\(who) is no longer on \(ssid)")
        case .unreadable: String(localized: "\(who) could not read the password of \(ssid)")
        default: String(localized: "\(who) did not send the Wi-Fi")
        }
        FloatingToast.show(text, symbol: "wifi.exclamationmark")
    }

    @discardableResult
    private func reply(_ id: String, _ message: TandemWifi) async -> Bool {
        guard let engine else { return false }
        return (try? await engine.sendWifi(target: id, wifi: message)) != nil
    }

    private func name(of id: String) -> String {
        EngineModel.shared.device(id)?.name ?? String(localized: "Another device")
    }

    /// Another device asks for the password of this Mac's network: its person said yes to taking it over there, so no second question
    /// here (macOS itself may still ask to let Tandem read it). The network is read again first: a change that came without an event
    /// would otherwise hand over the wrong one, or refuse the right one.
    private func asked(by id: String, for ssid: String) {
        Task { @MainActor in
            let network = await Task.detached { Self.read() }.value
            current = network
            guard let network, network.ssid == ssid else {
                await reply(id, .refuse(ssid: ssid, why: .notOnIt))
                return
            }
            guard network.security.shareable else {
                await reply(id, .refuse(ssid: ssid, why: .cannotShare))
                return
            }
            send(network, to: id)
        }
    }

    /// Reads the password (macOS asks the person) and sends it. An open network has none to read.
    private func send(_ network: TandemWifiNetwork, to id: String) {
        let who = name(of: id)
        Task { @MainActor in
            let password = network.security == .open ? "" : await Task.detached { Self.password(for: network.ssid) }.value
            guard let password else {
                await reply(id, .refuse(ssid: network.ssid, why: .unreadable))
                FloatingToast.show(String(localized: "macOS did not hand over the password of \(network.ssid)"), symbol: "wifi.exclamationmark")
                return
            }
            if await reply(id, .share(network: network, password: password)) {
                FloatingToast.show(String(localized: "Wi-Fi sent to \(who)"), symbol: "wifi")
            } else {
                FloatingToast.show(String(localized: "\(who) cannot be reached right now"), symbol: "wifi.exclamationmark")
            }
        }
    }

    /// "Share Wi-Fi" on the page of a device: no question here, the person pressed it. What is missing is said now, with the way to fix it.
    func share(with device: TandemDevice) {
        Task { @MainActor in
            let network = await Task.detached { Self.read() }.value
            current = network
            await engine?.setWifiNetwork(network: network)
            guard let network else {
                if !LocationAccess.allowed { explainLocation() } else { FloatingToast.show(String(localized: "This Mac is not on Wi-Fi"), symbol: "wifi.slash") }
                return
            }
            guard network.security.shareable else {
                FloatingToast.show(
                    network.security == .enterprise
                        ? String(localized: "\(network.ssid) has a sign-in for each person, which cannot be shared")
                        : String(localized: "\(network.ssid) uses an old kind of security that cannot be shared"),
                    symbol: "wifi.exclamationmark"
                )
                return
            }
            send(network, to: device.id)
        }
    }

    private func explainLocation() {
        let unasked = LocationAccess.status == .notDetermined
        GlassQuestion.shared.ask(
            symbol: "location",
            title: String(localized: "Tandem needs Location for the name of your Wi-Fi"),
            body: String(localized: "macOS only names the Wi-Fi network to apps that may see the location. Tandem never reads your position."),
            answers: [
                GlassQuestion.Answer(title: "Not Now", action: {}),
                GlassQuestion.Answer(title: unasked ? "Allow" : "Open Settings", prominent: true, action: {
                    if unasked { LocationAccess.ask() } else { LocationAccess.openSettings() }
                }),
            ]
        )
    }

    /// Another device hands this Mac its Wi-Fi without being asked (Share Wi-Fi there). Joining moves this Mac to another network, so
    /// the person here says yes first.
    private func handed(_ network: TandemWifiNetwork, password: String, by id: String) {
        let who = name(of: id)
        GlassQuestion.shared.ask(
            symbol: "wifi",
            title: String(localized: "Join \(network.ssid)?"),
            body: String(localized: "\(who) shared its Wi-Fi with this Mac."),
            answers: [
                GlassQuestion.Answer(title: "Not Now", action: {}),
                GlassQuestion.Answer(title: "Join Network", prominent: true, action: { [weak self] in self?.join(network, password: password) }),
            ]
        )
    }

    private func join(_ network: TandemWifiNetwork, password: String) {
        Task { @MainActor in
            let joined = await Task.detached { Self.associate(network.ssid, password: password) }.value
            if joined {
                FloatingToast.show(String(localized: "Joined \(network.ssid)"), symbol: "wifi")
                refresh()
            } else {
                FloatingToast.show(String(localized: "Could not join \(network.ssid)"), symbol: "wifi.exclamationmark")
            }
        }
    }

    /// CoreWLAN first, which keeps the password off a command line; it may not find the network without Location, and then
    /// `networksetup` joins by name.
    nonisolated static func associate(_ ssid: String, password: String) -> Bool {
        guard let interface = CWWiFiClient.shared().interface() else { return false }
        if let found = try? interface.scanForNetworks(withName: ssid).first,
           (try? interface.associate(to: found, password: password.isEmpty ? nil : password)) != nil {
            return true
        }
        guard let device = interface.interfaceName else { return false }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        process.arguments = ["-setairportnetwork", device, ssid] + (password.isEmpty ? [] : [password])
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        guard (try? process.run()) != nil else { return false }
        let output = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        // networksetup says it failed in words and still exits with 0.
        return process.terminationStatus == 0 && !output.contains("Failed") && !output.contains("Could not") && !output.contains("Error")
    }
}

extension WifiShare: CWEventDelegate {
    nonisolated func ssidDidChangeForWiFiInterface(withName interfaceName: String) {
        Task { @MainActor in WifiShare.shared.refresh() }
    }

    nonisolated func linkDidChangeForWiFiInterface(withName interfaceName: String) {
        Task { @MainActor in WifiShare.shared.refresh() }
    }
}

extension WifiShare: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            WifiShare.shared.locationAllowed = LocationAccess.allowed
            WifiShare.shared.refresh()
        }
    }
}

extension TandemWifiSecurity {
    var shareable: Bool { self == .open || self == .wpa2 || self == .wpa3 }
}
