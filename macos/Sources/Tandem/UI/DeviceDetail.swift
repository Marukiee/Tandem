import AppKit
import CoreBluetooth
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

struct DeviceDetail: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    /// Opens the files of this device. Only offered for devices that know how to show them.
    var onBrowse: () -> Void = {}
    /// Opens the text messages of this phone.
    var onMessages: () -> Void = {}
    /// Opens the Shared page, narrowed to this device.
    var onViewAll: () -> Void = {}

    @LocalState private var confirmRemoval = false
    /// The address at which this computer answers for ssh, once looked for.
    @LocalState private var sshAddress: String?

    @LocalState private var position = ScrollPosition(edge: .top)

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                // Only what sits under the header is swapped when the device changes.
                // Stacked, so the old and the new one cross-fade in the same place.
                ZStack(alignment: .top) {
                    content
                        .id(device.id)
                        .transition(.rise)
                }
            }
            .pagePadding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .scrollPosition($position)
        .animation(.tandem, value: device.id)
        .animation(.tandem, value: device.online)
        // The battery comes a moment after the connection, in a status of its own: the space it takes is made smoothly too.
        .animation(.tandem, value: device.status.battery != nil)
        .animation(.tandem, value: shownPlayers)
        .onChange(of: device.id) {
            confirmRemoval = false
            position.scrollTo(edge: .top)
        }
        .confirmationDialog(
            "Remove \(device.name)?",
            isPresented: $confirmRemoval,
            titleVisibility: .visible
        ) {
            Button("Remove for good", role: .destructive) { model.remove(device.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It leaves the circle on every device and cannot come back without a new pairing.")
        }
    }

    // MARK: Header

    private var reach: Reach { model.reach(of: device) }

    private var deviceSubtitle: String {
        let system: String = switch device.platform {
        case .android: "Android"
        case .macOs: "macOS"
        case .linux: "Linux"
        case .windows: "Windows"
        case .ios: "iOS"
        case .other: String(localized: "Device")
        }
        guard let version = device.appVersion, !version.isEmpty else { return system }
        return "\(system) \u{00B7} Tandem \(version)"
    }

    private var header: some View {
        Card(radius: Metrics.card, padding: 22, tint: device.online ? Palette.indigo : nil) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 20) {
                    // The system's menu, like every other choice from a list in the app.
                    Menu {
                        ForEach(DeviceIconChoice.allCases, id: \.symbol) { choice in
                            Button { model.setIcon(choice.symbol, for: device.id) } label: {
                                Label(choice.label, systemImage: choice.symbol)
                            }
                        }
                        Divider()
                        Button("Use the default icon") { model.setIcon(nil, for: device.id) }
                    } label: {
                        DeviceGlyph(platform: device.platform, online: device.online, size: 80, deviceID: device.id)
                    }
                    .menuStyle(.button)
                    .buttonStyle(.plain)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Change the icon")

                    VStack(alignment: .leading, spacing: 8) {
                        Text(device.name)
                            .font(.title.weight(.bold))
                            .lineLimit(1)
                            .contentTransition(.opacity)

                        // What it is: the system and the version of Tandem on it, so the card says who this is and not only how it is reached.
                        Text(deviceSubtitle)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)

                        HStack(spacing: 8) {
                            Chip(symbol: reach.symbol, text: reach.text, tint: reach.color, strong: reach.reachable)
                            if device.online {
                                if usingItsHotspot {
                                    // This Mac is on the phone's hotspot: that says more than the kind of address.
                                    Chip(symbol: "personalhotspot", text: "Via hotspot", tint: Palette.indigo)
                                } else if let route = device.route {
                                    Chip(symbol: route.symbol, text: route.label)
                                }
                            }
                            if let rtt = device.rttMs, device.online {
                                Chip(symbol: "speedometer", text: "\(rtt) ms")
                            }
                        }
                        if device.online {
                            HStack(spacing: 8) {
                                phoneNetworkChips
                                // A computer: the network it is on, as a chip with the others and not as a line of its own under the buttons.
                                if device.platform != .android, let ssid = device.lastSeen?.ssid {
                                    Chip(symbol: "wifi", text: "\(ssid)")
                                }
                                if device.status.dnd == true {
                                    Chip(symbol: "moon.fill", text: "Do not disturb")
                                }
                            }
                        } else {
                            Text("Tandem connects again as soon as this device can be reached.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)

                    if let battery = device.status.battery {
                        BatteryRing(battery: battery, size: 74)
                            .transition(.scale.combined(with: .opacity))
                    }
                }

                // What it plays, in the card: the card grows when it starts and shrinks when it stops. Its own id, so a
                // player picked on one device is not the pick on the next.
                NowPlayingCard(device: device)
                    .id(device.id)
            }
        }
        // While the card shrinks the player is still on its way out, and is not drawn over what sits below the card.
        .clipShape(RoundedRectangle(cornerRadius: Metrics.card, style: .continuous))
    }

    /// The players shown in the header, as the page sees them, so the card around them grows and shrinks on the same change.
    private var shownPlayers: [String] {
        model.reach(of: device).reachable ? model.visibleMedia(for: device.id).map(\.id) : []
    }

    /// This Mac has joined the phone's hotspot, by itself or by hand.
    private var usingItsHotspot: Bool {
        if device.platform == .android, case .connected = model.hotspotStatus { return true }
        return false
    }

    /// What the phone's own connection is, said as the phone's, because on this page "mobile data" next to a Mac
    /// reads as the Mac's. While its hotspot is on over mobile data one chip says both: the hotspot is that data.
    @ViewBuilder
    private var phoneNetworkChips: some View {
        let hotspotOn = device.status.hotspot == true
        // A computer sends its network too (for where it was last seen), and these words are about a phone.
        if let network = device.status.network, !device.isComputer {
            if hotspotOn && network.kind == .cellular {
                Chip(symbol: "personalhotspot", text: "Hotspot on, sharing mobile data", tint: Palette.indigo)
            } else {
                Chip(symbol: networkSymbol(network), text: LocalizedStringKey(phoneNetworkText(network)))
                if hotspotOn { Chip(symbol: "personalhotspot", text: "Hotspot on", tint: Palette.indigo) }
            }
        } else if hotspotOn {
            Chip(symbol: "personalhotspot", text: "Hotspot on", tint: Palette.indigo)
        }
    }

    private func phoneNetworkText(_ network: TandemNetwork) -> String {
        switch network.kind {
        case .wifi: network.ssid.map { String(localized: "Phone on \($0)") } ?? String(localized: "Phone on Wi-Fi")
        case .cellular: network.roaming ? String(localized: "Phone on mobile data (roaming)") : String(localized: "Phone on mobile data")
        case .ethernet: String(localized: "Phone on Ethernet")
        case .none: String(localized: "Phone has no network")
        case .other: String(localized: "Phone on a network")
        }
    }

    private func networkSymbol(_ network: TandemNetwork) -> String {
        switch network.kind {
        case .wifi: "wifi"
        case .cellular: "antenna.radiowaves.left.and.right"
        case .ethernet: "cable.connector"
        case .none: "wifi.slash"
        case .other: "network"
        }
    }

    // MARK: Content

    private var content: some View {
        VStack(spacing: 18) {
            actions
            DropZone(device: device)
            if device.platform == .android { HotspotCard(device: device) }
            // What is set comes before what was sent: the list grows, and the settings should not move further down the more is sent.
            settings
            recentTransfers
        }
    }

    // MARK: Actions

    private var actions: some View {
        let isComputer = device.platform == .macOs || device.platform == .windows || device.platform == .linux
        return VStack(alignment: .leading, spacing: 10) {
            // All of it in one row of buttons that are as wide as their words, wrapping on a narrow window: what goes between the two devices first, then what this
            // Mac does on the other one (look at it, use it, log in to it, ring it).
            GlassEffectContainer(spacing: 0) {
                WrapRow(spacing: 10) {
                    GlassActionButton(title: "Send files", symbol: "paperplane.fill", prominent: true) { pickFiles() }
                        .disabled(!device.online)
                    // Small enough for Bluetooth, so it works with no network as long as a link is up.
                    GlassActionButton(title: "Send clipboard", symbol: "doc.on.clipboard") {
                        model.sendClipboard(to: [device.id])
                    }
                    .disabled(!(device.online || device.ble))
                    if device.caps.contains("wifi.join"), WifiShare.shared.worthSharing(with: device) { WifiShareButton(device: device) }
                    // A computer offers Downloads, Documents and Desktop as well now, so every device with files can be browsed.
                    if device.caps.contains("files") {
                        GlassActionButton(title: "Browse files", symbol: "folder") { onBrowse() }
                            .disabled(!device.online)
                    }
                    if device.platform == .android {
                        CallButton(device: device)
                    }
                    if device.caps.contains("sms") {
                        GlassActionButton(title: "Messages", symbol: "message") { onMessages() }
                            .disabled(!device.online)
                            .opacity(device.online ? 1 : 0.5)
                    }
                    if isComputer || device.platform == .android {
                        LiveActionRow(device: device)
                    }
                    if device.platform == .android {
                        let ringing = model.ringing.contains(device.id)
                        GlassActionButton(
                            title: ringing ? "Stop ringing" : "Find phone",
                            symbol: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right",
                            prominent: ringing
                        ) {
                            model.ring(device.id, on: !ringing)
                            model.showToast(ringing ? String(localized: "Stopped ringing") : String(localized: "Your phone is ringing"))
                        }
                        .disabled(!device.online)
                        // This Mac's sound on the phone, one press from the top, as the button in the menu bar has it. It is the same as picking the
                        // phone in the sound menu.
                        let speaking = model.speaker.device == device.id
                        GlassActionButton(
                            title: speaking ? "Stop phone output" : "Phone as output",
                            symbol: speaking ? "speaker.wave.3.fill" : "speaker.wave.2",
                            prominent: speaking
                        ) { model.toggleSpeaker(for: device.id) }
                        .disabled(!device.online && !speaking)
                    }
                    // A computer can be logged in to from here. The button looks the same whether that is set up there or not: someone may
                    // not want it, so what is missing is only said when the button is pressed.
                    if isComputer {
                        GlassActionButton(title: "Terminal", symbol: "terminal") {
                            Task {
                                var address = sshAddress
                                if address == nil { address = await SSHAccess.reachableAddress(of: device) }
                                if let address {
                                    SSHAccess.open(device, at: address)
                                } else {
                                    model.showToast(SSHAccess.reason(for: device))
                                }
                            }
                        }
                        .disabled(!device.online)
                        .help("Log in to this computer over SSH")
                        PlaySoundButton(device: device)
                    }
                    // This Mac's sound on a computer that plays the sound of other devices. What can go wrong there is said when it is
                    // pressed.
                    if isComputer, device.caps.contains("audio.play") {
                        let speaking = model.speaker.device == device.id
                        GlassActionButton(
                            title: speaking ? "Stop speaker" : "Use as speaker",
                            symbol: speaking ? "speaker.wave.3.fill" : "speaker.wave.2",
                            prominent: speaking
                        ) { model.toggleSpeaker(for: device.id) }
                        .disabled(!device.online && !speaking)
                        .help("Play the sound of this Mac on \(device.name)")
                    }
                }
            }
            if isComputer { LastSeenText(device: device) }
        }
        .animation(.tandemFade, value: device.online)
        .animation(.tandemFade, value: device.ble)
        // A button that turns on gets other words and so another width: the pills next to it move over smoothly, not in a jump.
        .animation(.tandem, value: liveState)
        .task(id: "\(device.id)-\(device.online)") {
            // What was found the last time is there at once, and is looked at again quietly.
            if let known = SSHAccess.seen[device.id] {
                sshAddress = known.isEmpty ? nil : known
            }
            let found = await SSHAccess.reachableAddress(of: device)
            SSHAccess.seen[device.id] = found ?? ""
            sshAddress = found
        }
    }

    /// What runs between this Mac and the device, as one value that changes whenever a button of the row turns on or off.
    private var liveState: String {
        let host = ScreenHost.shared.running.map { "\($0.peer)\($0.extend)" }.joined()
        let live = LiveManager.shared.sessions.filter { $0.peer == device.id }.map { "\($0.kind)\($0.extend)" }.joined()
        return host + live + "\(model.ringing.contains(device.id))\(model.speaker.device == device.id)\(FindOthers.shared.ringing.contains(device.id))"
    }

    private func pickFiles() {
        guard device.online else {
            model.showToast(String(localized: "That device is not connected right now"))
            return
        }
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = String(localized: "Send")
        panel.message = String(localized: "Choose files to send to \(device.name)")
        if panel.runModal() == .OK { model.send(urls: panel.urls, to: [device.id]) }
    }

    // MARK: Transfers

    @ViewBuilder
    private var recentTransfers: some View {
        let items = Array(model.transfers.filter { $0.peer == device.id }.prefix(6))
        if !items.isEmpty {
            VStack(spacing: 3) {
                HStack {
                    Text("Recent").font(.headline)
                    Spacer(minLength: 8)
                    Button(action: onViewAll) {
                        HStack(spacing: 4) {
                            Text("View all")
                            Image(systemName: "chevron.right").font(.caption.weight(.bold))
                        }
                        .font(.callout.weight(.medium))
                        .foregroundStyle(Palette.indigo)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .hoverHighlight(radius: 10)
                    }
                    .buttonStyle(.plain)
                    .help("Everything sent to and received from \(device.name)")
                }
                .padding(.horizontal, 6)
                .padding(.bottom, 6)
                Card(radius: Metrics.card, padding: Metrics.cardInset) {
                    VStack(spacing: 2) {
                        ForEach(items, id: \.id) { item in
                            TransferEntry(item: item, peerName: device.name)
                                .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                        }
                    }
                }
            }
            .transition(.opacity.combined(with: .move(edge: .bottom)))
            .animation(.tandem, value: items.map(\.id))
        }
    }

    // MARK: Settings

    private var settings: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(symbol: "doc.on.clipboard", title: "Sync clipboard", subtitle: "Text and links you copy show up on both devices") {
                    Toggle("", isOn: Binding(
                        get: { device.clipboardEnabled },
                        set: { model.setSettings(device, clipboard: $0) }
                    ))
                    .controlSize(.mini)
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "bell.badge", title: "Show its notifications", subtitle: "Phone notifications appear here, and you can reply") {
                    Toggle("", isOn: Binding(
                        get: { device.notificationsEnabled },
                        set: { model.setSettings(device, notifications: $0) }
                    ))
                    .controlSize(.mini)
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "tray.and.arrow.down", title: "Accept files automatically", subtitle: "Turn off to be asked before something arrives") {
                    Toggle("", isOn: Binding(
                        get: { device.autoAccept },
                        set: { model.setSettings(device, autoAccept: $0) }
                    ))
                    .controlSize(.mini)
                }
                if device.platform == .android {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    speakerRow
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                if device.isComputer || device.platform == .android {
                    SideRow(device: device)
                    Divider().opacity(0.4).padding(.horizontal, 14)
                }
                SettingRow(symbol: "trash", title: "Remove this device", subtitle: device.vouchedByRemoved ? "It was added by a device that has since been removed" : "It leaves the circle everywhere") {
                    Button("Remove", role: .destructive) { confirmRemoval = true }
                        .buttonStyle(.tandemGlass)
                }
            }
        }
    }
}

