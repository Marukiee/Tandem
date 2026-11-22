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
    /// Opens the Shared page, narrowed to this device.
    var onViewAll: () -> Void = {}

    @LocalState private var confirmRemoval = false
    /// The address at which this computer answers for ssh, once looked for.
    @LocalState private var sshAddress: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                // The head and what plays, each on a card of its own: side by side where there is room, under each other where
                // there is not. The row decides that from its own width, so the page arrives in the layout it keeps.
                HeadRow {
                    header
                    PlayersCard(device: device)
                }
                content
            }
            .devicePagePadding()
            // As wide as Shared may be: in a wide window the head and the player each have room for all they say.
            .frame(maxWidth: 1080)
            .frame(maxWidth: .infinity)
        }
        .ignoresSafeArea(.container, edges: .top)
        .animation(.tandem, value: device.online)
        // The battery comes a moment after the connection, in a status of its own: the space it takes is made smoothly too.
        .animation(.tandem, value: device.status.battery != nil)
        .animation(.tandem, value: shownPlayers)
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

    private var system: String {
        switch device.platform {
        case .android: "Android"
        case .macOs: "macOS"
        case .linux: "Linux"
        case .windows: "Windows"
        case .ios: "iOS"
        case .other: String(localized: "Device")
        }
    }

    /// What it is: the system and the version of Tandem on it, where they are known.
    private var systemLine: String {
        guard let version = device.appVersion, !version.isEmpty else { return system }
        return "\(system) \u{00B7} Tandem \(version)"
    }

    /// The tiles under the name: what there is to know about the device at a glance, each where it is known. That it is connected
    /// and how quick it answers are small under its name: they matter when they go wrong, not as the first thing on the page.
    @ViewBuilder
    private var tiles: some View {
        if let battery = device.status.battery {
            HeadTile(
                symbol: Self.batterySymbol(battery),
                tint: batteryTint(battery, connected: device.online),
                value: Text(verbatim: "\(battery.level)%"),
                label: battery.charging && device.online ? Text("Charging") : Text("Battery")
            )
        }
        if device.online, let network = device.status.network {
            HeadTile(
                symbol: Self.networkSymbol(network.kind),
                tint: Palette.indigo,
                value: network.ssid.flatMap { $0.isEmpty ? nil : Text(verbatim: $0) } ?? Self.networkName(network.kind),
                label: network.metered ? Text("Network, metered") : Text("Network")
            )
        }
        if device.platform == .android, device.online, let sound = device.status.sound {
            let look = Self.soundLook(sound)
            HeadTile(symbol: look.symbol, tint: Palette.indigo, value: Text(look.title), label: Text("Sound"))
        }
        if device.isComputer, device.online, let free = device.status.freeStorage {
            HeadTile(
                symbol: "internaldrive", tint: Palette.indigo,
                value: Text(verbatim: ByteCountFormatter.string(fromByteCount: Int64(free), countStyle: .file)),
                label: Text("Free space")
            )
        }
    }

    private var hasTiles: Bool {
        let status = device.status
        return status.battery != nil || (device.online && (status.network != nil
            || (device.platform == .android && status.sound != nil) || (device.isComputer && status.freeStorage != nil)))
    }

    static func batterySymbol(_ battery: TandemBattery) -> String {
        if battery.charging { return "battery.100percent.bolt" }
        switch battery.level {
        case 88...: return "battery.100percent"
        case 63...: return "battery.75percent"
        case 38...: return "battery.50percent"
        case 13...: return "battery.25percent"
        default: return "battery.0percent"
        }
    }

    private static func networkSymbol(_ kind: TandemNetKind) -> String {
        switch kind {
        case .wifi: "wifi"
        case .cellular: "antenna.radiowaves.left.and.right"
        case .ethernet: "cable.connector"
        case .none, .other: "network"
        }
    }

    private static func networkName(_ kind: TandemNetKind) -> Text {
        switch kind {
        case .wifi: Text("Wi-Fi")
        case .cellular: Text("Mobile data")
        case .ethernet: Text("Ethernet")
        case .none: Text("No network")
        case .other: Text("Network")
        }
    }

    private static func soundLook(_ sound: TandemSoundMode) -> (title: LocalizedStringKey, symbol: String) {
        switch sound {
        case .vibrate: ("Vibrate", "iphone.gen3.radiowaves.left.and.right")
        case .silent: ("Silent", "bell.slash")
        case .doNotDisturb: ("Do not disturb", "moon.fill")
        case .sound: ("Sound on", "bell")
        }
    }

    /// That it is there, at the top right of the head: a tick or a dashed round and the word, on a faint pill. How quick it
    /// answers is in its help, as it matters only when something is slow.
    private var statePill: some View {
        StatePill(there: reach.reachable, text: reach.text)
            .help(device.online ? device.rttMs.map { String(localized: "Answers in \($0) ms") } ?? "" : "")
    }

    /// The head of the page: the device, its name, what it runs on and that it is there, and under them tiles with what there is
    /// to know at a glance. Tiles and no bar: a bar beside the player read as a second player. Beside a taller player the tiles grow,
    /// so the card never has empty room above or under what it says.
    private var header: some View {
        Card(radius: Metrics.card, padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                headRow
                if hasTiles {
                    HStack(spacing: 8) { tiles }
                        .frame(maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: hasTiles ? .topLeading : .leading)
            // In the corner, as far from the top of the card as from its side.
            .overlay(alignment: .topTrailing) { statePill }
        }
    }

    private var headRow: some View {
        HStack(spacing: 12) {
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
                    DeviceGlyph(platform: device.platform, online: device.online, size: 52, deviceID: device.id)
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Change the icon")

                VStack(alignment: .leading, spacing: 1) {
                    // The pill on the line of the name, so the line under it has the whole width for the system and the version.
                    HStack(spacing: 8) {
                        Text(device.name)
                            .font(.title3.weight(.semibold))
                            .lineLimit(1)
                            // A long name is a little smaller before it is cut short.
                            .minimumScaleFactor(0.8)
                        Spacer(minLength: 0)
                        // Its room on the line of the name, so a long name stops before it. The pill itself sits in the corner of the card.
                        statePill.hidden()
                    }
                    Text(verbatim: systemLine)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
        }
    }

    /// The players shown under the head, as the page sees them, so the room for their card opens and closes on the same change.
    private var shownPlayers: [String] {
        model.reach(of: device).reachable ? model.visibleMedia(for: device.id).map(\.id) : []
    }

    /// This Mac has joined the phone's hotspot, by itself or by hand.
    private var usingItsHotspot: Bool {
        if device.platform == .android, case .connected = model.hotspotStatus { return true }
        return false
    }

    // MARK: Content

    private var content: some View {
        VStack(spacing: 18) {
            actions
            DropZone(device: device)
            if device.platform == .android { HotspotCard(device: device) }
            // What is set comes before what was sent: the list grows, and the settings should not move further down the more is sent.
            settings
            deviceSettingsCard
            recentTransfers
            removal
        }
    }

    // MARK: Actions

    private var actions: some View {
        let isComputer = device.platform == .macOs || device.platform == .windows || device.platform == .linux
        return VStack(alignment: .leading, spacing: 12) {
            // Grouped by what they are for, the groups side by side as cards with a row per action, and under each other when the window
            // is too narrow. Pills as wide as their words wrapped into ragged lines, and a group of one took a whole line.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 12) { actionGroups(isComputer: isComputer) }
                    .fixedSize(horizontal: false, vertical: true)
                VStack(spacing: 12) { actionGroups(isComputer: isComputer) }
            }
            if isComputer { LastSeenText(device: device) }
        }
        .animation(.tandemFade, value: device.online)
        .animation(.tandemFade, value: device.ble)
        .animation(.tandemFade, value: device.caps)
        .animation(.tandemFade, value: device.status.lock)
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

    @ViewBuilder
    private func actionGroups(isComputer: Bool) -> some View {
        ActionGroup(title: "Share") {
            GlassActionButton(title: "Send files", symbol: "paperplane.fill", prominent: true) { pickFiles() }
                .disabled(!device.online)
                .help("Send files to \(device.name)")
            // Small enough for Bluetooth, so it works with no network as long as a link is up.
            GlassActionButton(title: "Send clipboard", symbol: "doc.on.clipboard") {
                model.sendClipboard(to: [device.id])
            }
            .disabled(!(device.online || device.ble))
            .help("Send clipboard to \(device.name)")
            WifiShareButton(device: device)
            // A computer offers Downloads, Documents and Desktop as well now, so every device with files can be browsed.
            let browsable = device.caps.contains("files")
            GlassActionButton(title: "Browse files", symbol: "folder") {
                if browsable { onBrowse() } else { model.showToast(String(localized: "Update Tandem on \(device.name)")) }
            }
            .opacity(device.online && !browsable ? 0.5 : 1)
            .disabled(!device.online)
            .help("Browse files on \(device.name)")
        }
        if isComputer || device.platform == .android {
            ActionGroup(title: "Screen", tone: .rose) {
                LiveActionRow(device: device)
                // Lock and unlock are one place: what is locked and can be opened from here offers that instead.
                if isComputer, device.online, device.status.unlock == true, device.status.locked == true {
                    RemoteUnlockButton(device: device)
                } else {
                    RemoteLockButton(device: device)
                }
            }
        }
        if device.platform == .android {
            ActionGroup(title: "Sound", tone: .mint) {
                // This Mac's sound on the phone, one press from the top, as the button in the menu bar has it. It is the same as picking
                // the phone in the sound menu.
                let speaking = model.speaker.device == device.id
                GlassActionButton(
                    title: speaking ? "Stop phone output" : "Phone as output",
                    symbol: speaking ? "speaker.wave.3.fill" : "speaker.wave.2",
                    prominent: speaking
                ) { model.toggleSpeaker(for: device.id) }
                .disabled(!device.online && !speaking)
                // The phone as the microphone of this Mac. Grey only when it is away; what else is missing is said when it is pressed.
                let listening = PhoneMicrophone.shared.state.device == device.id
                GlassActionButton(
                    title: listening ? "Stop phone microphone" : "Phone as microphone",
                    symbol: listening ? "mic.fill" : "mic",
                    prominent: listening
                ) { PhoneMicrophone.shared.toggle(for: device.id) }
                .disabled(!device.online && !listening)
                let ringing = model.ringing.contains(device.id)
                GlassActionButton(
                    title: ringing ? "Stop sound" : "Play sound",
                    symbol: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right",
                    prominent: ringing
                ) {
                    model.ring(device.id, on: !ringing)
                    model.showToast(ringing ? String(localized: "Stopped ringing") : String(localized: "Your phone is ringing"))
                }
                .disabled(!device.online)
                // The watch is a device of its own with its own page, under this phone in the sidebar: finding it is done there.
                SoundModeButton(device: device)
            }
        }
        // What is done with that computer itself. Four rows, like the other groups: what it cannot do is grey and says why when pressed.
        if isComputer {
            ActionGroup(title: "Computer", tone: .mint) {
                // It can be logged in to from here. The button looks the same whether that is set up there or not: someone may not want
                // it, so what is missing is only said when the button is pressed.
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
                RemoteKeepAwakeButton(device: device)
                // This Mac's sound on a computer that plays the sound of other devices. What can go wrong there is said when it is pressed.
                let speaking = model.speaker.device == device.id
                let plays = device.caps.contains("audio.play")
                GlassActionButton(
                    title: speaking ? "Stop speaker" : "Use as speaker",
                    symbol: speaking ? "speaker.wave.3.fill" : "speaker.wave.2",
                    prominent: speaking
                ) {
                    if plays || speaking { model.toggleSpeaker(for: device.id) }
                    else { model.showToast(String(localized: "Update Tandem on \(device.name)")) }
                }
                .opacity(device.online && !plays ? 0.5 : 1)
                .disabled(!device.online && !speaking)
                .help("Play the sound of this Mac on \(device.name)")
                PlaySoundButton(device: device)
            }
        }
    }

    /// What runs between this Mac and the device, as one value that changes whenever a button of the row turns on or off.
    private var liveState: String {
        let host = ScreenHost.shared.running.map { "\($0.peer)\($0.extend)" }.joined()
        let live = LiveManager.shared.sessions.filter { $0.peer == device.id }.map { "\($0.kind)\($0.extend)" }.joined()
        return host + live + "\(model.ringing.contains(device.id))\(model.speaker.device == device.id)\(FindOthers.shared.ringing.contains(device.id))\(PhoneMicrophone.shared.state.device == device.id)"
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
                    .buttonStyle(.bouncy)
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
                if device.isComputer || device.platform == .android {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    SideRow(device: device)
                }
            }
        }
    }

    /// Everything else that is set for this device, and its name, on a page of its own: a card of its own, as the hotspot has, so
    /// it does not read as one more switch of the card above.
    private var deviceSettingsCard: some View {
        Card(radius: Metrics.card, padding: 6) {
            Button { DeviceSettingsShown.shared.id = device.id } label: {
                SettingRow(symbol: "gearshape", title: "Settings for this device", subtitle: "Its name, where it stands, and what it may do on this Mac") {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    /// At the very bottom of the page: it is done once, if ever, and should not sit between the things that are used.
    private var removal: some View {
        Card(radius: Metrics.card, padding: 6) {
            SettingRow(symbol: "trash", title: "Remove this device", subtitle: device.vouchedByRemoved ? "It was added by a device that has since been removed" : "It leaves the circle everywhere") {
                Button("Remove", role: .destructive) { confirmRemoval = true }
                    .buttonStyle(.tandemGlass)
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
            if TandemAudioDriver.shared.active { return String(localized: "Pick Tandem Speaker in the sound menu") }
            return String(localized: "Pick \(device.name) (Tandem) in the sound menu")
        }
    }
}

/// The head of a device and what it plays. With room they stand side by side, each half of the width and both as tall as the
/// taller one; without it, or with nothing playing, the head has the whole width at its own height and the player goes under it.
/// It decides from the width it is given, not from something set after the first layout: a page arrives in the layout it keeps.
struct HeadRow: Layout {
    /// From this width on the two fit side by side.
    static let room: CGFloat = 720
    /// The gap between the cards under them.
    var gap: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 780
        guard let head = subviews.first else { return .zero }
        guard subviews.count > 1 else {
            return CGSize(width: width, height: head.sizeThatFits(ProposedViewSize(width: width, height: nil)).height)
        }
        if width >= Self.room {
            let half = ((width - gap) / 2).rounded(.down)
            let heights = subviews.prefix(2).map { $0.sizeThatFits(ProposedViewSize(width: half, height: nil)).height }
            return CGSize(width: width, height: heights.max() ?? 0)
        }
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        return CGSize(width: width, height: heights.reduce(0, +) + gap * CGFloat(subviews.count - 1))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let head = subviews.first else { return }
        guard subviews.count > 1 else {
            head.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
            return
        }
        if bounds.width >= Self.room {
            let half = ((bounds.width - gap) / 2).rounded(.down)
            head.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: half, height: bounds.height))
            subviews[1].place(
                at: CGPoint(x: bounds.maxX - half, y: bounds.minY), anchor: .topLeading,
                proposal: ProposedViewSize(width: half, height: bounds.height)
            )
        } else {
            var y = bounds.minY
            for subview in subviews {
                let height = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
                subview.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: height))
                y += height + gap
            }
        }
    }
}

