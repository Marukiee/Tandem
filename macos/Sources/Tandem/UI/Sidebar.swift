import SwiftUI
import TandemCore

enum SidebarSelection: Hashable {
    case device(String)
    case shared
}

/// Two clearly separate groups: the devices, with how many of them are reachable, and
/// what has been shared with them.
///
/// Drawn by hand instead of as a system List. A List paints the row under the pointer in
/// solid blue the moment the mouse goes down and keeps it that way, which reads as if text
/// were being selected. Here a press only dips the row a little, the chosen row gets a
/// soft tint of the accent colour, and the pointer over a row is a faint highlight.
struct Sidebar: View {
    @Environment(EngineModel.self) private var model
    @Binding var selection: SidebarSelection?
    @Binding var showPairing: Bool

    private var order: [SidebarSelection] {
        model.devices.map { .device($0.id) } + [.shared]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                DevicesHeader()
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .padding(.bottom, 4)
                ForEach(model.devices, id: \.id) { device in
                    SidebarButton(selected: selection == .device(device.id)) {
                        selection = .device(device.id)
                    } content: {
                        DeviceRow(device: device)
                    }
                }

                Text("Shared")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.top, 16)
                    .padding(.bottom, 4)
                SidebarButton(selected: selection == .shared) {
                    selection = .shared
                } content: {
                    SharedRow()
                }
            }
            .padding(.horizontal, 10)
            .animation(.tandem, value: model.devices.map(\.id))
        }
        .scrollContentBackground(.hidden)
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.upArrow) { move(-1) }
        .onKeyPress(.downArrow) { move(1) }
        .safeAreaInset(edge: .bottom, spacing: 0) { PairButton(showPairing: $showPairing) }
    }

    private func move(_ step: Int) -> KeyPress.Result {
        let items = order
        guard let current = selection, let index = items.firstIndex(of: current) else {
            selection = items.first
            return .handled
        }
        selection = items[min(items.count - 1, max(0, index + step))]
        return .handled
    }
}

/// One row of the sidebar: a tinted capsule while chosen, a faint one under the pointer.
private struct SidebarButton<Content: View>: View {
    let selected: Bool
    let action: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) {
            content
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(selected ? Palette.indigo.opacity(0.16) : Color.primary.opacity(hovering && hoverEnabled ? 0.07 : 0))
                }
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(SidebarPressStyle())
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: selected)
        .animation(.tandemFade, value: hovering)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A press only dips the row, the way a button does. It never turns it blue.
private struct SidebarPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
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
        // The section header runs to the edge of the column; keep the count off it.
        .padding(.trailing, 12)
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
            HStack(spacing: 8) {
                Label {
                    Text("Files")
                } icon: {
                    Image(systemName: "arrow.up.arrow.down.circle.fill")
                        .foregroundStyle(Palette.indigo)
                        .symbolEffect(.pulse, isActive: active > 0)
                        .scaleEffect(hovering ? 1.12 : 1)
                        .animation(.tandemSpringy, value: hovering)
                }
                Spacer(minLength: 0)
                if active > 0 {
                    Text("\(active)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(Palette.indigo.opacity(0.18), in: Capsule())
                        .contentTransition(.numericText(value: Double(active)))
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .animation(.tandem, value: active)
        }
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
