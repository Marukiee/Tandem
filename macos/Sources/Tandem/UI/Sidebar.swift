import SwiftUI
import TandemCore

enum SidebarSelection: Hashable {
    case device(String)
    /// The files of that device.
    case files(String)
    /// The text messages of that phone.
    case messages(String)
    /// The watch of that phone.
    case watch(String)
    /// Health, opened from the page of the watch of that phone.
    case health(String)
    case shared
    case notifications
    case clipboard
}

/// Two clearly separate groups: the devices, with how many of them are reachable, and
/// what has been shared with them.
///
/// Drawn by hand instead of as a system List. A List paints the row under the pointer in
/// solid blue the moment the mouse goes down and keeps it that way, which reads as if text
/// were being selected. Here a press only dips the row a little, the chosen row has a
/// soft tint of the accent colour, one fill that glides from row to row, and the pointer over
/// another row is a fainter highlight that fades in.
///
/// One sidebar with two widths. In a narrow window it folds by itself to a column of icons with a small name under each, and it
/// opens again when there is room. Every icon stays where it is: only the words beside it fade and the width springs, so the
/// change reads as one thing folding and not as one sidebar swapped for another. A row is laid out for the wide sidebar at all
/// times and what does not fit is cut off by the edge, so no line of text is wrapped or squeezed on the way.
struct Sidebar: View {
    static let wideWidth: CGFloat = 264
    /// The glyph of a row is 38 wide and starts 22 from the edge: this width puts it in the middle. The three buttons of the window
    /// are in the middle of it too: they run from 19 to 79 points from the edge of the window, and the sidebar from 8 to 90.
    static let railWidth: CGFloat = 82
    /// From the edge of the sidebar to a row; the glyph starts ten further in.
    fileprivate static let inset: CGFloat = 12
    /// How far the sidebar floats from the edge of the window.
    static let margin: CGFloat = 8
    static let glyph: CGFloat = 38
    /// The corners of a row: of the fill of the chosen one and of the highlight under the pointer.
    fileprivate static let rowRadius: CGFloat = 14
    /// The highlight under the pointer. Fainter than elsewhere in the app: here it lies next to the fill of the chosen row, and at
    /// the usual strength the two read as two chosen rows.
    fileprivate static let hover: Double = 0.04
    /// The room the words of a row have in the wide sidebar.
    fileprivate static let words: CGFloat = wideWidth - (inset + 10) * 2 - glyph - 11

    @Environment(EngineModel.self) private var model
    @Binding var selection: SidebarSelection?
    @Binding var showPairing: Bool
    var folded = false
    /// Asks to open over the page for a moment, for a banner that is only an icon while the sidebar is folded.
    var onPeek: () -> Void = {}
    @Namespace private var chosen
    @AppStorage("updateDismissedVersion") private var dismissedVersion = ""

    /// The rows there are, from the top.
    private var order: [SidebarSelection] {
        model.devices.flatMap { device -> [SidebarSelection] in
            device.id == model.watchPhoneID && device.status.watch != nil ? [.device(device.id), .watch(device.id)] : [.device(device.id)]
        } + [.shared] + (messagesPhone.map { [.messages($0.id)] } ?? []) + [.notifications, .clipboard]
    }

    /// The row of the page that is open: the files of a device are under the row of that device, health under the watch.
    private var chosenRow: SidebarSelection? {
        switch selection {
        case let .files(id): .device(id)
        case let .health(id): .watch(id)
        default: selection
        }
    }

    /// The phones that have messages to show.
    private var phones: [TandemDevice] { model.devices.filter(Sidebar.offersMessages) }

    /// A phone has its messages, and its calls with them, as a place of its own in the second group. What a device can do is only
    /// known while it is connected, so a phone that is away keeps the place: the page itself says that it cannot be reached.
    static func offersMessages(_ device: TandemDevice) -> Bool {
        device.platform == .android && (device.caps.isEmpty || device.caps.contains("sms"))
    }

    /// The phone whose messages were open last, kept for the one row of Messages.
    @MainActor static var lastMessagesPhone: String? {
        get { UserDefaults.standard.string(forKey: "messagesPhone") }
        set { UserDefaults.standard.set(newValue, forKey: "messagesPhone") }
    }