extension DeviceDetail {
    /// Offers the phone as a sound output, all the time: the switch stays on, and the phone is there to pick in the
    /// sound menu or System Settings, Sound. Only while it is picked does this Mac's sound play on it. The button
    /// is the same thing from here.
    fileprivate var speakerRow: some View {
        let streaming = model.speaker.device == device.id
        let enabled = model.speakerEnabled(for: device.id)
        // What went wrong the last time it was used. A sound output that could not be made is said when the button is pressed: until then
        // the row looks as it always does.
        let failure: String? = {
            if case let .failed(text) = model.speaker { return text }
            return nil
        }()
        return SettingRow(
            symbol: streaming ? "speaker.wave.3.fill" : "speaker.wave.2",
            title: "Use this phone as a speaker",
            subtitle: LocalizedStringKey(speakerSubtitle(enabled: enabled, failure: failure))
        ) {
            HStack(spacing: 10) {
                if case let .starting(id) = model.speaker, id == device.id { PillSpinner(size: 16) }
                if enabled {
                    Button(LocalizedStringKey(streaming ? "Stop" : "Use now")) {
                        if !streaming, let problem = model.speakerDevices.problem { model.showToast(problem) }
                        model.toggleSpeaker(for: device.id)
                    }
                    .buttonStyle(.tandemGlass)
                    .disabled(!device.online && !streaming)
                }
                Toggle("", isOn: Binding(get: { enabled }, set: { model.setSpeakerEnabled($0, for: device.id) }))
                    .controlSize(.mini)
            }
        }
    }

