import SwiftUI
import TandemCore

enum SidebarSelection: Hashable {
    case device(String)
    /// The files of that device.
    case files(String)
    /// The text messages of that phone.
    case messages(String)
    case shared
    case notifications
    case clipboard
}

/// Two clearly separate groups: the devices, with how many of them are reachable, and
/// what has been shared with them.
///
/// Drawn by hand instead of as a system List. A List paints the row under the pointer in
/// solid blue the moment the mouse goes down and keeps it that way, which reads as if text
/// were being selected. Here a press only dips the row a little, the chosen row gets a
/// soft tint of the accent colour that slides from row to row, and the pointer over a row
/// is a faint highlight that fades in.
struct Sidebar: View {
    @Environment(EngineModel.self) private var model
    @Binding var selection: SidebarSelection?
    @Binding var showPairing: Bool
    @Namespace private var chosen

    private var order: [SidebarSelection] {
        model.devices.flatMap { device in
            Sidebar.offersMessages(device) ? [SidebarSelection.device(device.id), .messages(device.id)] : [.device(device.id)]
        } + [.shared, .notifications, .clipboard]
    }

    /// A phone has its messages, and its calls with them, as a place of its own under it. What a device can do is only known while
    /// it is connected, so a phone that is away keeps the place: the page itself says that it cannot be reached.
    static func offersMessages(_ device: TandemDevice) -> Bool {
        device.platform == .android && (device.caps.isEmpty || device.caps.contains("sms"))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                DevicesHeader()
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .padding(.bottom, 4)
                ForEach(model.devices, id: \.id) { device in
                    SidebarButton(selected: selection == .device(device.id) || selection == .files(device.id), chosen: chosen) {
                        selection = .device(device.id)
                    } content: {
                        DeviceRow(device: device)
                    }
                    if Sidebar.offersMessages(device) {
                        SidebarButton(selected: selection == .messages(device.id), chosen: chosen) {
                            selection = .messages(device.id)
                        } content: {
                            MessagesRow(device: device)
                        }
                        // Stepped in to the column of the phone's name, its shape too, so it reads as part of that phone.
                        .padding(.leading, DeviceRow.nameColumn)
                        .transition(.opacity)
                    }
                }

                Text("Shared")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.top, 16)
                    .padding(.bottom, 4)
                SidebarButton(selected: selection == .shared, chosen: chosen) {
                    selection = .shared
                } content: {
                    SharedRow()
                }
                SidebarButton(selected: selection == .notifications, chosen: chosen) {
                    selection = .notifications
                } content: {
                    NotificationsRow()
                }
                SidebarButton(selected: selection == .clipboard, chosen: chosen) {
                    selection = .clipboard
                } content: {
                    ClipboardRow()
                }
            }
            .padding(.horizontal, 10)
            .animation(.tandem, value: model.devices.map(\.id))
            .animation(.tandem, value: selection)
        }
        .scrollContentBackground(.hidden)
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.upArrow) { move(-1) }
        .onKeyPress(.downArrow) { move(1) }
        // At the foot of the sidebar: the update sits just above the way to pair, out of the way of the pages.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                UpdateBanner(compact: true, fromBottom: true)
                    .padding(.horizontal, 10)
                PairButton(showPairing: $showPairing)
            }
        }
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

/// One row of the sidebar: a tinted shape while chosen, which moves to the next chosen row, and a faint one under the pointer.
private struct SidebarButton<Content: View>: View {
    let selected: Bool
    let chosen: Namespace.ID
    let action: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        Button(action: action) {
            content
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    ZStack {
                        shape.fill(Color.primary.opacity(hovering && hoverEnabled && !selected ? hoverOpacity : 0))
                        if selected {
                            shape.fill(Palette.indigo.opacity(0.16)).matchedGeometryEffect(id: "selection", in: chosen)
                        }
                    }
                }
                .contentShape(shape)
        }
        .buttonStyle(SidebarPressStyle())
        .onHover { hovering = $0 }
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
        .animation(.tandem, value: model.onlineCount)
        .animation(.tandem, value: model.devices.count)
    }
}

private struct DeviceRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    /// Where the name starts, from the edge of the row's content: the glyph and the space after it.
    static let nameColumn: CGFloat = 38 + 11

    var body: some View {
        let reach = model.reach(of: device)
        Hoverable { hovering in
            HStack(spacing: 11) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 38, deviceID: device.id)
                VStack(alignment: .leading, spacing: 2) {
                    Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                    HStack(spacing: 4) {
                        Text(reach.text)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(reach.color)
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
        .opacity(reach.reachable ? 1 : 0.6)
        .animation(.tandem, value: device.online)
        .animation(.tandem, value: reach.reachable)
    }
}

/// A row of the second group, built like a device row so the two groups carry the same weight:
/// a disc with a symbol, a title and a line under it.
private struct SideRow: View {
    let symbol: String
    let title: LocalizedStringKey
    let subtitle: String
    var badge = 0
    var pulse = false

    var body: some View {
        HStack(spacing: 11) {
            PulsingSymbol(name: symbol, pointSize: 17, weight: .semibold, color: NSColor(Palette.indigo), active: pulse)
                .frame(width: 38, height: 38)
                .background(Palette.indigo.opacity(0.14), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.callout.weight(.semibold)).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if badge > 0 {
                Text("\(badge)")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Palette.indigo.opacity(0.18), in: Capsule())
                    .contentTransition(.numericText(value: Double(badge)))
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.vertical, 3)
        .animation(.tandem, value: badge)
    }
}

/// The messages of a phone, just under it and stepped in under its name (see `Sidebar`), so it reads as part of that phone: a small
/// disc and the word.
private struct MessagesRow: View {
    let device: TandemDevice

    var body: some View {
        let unread = MessagesStore.shared.phone(device.id).unreadCount
        HStack(spacing: 8) {
            Image(systemName: "message.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Palette.indigo)
                .frame(width: 24, height: 24)
                .background(Palette.indigo.opacity(0.14), in: Circle())
            Text("Messages").font(.callout.weight(.medium)).lineLimit(1)
            Spacer(minLength: 0)
            if unread > 0 {
                Text("\(unread)")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Palette.indigo.opacity(0.18), in: Capsule())
                    .contentTransition(.numericText(value: Double(unread)))
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.vertical, 1)
        .opacity(device.online ? 1 : 0.6)
        .animation(.tandem, value: unread)
    }
}

private struct SharedRow: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let active = model.transfers.filter { $0.state == .active }.count
        let total = model.transfers.count
        SideRow(
            symbol: "arrow.up.arrow.down.circle.fill",
            title: "Files",
            subtitle: total == 0 ? String(localized: "Nothing yet") : String(localized: "\(total) shared"),
            badge: active,
            pulse: active > 0
        )
    }
}

private struct NotificationsRow: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let count = model.mirrored.count
        SideRow(
            symbol: "bell.fill",
            title: "Notifications",
            subtitle: count == 0 ? String(localized: "Nothing yet") : String(localized: "\(count) from your phones")
        )
    }
}

private struct ClipboardRow: View {
    var body: some View {
        let history = ClipboardHistory.shared
        let count = history.items.count
        SideRow(
            symbol: "doc.on.clipboard.fill",
            title: "Clipboard",
            subtitle: !history.enabled ? String(localized: "Off") : (count == 0 ? String(localized: "Nothing yet") : String(localized: "\(count) saved"))
        )
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
            .hoverHighlight(radius: 14)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
    }
}