    /// The phone the one row of Messages opens: the one that was open last, otherwise the first that is connected. With more than
    /// one phone the page itself has the choice.
    private var messagesPhone: TandemDevice? {
        if case let .messages(id) = selection, let open = phones.first(where: { $0.id == id }) { return open }
        return phones.first { $0.id == Sidebar.lastMessagesPhone } ?? phones.first(where: \.online) ?? phones.first
    }

    /// A word for each device in the narrow sidebar: the one that tells it from the others.
    static func shortNames(_ devices: [TandemDevice]) -> [String: String] {
        let words = devices.map { $0.name.split(whereSeparator: \.isWhitespace).map(String.init) }
        // A first word they all share (the name of their owner) says nothing in a column this narrow.
        let shared = words.count > 1 && words.allSatisfy { $0.count > 1 && $0[0] == words[0][0] }
        var names: [String: String] = [:]
        for (index, device) in devices.enumerated() {
            let rest = Array(words[index].dropFirst(shared ? 1 : 0))
            let whole = rest.joined(separator: " ")
            var word = whole.count <= 10 ? whole : (rest.first ?? "")
            if word.count > 10 { word = String(word.prefix(9)) + "\u{2026}" }
            names[device.id] = word
        }
        return names
    }

    /// The update banner has something to show. While folded it is only an icon, which opens the sidebar over the page.
    private var updateWaits: Bool {
        switch Updater.shared.state {
        case let .available(release): release.version != dismissedVersion
        case .downloading, .installing: true
        case .failed: Updater.shared.installFailed
        default: false
        }
    }

