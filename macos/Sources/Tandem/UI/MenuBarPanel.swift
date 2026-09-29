import AppKit
import SwiftUI
import TandemCore

struct MenuBarIcon: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        Image(systemName: symbol)
            .symbolEffect(.variableColor.iterative, isActive: model.isTransferring)
    }

    private var symbol: String {
        if model.isTransferring { return "arrow.up.arrow.down.circle.fill" }
        return model.onlineCount > 0 ? "link.circle.fill" : "link.circle"
    }
}

struct MenuBarPanel: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                ZStack {
                    Capsule().fill(Palette.indigo.gradient).frame(width: 9, height: 20).rotationEffect(.degrees(30))
                    Capsule().fill(Palette.rose.gradient).frame(width: 9, height: 20).rotationEffect(.degrees(30)).offset(x: -11)
                }
                .frame(width: 30)
                Text("Tandem").font(.headline)
                Spacer()
                Text(summary).font(.caption).foregroundStyle(.secondary)
            }

            if model.devices.isEmpty {
                VStack(spacing: 8) {
                    Text("No devices yet").font(.callout.weight(.medium))
                    Text("Pair your phone to send files and share the clipboard.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Pair a device") { openMain(pairing: true) }
                        .buttonStyle(.glassProminent)
                }
                .padding(.vertical, 12)
            } else {
                GlassEffectContainer(spacing: 8) {
                    VStack(spacing: 8) {
                        ForEach(model.devices, id: \.id) { device in
                            MenuDeviceRow(device: device)
                        }
                    }
                }
            }

            if let item = model.transfers.first(where: { $0.state == .active }) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(item.name).font(.caption.weight(.medium)).lineLimit(1).truncationMode(.middle)
                        Spacer()
                        Text(formatSpeed(item.speed)).font(.caption).foregroundStyle(.secondary)
                    }
                    ProgressCapsule(fraction: item.fraction).frame(height: 5)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            HStack {
                Button("Open Tandem") { openMain(pairing: false) }
                    .buttonStyle(.glassProminent)
                Spacer()
                Menu {
                    Button("Pair a device…") { openMain(pairing: true) }
                    SettingsLink { Text("Settings…") }
                    Divider()
                    Button("Quit Tandem") { NSApp.terminate(nil) }
                } label: {
                    Image(systemName: "ellipsis").frame(width: 24, height: 24)
                }
                .menuStyle(.button)
                .buttonStyle(.glass)
                .menuIndicator(.hidden)
            }
        }
        .padding(16)
        .frame(width: 340)
        .animation(.tandem, value: model.devices.map(\.id))
        .animation(.tandem, value: model.isTransferring)
    }

    private var summary: String {
        let online = model.onlineCount
        if model.devices.isEmpty { return "" }
        if online == 0 { return String(localized: "Nothing online") }
        return String(localized: "\(online) online")
    }

    private func openMain(pairing: Bool) {
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
        if pairing {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                NotificationCenter.default.post(name: .tandemShowPairing, object: nil)
            }
        }
    }
}

private struct MenuDeviceRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var targeted = false

    var body: some View {
        HStack(spacing: 10) {
            DeviceGlyph(platform: device.platform, online: device.online, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                HStack(spacing: 5) {
                    if let battery = device.status.battery {
                        Image(systemName: battery.charging ? "battery.100percent.bolt" : "battery.75percent").font(.caption2)
                        Text("\(battery.level)%").font(.caption.monospacedDigit())
                    } else {
                        Text(device.online ? "Online" : "Offline").font(.caption)
                    }
                }
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Button {
                pickFiles()
            } label: {
                Image(systemName: "paperplane.fill").frame(width: 24, height: 24)
            }
            .buttonStyle(.bouncy)
            .help("Send files")
            Button {
                model.sendClipboard(to: [device.id])
            } label: {
                Image(systemName: "doc.on.clipboard").frame(width: 24, height: 24)
            }
            .buttonStyle(.bouncy)
            .help("Send clipboard")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .glassEffect(
            targeted ? Glass.regular.tint(Palette.indigo.opacity(0.35)).interactive() : Glass.regular.interactive(),
            in: .rect(cornerRadius: 22)
        )
        .disabled(!device.online)
        .opacity(device.online ? 1 : 0.55)
        .scaleEffect(targeted ? 1.03 : 1)
        .animation(.tandemSpringy, value: targeted)
        .dropDestination(for: URL.self) { urls, _ in
            model.send(urls: urls, to: [device.id])
            return true
        } isTargeted: { targeted = $0 }
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { model.send(urls: panel.urls, to: [device.id]) }
    }
}