/// A small tonal pill in the head of a device, in the style of the filters: what it says in the secondary colour on a faint fill.
private struct HeadPill: View {
    var symbol: String?
    var symbolTint: Color?
    var dot: Color?
    let text: Text

    var body: some View {
        HStack(spacing: 5) {
            if let dot { Circle().fill(dot).frame(width: 7, height: 7) }
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(symbolTint ?? Color.secondary)
            }
            text.font(.caption.weight(.medium)).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Color.primary.opacity(0.06), in: .capsule)
        .fixedSize()
    }
}

/// Whether a device is there, as the head of its page says it: a tick or a dashed round and the word, on a faint pill. Quiet on
/// purpose; the same on the page of a device and of a watch.
struct StatePill: View {
    let there: Bool
    let text: LocalizedStringKey

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: there ? "checkmark.circle.fill" : "circle.dashed")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(there ? Color.green : Color.secondary)
            Text(text)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Color.primary.opacity(0.06), in: .capsule)
        .fixedSize()
    }
}

/// The colour of a battery in the head: the same as the ring around the device in the sidebar.
func batteryTint(_ battery: TandemBattery, connected: Bool) -> Color {
    if !connected { return .gray }
    if battery.charging { return .green }
    return battery.level <= 15 ? Palette.urgent : Palette.indigo
}