    var body: some View {
        let short = Sidebar.shortNames(model.devices)
        // The foot under the list and not over it: rows that scroll end above it, in a short fade, instead of running on under the
        // way to pair.
        VStack(spacing: 0) {
            list(short: short)
                .mask {
                    VStack(spacing: 0) {
                        Rectangle()
                        LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: 14)
                    }
                }
            foot
        }
        // What is laid out for the wide sidebar ends at the edge of the narrow one.
        .clipped()
    }

    private func list(short: [String: String]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                DevicesHeader()
                    .wideOnly(folded, inset: 12)
                    .padding(.top, 10)
                    .padding(.bottom, 4)
                ForEach(model.devices, id: \.id) { device in
                    let on = chosenRow == .device(device.id)
                    SidebarButton(row: .device(device.id), selected: on, chosen: chosen) {
                        selection = .device(device.id)
                    } content: {
                        DeviceRow(device: device, folded: folded, short: short[device.id] ?? device.name, selected: on)
                    }
                    .help(folded ? Text(verbatim: device.name) : Text(verbatim: ""))
                    // The watch only talks to this phone, but it is a device of its own: its page goes by way of the phone.
                    if device.id == model.watchPhoneID, let watch = device.status.watch {
                        let on = chosenRow == .watch(device.id)
                        SidebarButton(row: .watch(device.id), selected: on, chosen: chosen) {
                            selection = .watch(device.id)
                        } content: {
                            WatchRow(watch: watch, online: device.online, folded: folded, selected: on)
                        }
                    }
                }

                Text("Shared")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .wideOnly(folded, inset: 12)
                    // Folded, a short line under the glyphs keeps the two groups apart where the heading was.
                    .overlay(alignment: .leading) {
                        Capsule()
                            .fill(Color.primary.opacity(0.12))
                            .frame(width: Sidebar.glyph - 10, height: 1.5)
                            .offset(x: 15)
                            .opacity(folded ? 1 : 0)
                            .animation(.easeOut(duration: 0.18), value: folded)
                    }
                    .padding(.top, 16)
                    .padding(.bottom, 4)
                SidebarButton(row: .shared, selected: selection == .shared, chosen: chosen) {
                    selection = .shared
                } content: {
                    SharedRow(folded: folded, selected: selection == .shared)
                }
                // One row for the messages, of whichever phone was open last: with two phones the page itself has the choice.
                if let phone = messagesPhone {
                    SidebarButton(row: .messages(phone.id), selected: selection == .messages(phone.id), chosen: chosen) {
                        selection = .messages(phone.id)
                    } content: {
                        MessagesRow(phones: phones, folded: folded, selected: selection == .messages(phone.id))
                    }
                    .transition(.opacity)
                }
                SidebarButton(row: .notifications, selected: selection == .notifications, chosen: chosen) {
                    selection = .notifications
                } content: {
                    NotificationsRow(folded: folded, selected: selection == .notifications)
                }
                SidebarButton(row: .clipboard, selected: selection == .clipboard, chosen: chosen) {
                    selection = .clipboard
                } content: {
                    ClipboardRow(folded: folded, selected: selection == .clipboard)
                }
            }
            // One fill for the chosen row, with the place and the size of that row. When another row is chosen it is this same fill
            // that glides there. A fill of its own in every row is two fills for a moment: the new one is there at once while the old
            // one is still leaving.
            .background {
                if let row = chosenRow, order.contains(row) {
                    RoundedRectangle(cornerRadius: Sidebar.rowRadius, style: .continuous)
                        .fill(Palette.indigo.opacity(0.16))
                        .matchedGeometryEffect(id: row, in: chosen, isSource: false)
                        .transition(.opacity)
                }
            }
            .padding(.horizontal, Sidebar.inset)
            .animation(.tandem, value: model.devices.map(\.id))
            .animation(.tandem, value: selection)
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(folded ? .hidden : .automatic)
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.upArrow) { move(-1) }
        .onKeyPress(.downArrow) { move(1) }
    }

    /// At the foot of the sidebar: the update sits just above the way to pair, out of the way of the pages.
    private var foot: some View {
        VStack(alignment: .leading, spacing: 0) {
            if folded {
                if updateWaits {
                    FoldedUpdateButton(onPeek: onPeek)
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                }
            } else {
                UpdateBanner(fromBottom: true)
                    .padding(.horizontal, Sidebar.inset)
                    .frame(width: Sidebar.wideWidth)
                    .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            }
            PairButton(showPairing: $showPairing, folded: folded)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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

extension Animation {
    /// The sidebar folding and opening: a spring with a little life in it, for its width and everything that moves with it.
    static let tandemSidebar = Animation.spring(response: 0.45, dampingFraction: 0.8)
}

extension View {
    /// Something that is only there in the wide sidebar, a heading for one: it keeps its place and its width while the sidebar
    /// folds, so nothing under it moves and its words are not wrapped, and fades away.
    fileprivate func wideOnly(_ folded: Bool, inset: CGFloat) -> some View {
        frame(width: Sidebar.wideWidth - Sidebar.inset * 2 - inset * 2, alignment: .leading)
            .padding(.horizontal, inset)
            // No width of its own towards the column: with one, the column would stay as wide as this and slide out of the narrow sidebar.
            .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
            .opacity(folded ? 0 : 1)
            .animation(.easeOut(duration: 0.18), value: folded)
    }
}

/// One row of the sidebar. It says where it is, for the fill of the chosen row that the list draws under it, and has a faint
/// highlight of its own under the pointer. The chosen row has none: its fill already says more.
private struct SidebarButton<Content: View>: View {
    let row: SidebarSelection
    let selected: Bool
    let chosen: Namespace.ID
    let action: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Sidebar.rowRadius, style: .continuous)
        let lit = (hovering || DebugSupport.pointerEverywhere) && hoverEnabled && !selected
        Button(action: action) {
            content
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    shape.fill(Color.primary.opacity(lit ? Sidebar.hover : 0))
                        // Its own pace, also when it goes because the row was chosen: gone before the fill has arrived.
                        .animation(.hover(lit), value: lit)
                        .matchedGeometryEffect(id: row, in: chosen)
                }
                .contentShape(shape)
        }
        .buttonStyle(SidebarPressStyle())
        .onHover { hovering = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
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

/// What a row is made of, in both widths. The glyph decides the size of the row and never moves. The words beside it are laid over
/// the row at the width they have in the wide sidebar and fade as it folds; the small name under the glyph comes once they are gone.
private struct RailRow<Glyph: View, Words: View>: View {
    let folded: Bool
    /// The name under the glyph in the narrow sidebar.
    let mini: String
    var selected = false
    /// A count that stays in sight while folded, on the corner of the glyph.
    var badge = 0
    @ViewBuilder var glyph: Glyph
    @ViewBuilder var words: Words

    private static var miniWidth: CGFloat { Sidebar.railWidth - 6 }

    var body: some View {
        glyph
            .frame(width: Sidebar.glyph, height: Sidebar.glyph)
            .overlay(alignment: .topTrailing) {
                if badge > 0 {
                    Text("\(min(badge, 99))")
                        .font(.system(size: 10, weight: .bold).monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 17, minHeight: 17)
                        .background(Palette.indigo, in: .capsule)
                        .offset(x: 5, y: -3)
                        .opacity(folded ? 1 : 0)
                        .animation(.easeOut(duration: 0.18), value: folded)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            // Room for the small name: the row grows a little as the sidebar folds.
            .padding(.top, 3)
            .padding(.bottom, folded ? 17 : 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .topLeading) {
                words
                    .frame(width: Sidebar.words, height: Sidebar.glyph, alignment: .leading)
                    .offset(x: Sidebar.glyph + 11 - (folded ? 10 : 0), y: 3)
                    .opacity(folded ? 0 : 1)
                    .animation(.easeOut(duration: 0.18), value: folded)
            }
            .overlay(alignment: .bottomLeading) {
                Text(verbatim: mini)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(selected ? Palette.indigo : Color.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(width: Self.miniWidth)
                    .offset(x: (Sidebar.glyph - Self.miniWidth) / 2, y: -1)
                    .opacity(folded ? 1 : 0)
                    // After the words have gone, and gone before they come back.
                    .animation(folded ? .easeOut(duration: 0.25).delay(0.18) : .easeOut(duration: 0.1), value: folded)
            }
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
        .lineLimit(1)
        .animation(.tandem, value: model.onlineCount)
        .animation(.tandem, value: model.devices.count)
    }
}

/// A level as a thin ring around a glyph: the battery of a device, the same circle in the wide sidebar and in the narrow one.
private struct LevelRing: View {
    let level: Int
    let tint: Color

    /// Anything below full leaves an opening, as the large ring on the page of a device does.
    private var arc: CGFloat { level >= 100 ? 1 : CGFloat(level) / 100 * 0.945 }

    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.1), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: arc)
                .stroke(tint, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(1.25)
        .animation(.tandem, value: level)
        .animation(.tandemFade, value: tint)
    }
}

private struct DeviceRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let folded: Bool
    let short: String
    let selected: Bool

    /// The colours of the large ring on the page of the device.
    private func tint(_ battery: TandemBattery) -> Color {
        if !device.online { return .gray }
        if battery.charging { return .green }
        return battery.level <= 15 ? Palette.urgent : Palette.indigo
    }

    var body: some View {
        let reach = model.reach(of: device)
        let battery = device.status.battery
        RailRow(folded: folded, mini: short, selected: selected) {
            ZStack {
                // With a ring around it the glyph is a size smaller, so the ring is the outline every row has.
                DeviceGlyph(platform: device.platform, online: device.online, size: battery == nil ? Sidebar.glyph : Sidebar.glyph - 8, deviceID: device.id)
                if let battery {
                    LevelRing(level: Int(battery.level), tint: tint(battery))
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                }
            }
            .animation(.tandem, value: battery != nil)
        } words: {
            VStack(alignment: .leading, spacing: 2) {
                // The whole first line is the name: a long one is no longer cut short by the battery beside it.
                Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                HStack(spacing: 4) {
                    Text(reach.text)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(reach.color)
                    if device.online, let rtt = device.rttMs {
                        Text("· \(rtt) ms").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    if let battery {
                        HStack(spacing: 2) {
                            if battery.charging && device.online { Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.green) }
                            Text("\(battery.level)%")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .contentTransition(.numericText(value: Double(battery.level)))
                        }
                    }
                }
                .lineLimit(1)
            }
        }
        // Not reachable: the whole row steps back, so the connected ones stand out.
        .opacity(reach.reachable ? 1 : 0.6)
        .animation(.tandem, value: device.online)
        .animation(.tandem, value: reach.reachable)
    }
}

private struct WatchRow: View {
    let watch: TandemWatchStatus
    let online: Bool
    let folded: Bool
    let selected: Bool

    var body: some View {
        let reachable = online && watch.online
        let name = watch.name ?? String(localized: "Watch")
        RailRow(folded: folded, mini: name, selected: selected) {
            ZStack {
                let size = watch.level == nil ? Sidebar.glyph : Sidebar.glyph - 8
                WatchGlyph(online: reachable, size: size)
                if let level = watch.level {
                    LevelRing(level: Int(level), tint: !reachable ? .gray : (watch.charging ? .green : (level <= 15 ? Palette.urgent : Palette.indigo)))
                }
            }
        } words: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name).font(.callout.weight(.semibold)).lineLimit(1)
                HStack(spacing: 4) {
                    Text(reachable ? "Connected" : "Watch away")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(reachable ? Color.green : Color.secondary)
                    Spacer(minLength: 4)
                    if let level = watch.level {
                        Text("\(level)%").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .lineLimit(1)
            }
        }
        .opacity(reachable ? 1 : 0.6)
        .animation(.tandem, value: reachable)
    }
}

/// A row of the second group, built like a device row so the two groups carry the same weight:
/// a disc with a symbol, a title and a line under it.
private struct PageRow: View {
    let symbol: String
    /// In the language of the person: it is also the small name under the disc in the narrow sidebar.
    let title: String
    let subtitle: String
    var badge = 0
    var pulse = false
    let folded: Bool
    let selected: Bool

    var body: some View {
        RailRow(folded: folded, mini: title, selected: selected, badge: badge) {
            PulsingSymbol(name: symbol, pointSize: 17, weight: .semibold, color: NSColor(Palette.indigo), active: pulse)
                .frame(width: Sidebar.glyph, height: Sidebar.glyph)
                .background(Palette.indigo.opacity(0.14), in: Circle())
        } words: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title).font(.callout.weight(.semibold)).lineLimit(1)
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
        }
        .animation(.tandem, value: badge)
    }
}

