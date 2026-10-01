import AppKit
import SwiftUI
import TandemCore

/// The panel under the menu bar icon. The panel itself is the glass; what sits in it
/// is content (the device rows, on a plain hover highlight) and controls (standard
/// glass buttons), grouped in containers so neighbours blend instead of stacking.
struct MenuBarPanel: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(spacing: 10) {
            header

            if model.devices.isEmpty {
                empty
            } else {
                // No glass container here: the buttons in a row sit close together, and in a
                // container glass that close blends into one blob and drags the icons off centre
                // as soon as one of them swells under the pointer.
                VStack(spacing: 2) {
                    ForEach(model.devices, id: \.id) { device in
                        MenuDeviceRow(device: device)
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
                .padding(.horizontal, 10)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            MenuNowPlaying()

            UpdateBanner(compact: true)
            footer
        }
        .padding(14)
        .frame(width: 344)
        .animation(.tandem, value: model.devices.map(\.id))
        .animation(.tandem, value: model.isTransferring)
        .animation(.tandem, value: model.remoteMedia.mapValues(\.players))
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            PillMark(size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(AppIdentity.displayName).font(.headline)
                if !model.devices.isEmpty {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(model.onlineCount > 0 ? Color.green : Color.secondary.opacity(0.45))
                            .frame(width: 6, height: 6)
                        Text(model.connectionSummary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 6)
        .animation(.tandem, value: model.onlineCount)
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Text("No devices yet").font(.callout.weight(.medium))
            Text("Pair your phone to send files and share the clipboard.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Pair a device") { openMain(pairing: true) }
                .buttonStyle(.glassProminent)
                .tint(Palette.indigo)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
    }

    // MARK: Footer

    /// Settings and the extras on the left, the main action on the right, all the
    /// same height so the row reads as one line.
    private var footer: some View {
        HStack(spacing: 8) {
            Menu {
                Button("Pair a device…") { openMain(pairing: true) }
                Divider()
                Button("Quit Tandem") { NSApp.terminate(nil) }
                    .keyboardShortcut("q")
            } label: {
                Image(systemName: "ellipsis.circle")
                    .frame(width: 18, height: 18)
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .fixedSize()
            .hoverGrey()
            .hoverSwell()
            .help("More")

            Spacer(minLength: 8)

            Button("Open Tandem") { openMain(pairing: false) }
                .buttonStyle(.glassProminent)
                .tint(Palette.indigo)
                .hoverSwell(1.04)
        }
        .controlSize(.large)
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
        Hoverable { hovering in
            HStack(spacing: 10) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 36, ring: true, deviceID: device.id)
                    .scaleEffect(hovering ? 1.06 : 1)
                    .animation(.tandemSpringy, value: hovering)
                VStack(alignment: .leading, spacing: 1) {
                    Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                    HStack(spacing: 5) {
                        Text(device.connectionText)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(device.connectionColor)
                        if let battery = device.status.battery {
                            Image(systemName: batterySymbol(battery)).font(.caption2).foregroundStyle(.secondary)
                            Text("\(battery.level)%").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 4)
                HStack(spacing: 6) {
                    Button { pickFiles() } label: {
                        Image(systemName: "paperplane.fill").frame(width: 14, height: 14)
                    }
                    .hoverGrey()
                    .hoverSwell(1.12)
                    .disabled(!device.online)
                    .help("Send files")
                    Button { model.sendClipboard(to: [device.id]) } label: {
                        Image(systemName: "doc.on.clipboard").frame(width: 14, height: 14)
                    }
                    .hoverGrey()
                    .hoverSwell(1.12)
                    .disabled(!(device.online || device.ble))
                    .help("Send clipboard")
                    if device.platform == .android {
                        let on = model.speaker.device == device.id
                        Button { model.toggleSpeaker(for: device.id) } label: {
                            Image(systemName: on ? "speaker.wave.3.fill" : "speaker.wave.2").frame(width: 14, height: 14)
                                .foregroundStyle(on ? Palette.indigo : Color.primary)
                        }
                        .hoverGrey()
                        .hoverSwell(1.12)
                        .disabled(!device.online)
                        .help(on ? "Stop using this phone as a speaker" : "Use this phone as a speaker")
                    }
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .controlSize(.regular)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .hoverHighlight(radius: 18, tint: targeted ? Palette.indigo : .primary, selected: targeted)
        .opacity(device.online || device.ble ? 1 : 0.6)
        .scaleEffect(targeted ? 1.02 : 1)
        .animation(.tandemSpringy, value: targeted)
        .animation(.tandem, value: device.online)
        .dropDestination(for: URL.self) { urls, _ in
            guard device.online else {
                model.showToast(String(localized: "That device is not connected right now"))
                return false
            }
            model.send(urls: urls, to: [device.id])
            return true
        } isTargeted: { targeted = $0 }
    }

    private func batterySymbol(_ battery: TandemBattery) -> String {
        if battery.charging { return "battery.100percent.bolt" }
        switch battery.level {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
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
