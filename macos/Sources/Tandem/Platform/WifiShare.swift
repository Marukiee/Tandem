import CoreLocation
import CoreWLAN
import Foundation
import TandemCore

/// Hands the Wi-Fi this Mac is on to another device, the way an iPhone offers a friend its password. The core tells the others which
/// network this Mac is on; a phone that is not on it asks, and the person here says whether the password goes. A computer that hands
/// this Mac its Wi-Fi is asked about here too, before this Mac joins.
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
    /// The devices whose question is on screen, so asking twice does not stack questions.
    @ObservationIgnored private var asking: Set<String> = []
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
        }
    }

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
            offered(network, password: password, by: id)
        case .network, .refuse:
            // This Mac does not ask other devices for their Wi-Fi: it offers its own, and joins what it is handed.
            break
        }
    }

    @discardableResult
    private func reply(_ id: String, _ message: TandemWifi) async -> Bool {
        guard let engine else { return false }
        return (try? await engine.sendWifi(target: id, wifi: message)) != nil
    }

    private func name(of id: String) -> String {
        EngineModel.shared.device(id)?.name ?? String(localized: "Another device")
    }

    /// A device that is not on this Mac's network asks for its password. The person here decides, like on an iPhone. The network is
    /// read again first: a change that came without an event would otherwise hand over the wrong one, or refuse the right one.
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
            question(network, for: id)
        }
    }

    private func question(_ network: TandemWifiNetwork, for id: String) {
        let ssid = network.ssid
        guard asking.insert(id).inserted else { return }
        let who = name(of: id)
        var answered = false
        GlassQuestion.shared.ask(
            symbol: "wifi",
            title: String(localized: "Share the password of \(ssid) with \(who)?"),
            body: String(localized: "\(who) is not on this network. macOS may ask you to allow Tandem to read the password."),
            answers: [
                GlassQuestion.Answer(title: "Don't Share", action: { [weak self] in
                    answered = true
                    self?.asking.remove(id)
                    Task { await self?.reply(id, .refuse(ssid: ssid, why: .declined)) }
                }),
                GlassQuestion.Answer(title: "Share", prominent: true, action: { [weak self] in
                    answered = true
                    self?.asking.remove(id)
                    self?.send(network, to: id)
                }),
            ],
            closeAfter: .seconds(90)
        )
        // Nobody here: the phone hears no rather than waiting for ever.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(90))
            guard !answered, let self, self.asking.remove(id) != nil else { return }
            await self.reply(id, .refuse(ssid: ssid, why: .declined))
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

    /// Another computer hands this Mac its Wi-Fi. Joining moves this Mac to another network, so the person here says yes first.
    private func offered(_ network: TandemWifiNetwork, password: String, by id: String) {
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