/// The messages of the phones, and their calls with them (see `MessagesView`), in the second group like Files and Notifications. One row
/// for all phones, with what is unread on any of them.
private struct MessagesRow: View {
    let phones: [TandemDevice]
    let folded: Bool
    let selected: Bool

    var body: some View {
        let unread = phones.reduce(0) { $0 + MessagesStore.shared.phone($1.id).unreadCount }
        PageRow(
            symbol: "message.fill",
            title: String(localized: "Messages"),
            subtitle: String(localized: "Texts and calls"),
            badge: unread,
            folded: folded,
            selected: selected
        )
        .opacity(phones.contains { $0.online } ? 1 : 0.6)
    }
}

private struct SharedRow: View {
    @Environment(EngineModel.self) private var model
    let folded: Bool
    let selected: Bool

    var body: some View {
        let active = model.transfers.filter { $0.state == .active }.count
        let total = model.transfers.count
        PageRow(
            symbol: "arrow.up.arrow.down.circle.fill",
            title: String(localized: "Files"),
            subtitle: total == 0 ? String(localized: "Nothing yet") : String(localized: "\(total) shared"),
            badge: active,
            pulse: active > 0,
            folded: folded,
            selected: selected
        )
    }
}

private struct NotificationsRow: View {
    @Environment(EngineModel.self) private var model
    let folded: Bool
    let selected: Bool

