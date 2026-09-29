import AppKit
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

struct DeviceDetail: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    @LocalState private var confirmRemoval = false
    @Namespace private var glassSpace

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                actions
                DropZone(device: device)
                if device.platform == .android { HotspotCard(device: device) }
                RemoteControlCard()
                recentTransfers
                settings
            }
            .padding(26)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .animation(.tandem, value: device.online)
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
        GlassCard(radius: 32, padding: 22, tint: device.online ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 72)

                VStack(alignment: .leading, spacing: 8) {
                    Text(device.name).font(.title.weight(.bold)).lineLimit(1)
                    HStack(spacing: 8) {
                        Chip(symbol: device.online ? "circle.fill" : "circle", text: device.online ? "Online" : "Offline", tint: device.online ? .green : .secondary)
                        if let route = device.route {
                            Chip(symbol: route.symbol, text: route.label)
                        }
                        if let rtt = device.rttMs, device.online {
                            Chip(symbol: "speedometer", text: "\(rtt) ms")
                        }
                    }
                    HStack(spacing: 8) {
                        if let network = device.status.network, device.online {
                            Chip(symbol: networkSymbol(network), text: networkLabel(network))
                        }
                        if device.status.dnd == true {
                            Chip(symbol: "moon.fill", text: "Do not disturb")
                        }
                        if device.status.hotspot == true {
                            Chip(symbol: "personalhotspot", text: "Hotspot on", tint: Palette.indigo)
                        }
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

    // MARK: Actions

    private var actions: some View {
        GlassEffectContainer(spacing: 14) {
            HStack(spacing: 12) {
                GlassActionButton(title: "Send files", symbol: "paperplane.fill", prominent: true) { pickFiles() }
                    .glassEffectID("send", in: glassSpace)
                GlassActionButton(title: "Send clipboard", symbol: "doc.on.clipboard") {
                    model.sendClipboard(to: [device.id])
                }
                .glassEffectID("clip", in: glassSpace)
                if device.platform == .android {
                    GlassActionButton(title: "Find phone", symbol: "bell.and.waves.left.and.right") {
                        model.ring(device.id, on: true)
                        model.showToast(String(localized: "Your phone is ringing"))
                    }
                    .glassEffectID("ring", in: glassSpace)
                }
                Spacer(minLength: 0)
            }
        }
        .disabled(!device.online)
        .opacity(device.online ? 1 : 0.5)
        .animation(.tandemFade, value: device.online)
    }

    private func pickFiles() {
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
            GlassCard(radius: 26, padding: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Recent").font(.headline).padding(.bottom, 4)
                    ForEach(Array(items)) { item in
                        TransferRow(item: item, peerName: device.name)
                            .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
    }

    // MARK: Settings

    private var settings: some View {
        GlassCard(radius: 26, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(symbol: "doc.on.clipboard", title: "Sync clipboard", subtitle: "Text and links you copy show up on both devices") {
                    Toggle("", isOn: Binding(
                        get: { device.clipboardEnabled },
                        set: { model.setSettings(device, clipboard: $0) }
                    ))
                }
                Divider().opacity(0.4)
                SettingRow(symbol: "bell.badge", title: "Show its notifications", subtitle: "Phone notifications appear here, and you can reply") {
                    Toggle("", isOn: Binding(
                        get: { device.notificationsEnabled },
                        set: { model.setSettings(device, notifications: $0) }
                    ))
                }
                Divider().opacity(0.4)
                SettingRow(symbol: "tray.and.arrow.down", title: "Accept files automatically", subtitle: "Turn off to be asked before something arrives") {
                    Toggle("", isOn: Binding(
                        get: { device.autoAccept },
                        set: { model.setSettings(device, autoAccept: $0) }
                    ))
                }
                Divider().opacity(0.4)
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

    var body: some View {
        Label {
            Text(text).font(.caption.weight(.medium))
        } icon: {
            Image(systemName: symbol).font(.caption2)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(tint.opacity(0.12), in: .capsule)
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
    }
}

// MARK: Drop zone

struct DropZone: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var targeted = false

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: targeted ? "arrow.down.circle.fill" : "square.and.arrow.down")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(targeted ? Palette.indigo : Color.secondary)
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
                    targeted ? Palette.indigo : Color.primary.opacity(0.16),
                    style: StrokeStyle(lineWidth: targeted ? 2.5 : 1.5, dash: [8, 7])
                )
                .background(
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .fill(Palette.indigo.opacity(targeted ? 0.10 : 0))
                )
        }
        .scaleEffect(targeted ? 1.015 : 1)
        .animation(.tandemSpringy, value: targeted)
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
            GlassCard(radius: 26, padding: 6) {
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

    var body: some View {
        GlassCard(radius: 26, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(
                    symbol: "personalhotspot",
                    title: "Use its hotspot when I have no connection",
                    subtitle: "Your phone turns its hotspot on and this Mac joins it"
                ) {
                    Toggle("", isOn: $auto)
                }
                if model.hotspotStatus != .idle {
                    Divider().opacity(0.4)
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
                    Divider().opacity(0.4)
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
    }
}
