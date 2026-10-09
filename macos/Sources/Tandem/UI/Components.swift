import QuickLookThumbnailing
import SwiftUI
import TandemCore

// MARK: Cards and actions

/// A card of content on a plain surface. Glass is for what floats above the content
/// (the toolbar, controls, the toast), never for the content itself.
struct Card<Content: View>: View {
    var radius: CGFloat = Metrics.card
    var padding: CGFloat = 20
    var tint: Color? = nil
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .surface(radius: radius, tint: tint)
    }
}

/// An action on a device. It is a system glass button, so it gets the standard
/// pressed and hover behaviour for free, and it sits in a GlassEffectContainer with
/// its neighbours.
struct GlassActionButton: View {
    let title: LocalizedStringKey
    let symbol: String
    var prominent = false
    /// Takes the width it is given, so buttons side by side come out the same size whatever their labels are.
    var wide = false
    let action: () -> Void

    var body: some View {
        let button = Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.callout.weight(.semibold))
                .frame(maxWidth: wide ? .infinity : nil)
        }
        if prominent {
            button.buttonStyle(.glassProminent).tint(Palette.indigo).controlSize(.large)
        } else {
            button.buttonStyle(.glass).controlSize(.large)
        }
    }
}

// MARK: Round icon buttons for the menu bar panel

/// The disc under an icon in the menu bar panel. A plain tint that darkens under the pointer: the system's glass
/// button flashed grey and fell back inside that panel, and a tint does not.
private struct RoundIconDisc: View {
    let symbol: String
    var tint: Color?
    var size: CGFloat
    var hovering: Bool
    var enabled: Bool

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.43, weight: .semibold))
            .contentTransition(.symbolEffect(.replace))
            .foregroundStyle(tint ?? Color.primary)
            .frame(width: size, height: size)
            .background(Circle().fill(Color.primary.opacity(hovering && enabled ? 0.2 : 0.1)))
            .overlay(Circle().strokeBorder(Color.primary.opacity(0.06), lineWidth: 1))
            .contentShape(Circle())
            .opacity(enabled ? 1 : 0.4)
            .animation(.tandemFade, value: hovering)
    }
}

struct RoundPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A round button with an icon. It says what it does through `onHover`, so the row it sits in can show the words where
/// they are read, instead of a tooltip that comes late and over something else.
struct RoundIconButton: View {
    let symbol: String
    var tint: Color?
    var size: CGFloat = 30
    var onHover: ((Bool) -> Void)?
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) {
            RoundIconDisc(symbol: symbol, tint: tint, size: size, hovering: hovering && hoverEnabled, enabled: isEnabled)
        }
        .buttonStyle(RoundPressStyle())
        .onHover { inside in
            hovering = inside
            onHover?(inside)
        }
    }
}

/// The same round button that opens a menu.
struct RoundIconMenu<Content: View>: View {
    let symbol: String
    var size: CGFloat = 30
    var onHover: ((Bool) -> Void)?
    @ViewBuilder var content: Content
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Menu {
            content
        } label: {
            RoundIconDisc(symbol: symbol, tint: nil, size: size, hovering: hovering && hoverEnabled, enabled: isEnabled)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .onHover { inside in
            hovering = inside
            onHover?(inside)
        }
    }
}

// MARK: Device visuals

extension TandemPlatform {
    var symbol: String {
        switch self {
        case .android: "iphone"
        case .macOs: "laptopcomputer"
        case .linux: "desktopcomputer"
        case .windows: "pc"
        case .ios: "iphone"
        case .other: "display"
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .android: "Android"
        case .macOs: "Mac"
        case .linux: "Linux"
        case .windows: "Windows"
        case .ios: "iPhone"
        case .other: "Device"
        }
    }
}

extension TandemRoute {
    var label: LocalizedStringKey {
        switch self {
        case .lan: "Local network"
        case .tailnet: "Tailscale"
        case .other: "Internet"
        }
    }

    var symbol: String {
        switch self {
        case .lan: "wifi"
        case .tailnet: "point.3.connected.trianglepath.dotted"
        case .other: "globe"
        }
    }
}

