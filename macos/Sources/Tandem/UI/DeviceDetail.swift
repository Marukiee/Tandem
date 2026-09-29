import AppKit
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

struct DeviceDetail: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    @LocalState private var confirmRemoval = false
    /// 0 while the big name in the header card is on screen, 1 once it has scrolled
    /// into the toolbar's place.
    @LocalState private var handoff: CGFloat = 0
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
                        .transition(.page)
                }
            }
            .padding(26)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .scrollPosition($position)
        .trackScrollHandoff($handoff)
        .animation(.tandem, value: device.id)
        .animation(.tandem, value: device.online)
        .toolbar {
            ToolbarItem(placement: .navigation) {
                toolbarTitle
            }
            .sharedBackgroundVisibility(.hidden)

            ToolbarItemGroup(placement: .primaryAction) {
                Button { pickFiles() } label: {
                    Label("Send files", systemImage: "paperplane")
                }
                .help("Send files")
                .disabled(!device.online)
                Button { model.sendClipboard(to: [device.id]) } label: {
                    Label("Send clipboard", systemImage: "doc.on.clipboard")
                }
                .help("Send clipboard")
                .disabled(!device.online)
            }
        }
        .onChange(of: device.id) {
            confirmRemoval = false
            handoff = 0
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

    // MARK: Toolbar

    /// The device you are on, where the window title used to be. It arrives as the
    /// name in the header card leaves.
    private var toolbarTitle: some View {
        HandoffTitle(progress: handoff) {
            HStack(spacing: 8) {
                Image(systemName: device.platform.symbol)
                    .foregroundStyle(device.online ? Palette.indigo : Color.secondary)
                    .contentTransition(.symbolEffect(.replace))
                Text(device.name)
                    .font(.headline)
                    .lineLimit(1)
                    .contentTransition(.opacity)
                Circle()
                    .fill(device.online ? Color.green : Color.secondary.opacity(0.5))
                    .frame(width: 7, height: 7)
            }
            .padding(.horizontal, 6)
        }
        .animation(.tandem, value: device.id)
        .animation(.tandemFade, value: device.online)
    }

    // MARK: Header

    private var header: some View {
        Card(radius: 32, padding: 22, tint: device.online ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 72)

                VStack(alignment: .leading, spacing: 8) {
                    Text(device.name)
                        .font(.title.weight(.bold))
                        .lineLimit(1)
                        .contentTransition(.opacity)
                        .handoffSource(handoff)
                    HStack(spacing: 8) {
                        Chip(
                            symbol: device.online ? "checkmark.circle.fill" : "circle.dashed",
                            text: device.online ? "Connected" : "Not connected",
                            tint: device.online ? .green : .secondary,
                            strong: device.online
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
        .hoverLift(scale: 1.004, lift: 1)
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
                GlassActionButton(title: "Send clipboard", symbol: "doc.on.clipboard") {
                    model.sendClipboard(to: [device.id])
                }
                if device.platform == .android {
                    GlassActionButton(title: "Find phone", symbol: "bell.and.waves.left.and.right") {
                        model.ring(device.id, on: true)
                        model.showToast(String(localized: "Your phone is ringing"))
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .disabled(!device.online)
        .opacity(device.online ? 1 : 0.5)
        .animation(.tandemFade, value: device.online)
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
            .hoverLift(scale: 1.004, lift: 1)
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
        .hoverLift(scale: 1.004, lift: 1)
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
        .hoverLift(scale: 1.008, lift: 1.5, enabled: !targeted)
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
            .hoverLift(scale: 1.004, lift: 1)
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

    var body: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(
                    symbol: "personalhotspot",
                    title: "Use its hotspot when I have no connection",
                    subtitle: "Your phone turns its hotspot on and this Mac joins it"
                ) {
                    Toggle("", isOn: $auto)
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
        .hoverLift(scale: 1.004, lift: 1)
        .animation(.tandem, value: model.hotspotStatus)
    }
}