/// One fact in the head of a device, as a tonal tile: what it is small at the top and the value large under it, both starting at
/// the same edge, with the icon in its colour at the top right where it does not push the words in.
struct HeadTile: View {
    let symbol: String
    let tint: Color
    let value: Text
    let label: Text

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            VStack(alignment: .leading, spacing: 3) {
                label
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                value
                    .font(.headline.monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            Spacer(minLength: 0)
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(tint)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
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
        .animation(.hover(hovering), value: hovering)
        .onHover { hovering = $0 }
        .deviceDrop(device, model: model, targeted: $targeted)
        .opacity(device.online ? 1 : 0.5)
    }
}

/// A few actions under the name of what they are for, on a card of the same tint as the ones in the menu bar panel. The buttons in it
/// draw themselves as rows (see `actionButtonsAsRows`).
struct ActionGroup<Content: View>: View {
    let title: LocalizedStringKey
    /// The colour of the discs in it, so the groups are told apart at a glance.
    var tone: Tone = .indigo
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.top, 6)
                .padding(.bottom, 4)
            content
        }
        .padding(6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 22, style: .continuous))
        .environment(\.actionButtonsAsRows, true)
        .environment(\.actionTone, tone)
    }
}

// MARK: Hotspot

struct HotspotCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @AppStorage("autoHotspot") private var auto = false
    @LocalState private var profiles = HotspotProfiles.shared
    @LocalState private var bluetooth = CBManager.authorization
    @LocalState private var showSetup = false
    /// The name the phone said its hotspot has, for the setup: it opens with that filled in.
    @LocalState private var prefill: String?

    /// Bluetooth is allowed and the phone's network is filled in. The switch only shows on when this is so, and turning it on before
    /// opens the setup, which turns it on at its end: what it needs is said when it is wanted, not on the page all the time.
    private var ready: Bool {
        bluetooth == .allowedAlways && profiles.complete(for: device.id)
    }

    private func openSetup() {
        prefill = profiles.reported[device.id]
        showSetup = true
    }

    /// The row in the panel, or the click on its notification, asked for this page's setup.
    private func setupAsked() {
        guard model.hotspotSetupFor == device.id else { return }
        model.hotspotSetupFor = nil
        model.hotspot.dismissNotice()
        openSetup()
    }

    var body: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                if let notice = model.hotspotNotice, notice.phone == device.id {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(notice.title).font(.callout.weight(.semibold))
                            Text(notice.detail).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Set up") { openSetup(); model.hotspot.dismissNotice() }.buttonStyle(.tandemGlass)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    Divider().opacity(0.4).padding(.horizontal, 14)
                }
                SettingRow(
                    symbol: "personalhotspot",
                    title: "Use its hotspot when I have no connection",
                    subtitle: "Your phone turns its hotspot on and this Mac joins it"
                ) {
                    Toggle("", isOn: Binding(get: { auto && ready }, set: { on in
                        if on && !ready { openSetup() } else { auto = on }
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
                        if case .limitReached = model.hotspotStatus {
                            Button("1 GB extra") { model.hotspot.requestExtra(device.id) }
                                .buttonStyle(.tandemGlass)
                                .help("Once, for today: your phone shares 1 GB more. The limit is back tomorrow.")
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
                            if ready { model.hotspot.requestNow(device.id) } else { openSetup() }
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
        .animation(.tandem, value: model.hotspotNotice)
        .sheet(isPresented: $showSetup, onDismiss: { bluetooth = CBManager.authorization }) { HotspotSetupSheet(phone: device, prefill: prefill) }
        .onAppear { setupAsked() }
        .onChange(of: model.hotspotSetupFor) { _, _ in setupAsked() }
        // The permission only changes in System Settings, so it is read again when the person comes back from there.
        // A timer that asked the system every second kept the app and the permission service awake for nothing.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            bluetooth = CBManager.authorization
        }
    }
}

/// On which side of this Mac the device stands: chosen by hand, or found by sound. A second screen goes on that side, and a computer is put
/// there for the shared mouse. The title says what holds and the picture at the end of the row shows it: a press on the free place puts
/// the device there.
private struct SideRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var side: String?
    @LocalState private var finding = false

    var body: some View {
        // Nothing chosen yet is right, as a second screen and the shared mouse take it.
        let left = side == "left"
        SettingRow(
            symbol: "rectangle.split.2x1",
            title: "Where \(device.name) stands",
            subtitle: "Left or right of this Mac on your desk. The mouse crosses over to it on that side, and a second screen goes there."
        ) {
            HStack(spacing: 12) {
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
                SideChoice(device: device, left: left) { picked in
                    side = picked
                    DeviceSides.set(picked, for: device.id)
                }
            }
        }
        .onAppear { side = DeviceSides.side(of: device.id) }
    }
}

/// Left or right of this Mac, as the desk seen from the front, built like the button row of the player: three blocks with a small
/// gap, the outer ends round. This Mac is the block in the middle, filled and fixed, as play is in the player; the device is a choice
/// on either side of it. The side that holds is filled in the accent.
private struct SideChoice: View {
    let device: TandemDevice
    let left: Bool
    let pick: (String) -> Void

    /// This Mac as what it is: one with a battery is a laptop.
    private static let thisMac = PowerReader.battery() != nil ? "laptopcomputer" : "display"
    private static let height: CGFloat = 32
    private static let inner: CGFloat = 7

    var body: some View {
        HStack(spacing: 3) {
            option("left", on: left)
            // This Mac is not a choice: always filled and in the colour of the text, so it does not read as a third button.
            Image(systemName: Self.thisMac)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.primary)
                .frame(width: 46, height: Self.height)
                .background(Color.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: Self.inner, style: .continuous))
                .accessibilityLabel(Text("This Mac"))
            option("right", on: !left)
        }
        .animation(.tandemFade, value: left)
    }

    private func option(_ name: String, on: Bool) -> some View {
        SideOption(device: device, isLeft: name == "left", on: on, height: Self.height, inner: Self.inner) { pick(name) }
    }
}