/// How a device can be reached right now, best first. Bluetooth is a link that is up, or the beacon of the phone in
/// range: either way the hotspot can be asked for, which is what matters when there is no network.
enum Reach {
    case network, bluetooth, none

    var reachable: Bool { self != .none }

    var text: LocalizedStringKey {
        switch self {
        case .network: "Connected"
        case .bluetooth: "Over Bluetooth"
        case .none: "Not connected"
        }
    }

    var color: Color {
        switch self {
        case .network: .green
        case .bluetooth: Palette.indigo
        case .none: .secondary
        }
    }

    var symbol: String {
        switch self {
        case .network: "checkmark.circle.fill"
        case .bluetooth: "dot.radiowaves.left.and.right"
        case .none: "circle.dashed"
        }
    }
}

extension EngineModel {
    func reach(of device: TandemDevice) -> Reach {
        if device.online { return .network }
        return device.ble || bleNearby.contains(device.id) ? .bluetooth : .none
    }
}

/// A device as a round glyph. Connected devices get the accent tint. The green ring is
/// only for the small glyph in the toolbar (`ring`), where there is no card around it to
/// say whether the device is there. Flat on purpose: it sits on the sidebar and on cards,
/// which are already the layer under the glass.
struct DeviceGlyph: View {
    @Environment(EngineModel.self) private var model
    let platform: TandemPlatform
    var online: Bool
    var size: CGFloat = 44
    var ring = false
    /// Drawn on the accent-coloured selection of a sidebar row, where the usual indigo
    /// and green would disappear into the blue.
    var onSelection = false
    /// Just the symbol, larger, with no disc behind it (the big one on a device's page).
    var plain = false
    /// With an id the glyph follows the icon the person picked for that device.
    var deviceID: String?
    /// Green instead of indigo while the device is there, for where nothing else says that it is (the menu bar panel).
    var greenWhenOnline = false

    private var onlineColor: Color { greenWhenOnline ? .green : Palette.indigo }

    private var symbol: String {
        deviceID.flatMap { model.deviceIcons[$0] } ?? platform.symbol
    }

    var body: some View {
        let ringWidth = max(1.6, size * 0.055)
        ZStack {
            if !plain {
                Circle().fill(onSelection ? Color.white.opacity(0.24) : (online ? onlineColor.opacity(0.14) : Color.primary.opacity(0.07)))
            }
            Image(systemName: symbol)
                .font(.system(size: size * (plain ? 0.86 : (size > 60 ? 0.5 : 0.42)), weight: plain ? .regular : .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(onSelection ? Color.white : (online ? onlineColor : Color.secondary))
                .contentTransition(.symbolEffect(.replace))
            if ring {
                Circle()
                    .stroke(Color.primary.opacity(0.08), lineWidth: ringWidth)
                    .padding(ringWidth / 2)
                Circle()
                    .trim(from: 0, to: online ? 1 : 0)
                    .stroke(Color.green, style: StrokeStyle(lineWidth: ringWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(ringWidth / 2)
            }
        }
        .frame(width: size, height: size)
        .animation(.tandem, value: online)
        .animation(.tandem, value: symbol)
    }
}

/// The icons a device can be given, for the picker on its page.
enum DeviceIconChoice: CaseIterable {
    case phone, tablet, laptop, desktop, watch, tv

    var symbol: String {
        switch self {
        case .phone: "iphone"
        case .tablet: "ipad"
        case .laptop: "laptopcomputer"
        case .desktop: "desktopcomputer"
        case .watch: "applewatch"
        case .tv: "tv"
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .phone: "Phone"
        case .tablet: "Tablet"
        case .laptop: "Laptop"
        case .desktop: "Desktop"
        case .watch: "Watch"
        case .tv: "TV"
        }
    }
}

/// Battery as a ring. The number rolls when it changes.
struct BatteryRing: View {
    let battery: TandemBattery
    var size: CGFloat = 64

    private var tint: Color {
        if battery.charging { return .green }
        if battery.level <= 15 { return Palette.urgent }
        return Palette.indigo
    }

    /// A ring that is 97 percent full looks like a closed circle with a flaw. Anything below
    /// 100 leaves a visible opening, and only a full battery closes the ring.
    private var arc: CGFloat {
        let level = CGFloat(battery.level) / 100
        return battery.level >= 100 ? 1 : level * 0.945
    }

    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.08), lineWidth: size * 0.11)
            Circle()
                .trim(from: 0, to: arc)
                .stroke(tint, style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.tandem, value: battery.level)
            VStack(spacing: 0) {
                Text("\(battery.level)")
                    .font(.system(size: size * 0.28, weight: .bold, design: .rounded))
                    .contentTransition(.numericText(value: Double(battery.level)))
                    .animation(.tandem, value: battery.level)
                if battery.charging {
                    PulsingSymbol(name: "bolt.fill", pointSize: size * 0.16, color: .systemGreen)
                        .frame(width: size * 0.2, height: size * 0.2)
                }
            }
        }
        .frame(width: size, height: size)
        .animation(.tandemFade, value: tint)
    }
}