    var body: some View {
        let count = model.mirrored.count
        PageRow(
            symbol: "bell.fill",
            title: String(localized: "Notifications"),
            subtitle: count == 0 ? String(localized: "Nothing yet") : String(localized: "\(count) from your phones"),
            folded: folded,
            selected: selected
        )
    }
}

private struct ClipboardRow: View {
    let folded: Bool
    let selected: Bool

    var body: some View {
        let history = ClipboardHistory.shared
        let count = history.items.count
        PageRow(
            symbol: "doc.on.clipboard.fill",
            title: String(localized: "Clipboard"),
            subtitle: !history.enabled ? String(localized: "Off") : (count == 0 ? String(localized: "Nothing yet") : String(localized: "\(count) saved")),
            folded: folded,
            selected: selected
        )
    }
}

/// A round button at the foot of the folded sidebar, in line with the glyphs above it.
/// The update while the sidebar is folded, where the card has no room: one tap starts it, and the progress is drawn in the button
/// itself (a ring that fills, then a green tick). A failed update opens the sidebar, where the card says why.
private struct FoldedUpdateButton: View {
    let onPeek: () -> Void
    @LocalState private var updater = Updater.shared

    private var progress: Double? {
        switch updater.state {
        case let .downloading(_, fraction): fraction
        case .installing: 1
        default: nil
        }
    }