    private func speakerSubtitle(enabled: Bool, failure: String?) -> String {
        if let failure { return failure }
        guard enabled else { return String(localized: "Not offered as a sound output") }
        switch model.speaker {
        case .on(device.id): return String(localized: "This Mac's sound plays on \(device.name). Pick another output to stop.")
        case .starting(device.id): return String(localized: "Starting")
        default:
            return String(localized: "Pick \(device.name) (Tandem) in the sound menu")
        }
    }
}

struct Chip: View {
    let symbol: String
    let text: LocalizedStringKey
    var tint: Color = .secondary
    /// A filled chip, for the one that matters most.
    var strong = false

    var body: some View {
        Label {
            Text(text).font(.caption.weight(strong ? .semibold : .medium))
        } icon: {
            Image(systemName: symbol).font(.caption2)
        }
        .foregroundStyle(strong ? Color.white : tint)
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(strong ? tint : tint.opacity(0.12), in: .capsule)
        .animation(.tandemFade, value: strong)
    }
}

struct SettingRow<Trailing: View>: View {
    let symbol: String
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Palette.indigo)
                .frame(width: 30, height: 30)
                .background(Palette.indigo.opacity(0.12), in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.callout.weight(.medium))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            // Switches, as the grouped forms of Settings have them, at the size they have there (mini).
            trailing.labelsHidden().toggleStyle(.switch)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .hoverHighlight(radius: Metrics.inner(Metrics.card, inset: 6))
    }
}

