import SwiftUI
import TandemCore

enum SidebarSelection: Hashable {
    case device(String)
    case shared
}

/// Two clearly separate groups: the devices, with how many of them are reachable, and
/// what has been shared with them.
struct Sidebar: View {
    @Environment(EngineModel.self) private var model
    @Binding var selection: SidebarSelection?
    @Binding var showPairing: Bool

    var body: some View {
        List(selection: $selection) {
            Section {
                ForEach(model.devices, id: \.id) { device in
                    DeviceRow(device: device)
                        .tag(SidebarSelection.device(device.id))
                }
            } header: {
                DevicesHeader()
            }

            Section {
                SharedRow()
                    .tag(SidebarSelection.shared)
            } header: {
                Text("Shared")
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .top, spacing: 0) { SidebarHeader() }
        .safeAreaInset(edge: .bottom, spacing: 0) { PairButton(showPairing: $showPairing) }
    }
}

private struct SidebarHeader: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        HStack(spacing: 10) {
            PillMark(size: 32)
            VStack(alignment: .leading, spacing: 0) {
                Text(AppIdentity.displayName).font(.title3.weight(.bold))
                Text(model.myName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .contentTransition(.opacity)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
        .padding(.bottom, 6)
        .animation(.tandemFade, value: model.myName)
    }
}

private struct DevicesHeader: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        HStack(spacing: 6) {
            Text("Devices")
            Spacer(minLength: 8)
            if !model.devices.isEmpty {
                Circle()
                    .fill(model.onlineCount > 0 ? Color.green : Color.secondary.opacity(0.4))
                    .frame(width: 6, height: 6)
                Text(model.connectionSummary)
                    .font(.caption)
                    .textCase(nil)
                    .contentTransition(.numericText())
            }
        }
        .animation(.tandem, value: model.onlineCount)
        .animation(.tandem, value: model.devices.count)
    }
}

private struct DeviceRow: View {
    let device: TandemDevice

    var body: some View {
        Hoverable { hovering in
            HStack(spacing: 11) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 38)
                    .scaleEffect(hovering ? 1.07 : 1)
                    .animation(.tandemSpringy, value: hovering)
                VStack(alignment: .leading, spacing: 2) {
                    Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                    HStack(spacing: 4) {
                        Text(device.connectionText)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(device.connectionColor)
                        if device.online, let rtt = device.rttMs {
                            Text("· \(rtt) ms").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
                if let battery = device.status.battery {
                    HStack(spacing: 2) {
                        if battery.charging { Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.green) }
                        Text("\(battery.level)%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText(value: Double(battery.level)))
                    }
                }
            }
        }
        .padding(.vertical, 3)
        // Not reachable: the whole row steps back, so the connected ones stand out.
        .opacity(device.online ? 1 : 0.6)
        .animation(.tandem, value: device.online)
    }
}

private struct SharedRow: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let active = model.transfers.filter { $0.state == .active }.count
        Hoverable { hovering in
            Label {
                Text("Files")
            } icon: {
                Image(systemName: "arrow.up.arrow.down.circle.fill")
                    .foregroundStyle(Palette.indigo)
                    .symbolEffect(.pulse, isActive: active > 0)
                    .scaleEffect(hovering ? 1.12 : 1)
                    .animation(.tandemSpringy, value: hovering)
            }
        }
        .badge(active)
    }
}

/// A plain row at the bottom of the sidebar, like Notes has for a new folder. It is
/// not glass: the sidebar already is the glass layer.
private struct PairButton: View {
    @Binding var showPairing: Bool

    var body: some View {
        Button {
            showPairing = true
        } label: {
            Label {
                Text("Pair a device").font(.callout.weight(.medium))
            } icon: {
                Image(systemName: "plus.circle.fill").foregroundStyle(Palette.indigo)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .hoverHighlight(radius: 12)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
    }
}
