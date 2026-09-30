import AppKit
import CoreBluetooth
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

struct DeviceDetail: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    @LocalState private var confirmRemoval = false
    @LocalState private var showIcons = false

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

    private var header: some View {
        Card(radius: 32, padding: 22, tint: device.online ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                Button { showIcons = true } label: {
                    DeviceGlyph(platform: device.platform, online: device.online, size: 80, deviceID: device.id)
                }
                .buttonStyle(.plain)
                .help("Change the icon")
                .popover(isPresented: $showIcons, arrowEdge: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(DeviceIconChoice.allCases, id: \.symbol) { choice in
                            Button {
                                model.setIcon(choice.symbol, for: device.id)
                                showIcons = false
                            } label: {
                                Label(choice.label, systemImage: choice.symbol)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .hoverHighlight(radius: 8)
                            }
                            .buttonStyle(.plain)
                        }
                        Divider().padding(.vertical, 4)
                        Button("Use the default icon") {
                            model.setIcon(nil, for: device.id)
                            showIcons = false
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                    }
                    .padding(8)
                    .frame(width: 210)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text(device.name)
                        .font(.title.weight(.bold))
                        .lineLimit(1)
                        .contentTransition(.opacity)
                        
                    HStack(spacing: 8) {
                        Chip(
                            symbol: device.online ? "checkmark.circle.fill" : (device.ble ? "dot.radiowaves.left.and.right" : "circle.dashed"),
                            text: device.online ? "Connected" : (device.ble ? "Over Bluetooth" : "Not connected"),
                            tint: device.online ? .green : (device.ble ? Palette.indigo : .secondary),
                            strong: device.online || device.ble
                        )
                        if let route = device.route, device.online {
                            Chip(symbol: route.symbol, text: route.label)
                        }
                        if let rtt = device.rttMs, device.online {
                            Chip(symbol: "speedometer", text: "\(rtt) ms")
                        }
                    }
                    if device.online {
                        HStack(spacing: 8) {
                            if let network = device.status.network {
                                Chip(symbol: networkSymbol(network), text: networkLabel(network))
                            }
                            if device.status.dnd == true {
                                Chip(symbol: "moon.fill", text: "Do not disturb")
                            }
                            if device.status.hotspot == true {
                                Chip(symbol: "personalhotspot", text: "Hotspot on", tint: Palette.indigo)
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

    private func networkLabel(_ network: TandemNetwork) -> LocalizedStringKey {
        switch network.kind {
        case .wifi: network.ssid.map { LocalizedStringKey($0) } ?? "Wi-Fi"
        case .cellular: network.roaming ? "Mobile data (roaming)" : "Mobile data"
        case .ethernet: "Ethernet"
        case .none: "No network"
        case .other: "Network"
        }
    }

    // MARK: Content

    private var content: some View {
        VStack(spacing: 18) {
            actions
            DropZone(device: device)
            if device.platform == .android { HotspotCard(device: device) }
            RemoteControlCard()
            recentTransfers
            settings
        }
    }

    // MARK: Actions

    private var actions: some View {
        GlassEffectContainer(spacing: 14) {
            HStack(spacing: 12) {
                GlassActionButton(title: "Send files", symbol: "paperplane.fill", prominent: true) { pickFiles() }
                    .disabled(!device.online)
                    .opacity(device.online ? 1 : 0.5)
                // Small enough for Bluetooth, so it works with no network as long as a link is up.
                GlassActionButton(title: "Send clipboard", symbol: "doc.on.clipboard") {
                    model.sendClipboard(to: [device.id])
                }
                .disabled(!(device.online || device.ble))
                .opacity(device.online || device.ble ? 1 : 0.5)
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
                    .opacity(device.online ? 1 : 0.5)
                }
                Spacer(minLength: 0)
            }
        }
        .animation(.tandemFade, value: device.online)
        .animation(.tandemFade, value: device.ble)
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
        let items = model.transfers.filter { $0.peer == device.id }.prefix(6)
        if !items.isEmpty {
            Card(radius: Metrics.card, padding: Metrics.cardInset) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Recent")
                        .font(.headline)
                        .padding(.horizontal, 10)
                        .padding(.top, 6)
                        .padding(.bottom, 4)
                    ForEach(Array(items)) { item in
                        TransferEntry(item: item, peerName: device.name)
                            .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
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
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "bell.badge", title: "Show its notifications", subtitle: "Phone notifications appear here, and you can reply") {
                    Toggle("", isOn: Binding(
                        get: { device.notificationsEnabled },
                        set: { model.setSettings(device, notifications: $0) }
                    ))
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "tray.and.arrow.down", title: "Accept files automatically", subtitle: "Turn off to be asked before something arrives") {
                    Toggle("", isOn: Binding(
                        get: { device.autoAccept },
                        set: { model.setSettings(device, autoAccept: $0) }
                    ))
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "trash", title: "Remove this device", subtitle: device.vouchedByRemoved ? "It was added by a device that has since been removed" : "It leaves the circle everywhere") {
                    Button("Remove") { confirmRemoval = true }
                        .buttonStyle(.glass)
                        .tint(Palette.urgent)
                }
            }
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
            trailing.labelsHidden()
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
                .foregroundStyle(targeted || hovering ? Palette.indigo : Color.secondary)
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
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .strokeBorder(
                    targeted ? Palette.indigo : (hovering ? Palette.indigo.opacity(0.55) : Color.primary.opacity(0.16)),
                    style: StrokeStyle(lineWidth: targeted ? 2.5 : 1.5, dash: [8, 7])
                )
                .background(
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
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

// MARK: Remote control

struct RemoteControlCard: View {
    @LocalState private var trusted = AXIsProcessTrusted()

    var body: some View {
        if !trusted {
            Card(radius: Metrics.card, padding: 6) {
                SettingRow(
                    symbol: "cursorarrow.motionlines",
                    title: "Use your phone as a trackpad",
                    subtitle: "Allow Tandem to control this Mac in System Settings, under Accessibility"
                ) {
                    Button("Allow") {
                        let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
                        _ = AXIsProcessTrustedWithOptions(options)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(Palette.indigo)
                }
            }
            .task {
                // Poll while the card is on screen, so it disappears once allowed.
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(2))
                    let now = AXIsProcessTrusted()
                    if now != trusted { withAnimation(.tandem) { trusted = now } }
                }
            }
        }
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

    /// Grey until Bluetooth is allowed and the phone's network is filled in, so the switch
    /// never looks on while nothing can happen.
    private var ready: Bool {
        bluetooth == .allowedAlways && !ssid.isEmpty && (HotspotCredentials.password()?.isEmpty == false)
    }

    var body: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(
                    symbol: "personalhotspot",
                    title: "Use its hotspot when I have no connection",
                    subtitle: ready ? "Your phone turns its hotspot on and this Mac joins it" : "Finish the hotspot setup in Settings first"
                ) {
                    Toggle("", isOn: Binding(get: { auto && ready }, set: { auto = $0 }))
                        .disabled(!ready)
                }
                if !ready {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    HStack {
                        Text("Bluetooth and the phone's network name and password are needed.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Set up") { showSetup = true }
                            .buttonStyle(.glass)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
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
                            Button("Stop") { model.hotspot.stop(device.id) }.buttonStyle(.glass)
                        }
                        // A failure leaves its message up, and the way to try again with it.
                        if case .failed = model.hotspotStatus {
                            Button("Try again") { model.hotspot.requestNow(device.id) }.buttonStyle(.glass)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                } else if device.online {
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    HStack {
                        Text("Need it right now?").font(.callout).foregroundStyle(.secondary)
                        Spacer()
                        Button("Turn on hotspot") { model.hotspot.requestNow(device.id) }
                            .buttonStyle(.glass)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
            }
        }
        .animation(.tandem, value: model.hotspotStatus)
        .animation(.tandem, value: ready)
        .sheet(isPresented: $showSetup) { HotspotSetupSheet() }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
            bluetooth = CBManager.authorization
        }
    }
}