// MARK: Drop zone

struct DropZone: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var targeted = false
    @LocalState private var hovering = false

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: targeted ? "arrow.down.circle.fill" : "square.and.arrow.down")
                .font(.system(size: 30, weight: .semibold))
                .fadingForeground(Color.secondary, to: Palette.indigo, when: targeted || hovering)
                .symbolEffect(.bounce, value: targeted)
                .contentTransition(.symbolEffect(.replace))
            Text(targeted ? "Let go to send" : "Drop files here")
                .font(.headline)
                .foregroundStyle(targeted ? Palette.indigo : Color.primary)
            Text("Folders are sent as a zip.").font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 34)
        .background {
            RoundedRectangle(cornerRadius: Metrics.card, style: .continuous)
                .strokeBorder(
                    targeted ? Palette.indigo : (hovering ? Palette.indigo.opacity(0.55) : Color.primary.opacity(0.16)),
                    style: StrokeStyle(lineWidth: targeted ? 2.5 : 1.5, dash: [8, 7])
                )
                .background(
                    RoundedRectangle(cornerRadius: Metrics.card, style: .continuous)
                        .fill(Palette.indigo.opacity(targeted ? 0.10 : (hovering ? 0.04 : 0)))
                )
        }
        .scaleEffect(targeted ? 1.015 : 1)
        .animation(.tandemSpringy, value: targeted)
        .animation(.tandemFade, value: hovering)
        .onHover { hovering = $0 }
        .dropDestination(for: URL.self) { urls, _ in
            guard device.online else {
                model.showToast(String(localized: "That device is not connected right now"))
                return false
            }
            model.send(urls: urls, to: [device.id])
            return true
        } isTargeted: { targeted = $0 }
        .opacity(device.online ? 1 : 0.5)
    }
}