/// One side of the side choice. The side that does not hold lights up under the pointer, as the other buttons of the app do.
private struct SideOption: View {
    let device: TandemDevice
    let isLeft: Bool
    let on: Bool
    let height: CGFloat
    let inner: CGFloat
    let pick: () -> Void
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let round = height / 2
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: isLeft ? round : inner, bottomLeadingRadius: isLeft ? round : inner,
            bottomTrailingRadius: isLeft ? inner : round, topTrailingRadius: isLeft ? inner : round,
            style: .continuous
        )
        let lit = hovering && hoverEnabled && !on
        let glyph = DeviceGlyph(platform: device.platform, online: true, size: 17, onSelection: on, plain: true, deviceID: device.id)
        let word = Text(isLeft ? "Left" : "Right")
            .font(.callout.weight(on ? .semibold : .medium))
            .foregroundStyle(on ? Color.white : (lit ? Color.primary : Color.secondary))
        Button { if !on { pick() } } label: {
            // The device on the side of the word that it stands on, so the row reads as the desk.
            HStack(spacing: 5) {
                if isLeft { glyph; word } else { word; glyph }
            }
            .padding(.horizontal, 12)
            .frame(height: height)
            .background(on ? AnyShapeStyle(Palette.indigo) : AnyShapeStyle(Color.primary.opacity(lit ? 0.12 : 0.06)), in: shape)
            .contentShape(shape)
        }
        .buttonStyle(.bouncy)
        .onHover { hovering = $0 }
        .animation(.hover(hovering), value: hovering)
        .accessibilityLabel(Text(isLeft ? "Put it left" : "Put it right"))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// A row of the settings card that leads to a part of Settings: the whole row is the button, with a chevron at its end.
private struct SettingsLinkRow: View {
    let symbol: String
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    let section: SettingsSection

    var body: some View {
        Button { AppScenes.showSettings(section) } label: {
            SettingRow(symbol: symbol, title: title, subtitle: subtitle) {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
    }
}