/// The pill from the app icon, turning and pausing. Used for anything that is loading.
struct PillSpinner: View {
    var size: CGFloat = 28

    var body: some View {
        // 30 frames a second is smooth for something this slow, and a screen that refreshes 120 times a second would
        // wake the app four times as often.
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
            let cycle = 2.6
            let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
            // Two eased turns, with a breath between them.
            let turn: Double = t < 0.42 ? ease(t / 0.42) : (t < 0.5 ? 1 : (t < 0.92 ? 1 + ease((t - 0.5) / 0.42) : 2))
            // At rest it leans like the pills in the icon, and half a turn is the same shape.
            Capsule()
                .fill(Palette.indigo.gradient)
                .frame(width: size * 0.42, height: size)
                .rotationEffect(.degrees(turn * 180 - PillArt.leanDegrees))
        }
        .frame(width: size, height: size)
    }

    private func ease(_ x: Double) -> Double {
        x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
    }
}

// MARK: Transfers

/// What a transfer looks like. Interaction lives in `TransferEntry`.
struct TransferRow: View {
    let item: TransferItem
    let peerName: String
    var hovering = false
    @Environment(EngineModel.self) private var model

    var body: some View {
        HStack(spacing: 12) {
            leading
                .frame(width: 40, height: 40)
                .scaleEffect(hovering ? 1.05 : 1)
                .animation(.tandemSpringy, value: hovering)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Group {
                    switch item.state {
                    case .active:
                        Text("\(formatBytes(item.done)) of \(formatBytes(item.total))  \(formatSpeed(item.speed))")
                    case .done:
                        if missing {
                            Text("The file is no longer there")
                        } else {
                            Text(item.incoming ? "From \(peerName)" : "To \(peerName)")
                        }
                    case .failed:
                        Text(item.error ?? String(localized: "Failed"))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

                if item.state == .active {
                    ProgressCapsule(fraction: item.fraction)
                        .frame(height: 5)
                        .transition(.opacity)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
        .animation(.tandem, value: item.state)
        .animation(.tandemSpringy, value: hovering)
        .task(id: item.state == .done ? item.location : nil) { await loadThumbnail() }
    }

    @LocalState private var thumbnail: NSImage?

    /// The picture of the file when there is one to show, otherwise what state it is in.
    @ViewBuilder
    private var leading: some View {
        if let thumbnail {
            Image(nsImage: thumbnail)
                .resizable()
                .scaledToFill()
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        } else {
            ZStack {
                Circle().fill(iconTint.opacity(0.16))
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(iconTint)
                    .contentTransition(.symbolEffect(.replace))
            }
        }
    }

    private func loadThumbnail() async {
        guard item.state == .done, model.canOpen(item), let path = model.filePath(item) else {
            thumbnail = nil
            return
        }
        let request = QLThumbnailGenerator.Request(
            fileAt: URL(fileURLWithPath: path),
            size: CGSize(width: 40, height: 40),
            scale: 2,
            representationTypes: .thumbnail
        )
        let made = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request)
        thumbnail = made?.nsImage
    }

    /// A file that arrived and is gone now: no check but a cross, so the list does not promise what is not there.
    private var missing: Bool { item.state == .done && item.location != nil && !model.canOpen(item) }

    private var symbol: String {
        switch item.state {
        case .active: item.incoming ? "arrow.down" : "arrow.up"
        case .done: missing ? "xmark" : "checkmark"
        case .failed: "exclamationmark"
        }
    }

    private var iconTint: Color {
        switch item.state {
        case .active: Palette.indigo
        case .done: missing ? Palette.urgent : .green
        case .failed: Palette.urgent
        }
    }
}

/// A transfer you can act on: a click opens the file, a right click offers the rest,
/// and Delete takes it off the list while the row has the keyboard focus.
struct TransferEntry: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem
    let peerName: String
    /// Where the slab sits in its list, so the first and the last are rounder than the ones between.
    var first = true
    var last = true
    @FocusState private var focused: Bool

    private var shape: UnevenRoundedRectangle {
        let outer: CGFloat = 22, inner: CGFloat = 7
        return UnevenRoundedRectangle(
            topLeadingRadius: first ? outer : inner, bottomLeadingRadius: last ? outer : inner,
            bottomTrailingRadius: last ? outer : inner, topTrailingRadius: first ? outer : inner,
            style: .continuous
        )
    }

    var body: some View {
        Hoverable { hovering in
            HStack(spacing: 6) {
                TransferRow(item: item, peerName: peerName, hovering: hovering)
                menu(hovering: hovering)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 2)
        // Plain like the cards around it (the hotspot, the settings), not a grey of its own.
        .background(shape.fill(Color(nsColor: .controlBackgroundColor)).overlay { shape.strokeBorder(Color.primary.opacity(0.06), lineWidth: 1) })
        .hoverHighlight(radius: 12, tint: focused ? Palette.indigo : .primary, selected: focused)
        .clipShape(shape)
        .contentShape(shape)
        .onTapGesture {
            focused = true
            model.open(item)
        }
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onDeleteCommand {
            guard item.state != .active else { return }
            withAnimation(.tandem) { model.removeFromList([item.id]) }
        }
        .onKeyPress(.return) {
            model.open(item)
            return .handled
        }
        .contextMenu { TransferMenu(item: item) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .help(model.canOpen(item) ? Text("Open") : Text(""))
    }

    /// The three dots of the Android app: everything the right click offers, where it can be seen.
    private func menu(hovering: Bool) -> some View {
        Menu {
            TransferMenu(item: item)
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.secondary)
                .frame(width: 32, height: 32)
                .background(Circle().fill(Color.primary.opacity(hovering ? 0.12 : 0.07)))
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("More")
    }
}

/// The right-click menu of a transfer.
struct TransferMenu: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem

    var body: some View {
        let available = model.canOpen(item)
        Button("Open") { model.open(item) }
            .disabled(!available)
        Button("Open File Location") { model.reveal(item) }
            .disabled(!available)
        Button("Copy path") { model.copyPath(item) }
            .disabled(item.location == nil)
        Divider()
        Button("Remove from List") {
            withAnimation(.tandem) { model.removeFromList([item.id]) }
        }
        .disabled(item.state == .active)
        // Only what arrived on this Mac. A file that was sent is the person's own original.
        if item.incoming {
            Button("Move File to Trash…", role: .destructive) { model.requestTrash(item) }
                .disabled(!available)
        }
    }
}

struct ProgressCapsule: View {
    let fraction: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.08))
                Capsule()
                    .fill(Palette.indigo.gradient)
                    .frame(width: max(5, proxy.size.width * fraction))
                    .animation(.tandem, value: fraction)
            }
        }
    }
}

// MARK: Feedback

/// The toast floats over the content, so it is glass.
struct ToastView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.callout.weight(.medium))
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .glassEffect(.regular, in: .capsule)
            .shadow(color: .black.opacity(0.12), radius: 14, y: 6)
    }
}

/// Small explanation next to a setting that needs one.
struct HelpTip: View {
    let text: LocalizedStringKey
    @LocalState private var shown = false

    var body: some View {
        Button { shown.toggle() } label: {
            Image(systemName: "questionmark.circle").foregroundStyle(.secondary)
        }
        .buttonStyle(.bouncy)
        .popover(isPresented: $shown) {
            Text(text).font(.callout).padding(14).frame(width: 260, alignment: .leading)
        }
    }
}