// MARK: Hotspot

struct HotspotCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @AppStorage("autoHotspot") private var auto = false
    @AppStorage("hotspotSSID") private var ssid = ""
    @LocalState private var bluetooth = CBManager.authorization
    @LocalState private var showSetup = false

    /// Bluetooth is allowed and the phone's network is filled in. The switch only shows on when this is so, and turning it on before
    /// opens the setup, which turns it on at its end: what it needs is said when it is wanted, not on the page all the time.
    private var ready: Bool {
        bluetooth == .allowedAlways && !ssid.isEmpty && (HotspotCredentials.password()?.isEmpty == false)
    }

    var body: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(
                    symbol: "personalhotspot",
                    title: "Use its hotspot when I have no connection",
                    subtitle: "Your phone turns its hotspot on and this Mac joins it"
                ) {
                    Toggle("", isOn: Binding(get: { auto && ready }, set: { on in
                        if on && !ready { showSetup = true } else { auto = on }
                    }))
                    .controlSize(.mini)
                }
                if model.hotspotStatus != .idle {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    HStack(spacing: 10) {
                        if model.hotspotStatus.isBusy { PillSpinner(size: 18) }
                        Text(model.hotspotStatus.text)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Spacer()
                        if case .connected = model.hotspotStatus {
                            Button("Stop") { model.hotspot.stop(device.id) }.buttonStyle(.tandemGlass)
                        }
                        // A failure leaves its message up, and the way to try again with it.
                        if case .failed = model.hotspotStatus {
                            Button("Try again") { model.hotspot.requestNow(device.id) }.buttonStyle(.tandemGlass)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Need it right now?").font(.callout).foregroundStyle(.secondary)
                            if !device.online {
                                // Without a network the request goes over Bluetooth, which only works in range.
                                Text(model.reach(of: device).reachable ? "Asked over Bluetooth, your phone is in range" : "Asked over Bluetooth, so your phone has to be near")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        Spacer()
                        Button("Turn on hotspot") {
                            if ready { model.hotspot.requestNow(device.id) } else { showSetup = true }
                        }
                        .buttonStyle(.tandemGlass)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
            }
        }
        .animation(.tandem, value: model.hotspotStatus)
        .animation(.tandem, value: ready)
        .sheet(isPresented: $showSetup, onDismiss: { bluetooth = CBManager.authorization }) { HotspotSetupSheet() }
        // The permission only changes in System Settings, so it is read again when the person comes back from there.
        // A timer that asked the system every second kept the app and the permission service awake for nothing.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            bluetooth = CBManager.authorization
        }
    }
}

/// On which side of this Mac the device stands: chosen by hand, or found by sound. A second screen goes on that side, and a computer is put
/// there for the shared mouse.
private struct SideRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var side: String?
    @LocalState private var finding = false

    var body: some View {
        SettingRow(symbol: "rectangle.lefthalf.inset.filled.arrow.left", title: "Where it stands", subtitle: "A second screen goes on this side, and so does this computer for the shared mouse") {
            HStack(spacing: 10) {
                if device.caps.contains("side.listen") {
                    Button(finding ? "Listening…" : "Find by sound") {
                        finding = true
                        Task {
                            let found = await SideFinder.find(device)
                            finding = false
                            side = DeviceSides.side(of: device.id)
                            model.showToast(SideFinder.message(found, name: device.name))
                        }
                    }
                    .buttonStyle(.tandemGlass)
                    .disabled(!device.online || finding)
                }
                Picker("", selection: Binding(get: { side ?? "right" }, set: { side = $0; DeviceSides.set($0, for: device.id) })) {
                    Text("Left").tag("left")
                    Text("Right").tag("right")
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
        }
        .onAppear { side = DeviceSides.side(of: device.id) }
    }
}