    var body: some View {
        let installing = updater.state == .installing
        let failed: Bool = { if case .failed = updater.state { return true } else { return false } }()
        let tint = installing ? Color(hex: 0x34C759) : failed ? Palette.urgent : Palette.indigo
        Button {
            if case .available = updater.state { Task { await updater.install() } } else if failed { onPeek() }
        } label: {
            ZStack {
                if let progress {
                    Circle().stroke(tint.opacity(0.2), lineWidth: 3)
                    Circle().trim(from: 0, to: progress).stroke(tint, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(.tandem, value: progress)
                }
                Image(systemName: installing ? "checkmark" : failed ? "exclamationmark.triangle.fill" : "sparkles")
                    .font(.system(size: progress == nil ? 19 : 15, weight: .semibold))
                    .foregroundStyle(tint)
                    .contentTransition(.opacity)
            }
            .frame(width: Sidebar.glyph - (progress == nil ? 0 : 6), height: Sidebar.glyph - (progress == nil ? 0 : 6))
            .frame(width: Sidebar.glyph, height: Sidebar.glyph)
            .hoverHighlight(radius: Sidebar.glyph / 2)
        }
        .buttonStyle(.bouncy)
        .help(installing ? "Installing…" : progress != nil ? "Downloading" : "Update")
        .padding(.leading, Sidebar.inset + 10)
        .padding(.vertical, 2)
        .animation(.tandem, value: progress == nil)
    }
}

private struct FootButton: View {
    let symbol: String
    let name: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(Palette.indigo)
                .frame(width: Sidebar.glyph, height: Sidebar.glyph)
                .hoverHighlight(radius: Sidebar.glyph / 2)
        }
        .buttonStyle(.bouncy)
        // The tooltip of the system, as the rows above it have while folded: a name drawn under this button would lie on the plus.
        .help(name)
        .accessibilityLabel(Text(name))
        .padding(.leading, Sidebar.inset + 10)
        .padding(.vertical, 2)
    }
}

/// A plain row at the bottom of the sidebar, like Notes has for a new folder. It is
/// not glass: the sidebar already is the glass layer. Folded it is its plus alone, under the glyphs of the rows.
private struct PairButton: View {
    @Binding var showPairing: Bool
    let folded: Bool

    var body: some View {
        Button {
            showPairing = true
        } label: {
            Image(systemName: "plus.circle.fill")
                .font(.system(size: 17))
                .foregroundStyle(Palette.indigo)
                .frame(width: Sidebar.glyph, height: 34)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .leading) {
                    Text("Pair a device")
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                        .frame(width: Sidebar.words, alignment: .leading)
                        .offset(x: Sidebar.glyph + 11 - (folded ? 10 : 0))
                        .opacity(folded ? 0 : 1)
                        .animation(.easeOut(duration: 0.18), value: folded)
                }
                .padding(.horizontal, 10)
                .hoverHighlight(radius: 14)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, Sidebar.inset)
        .padding(.top, 4)
        .padding(.bottom, 10)
        .help(folded ? Text("Pair a device") : Text(verbatim: ""))
        // Folded, its words are there but not seen: without this VoiceOver may read only the plus.
        .accessibilityLabel(Text("Pair a device"))
    }
}
