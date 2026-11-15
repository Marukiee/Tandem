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

/// Things side by side that go on to a next line when the row is full, such as the labels in the head of a device.
struct FlowRow: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    private func lines(width: CGFloat, subviews: Subviews) -> [[(index: Int, size: CGSize)]] {
        var lines: [[(index: Int, size: CGSize)]] = [[]]
        var used: CGFloat = 0
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            if !lines[lines.count - 1].isEmpty, used + spacing + size.width > width {
                lines.append([])
                used = 0
            }
            used += (lines[lines.count - 1].isEmpty ? 0 : spacing) + size.width
            lines[lines.count - 1].append((index, size))
        }
        return lines.filter { !$0.isEmpty }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = lines(width: proposal.width ?? .infinity, subviews: subviews)
        let width = lines.map { line in line.reduce(0) { $0 + $1.size.width } + spacing * CGFloat(line.count - 1) }.max() ?? 0
        let height = lines.reduce(0) { $0 + ($1.map(\.size.height).max() ?? 0) } + lineSpacing * CGFloat(max(0, lines.count - 1))
        return CGSize(width: min(width, proposal.width ?? width), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in lines(width: bounds.width, subviews: subviews) {
            let height = line.map(\.size.height).max() ?? 0
            var x = bounds.minX
            for item in line {
                // One that is wider than the row gets the row, so its words are cut and not drawn over the edge.
                let width = min(item.size.width, bounds.width)
                subviews[item.index].place(at: CGPoint(x: x, y: y + height / 2), anchor: .leading, proposal: ProposedViewSize(width: width, height: item.size.height))
                x += width + spacing
            }
            y += height + lineSpacing
        }
    }
}

/// The title of a page and the line under it, the same on every page that has one.
struct PageHeader<Extra: View, Actions: View>: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    @ViewBuilder var extra: Extra
    /// The buttons of the page, on the line of the title at its end: in the toolbar they floated alone in a corner of the window.
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 8) {
                Text(title).font(.largeTitle.weight(.bold))
                Spacer(minLength: 12)
                actions
            }
            Text(subtitle)
                .font(.callout)
                .foregroundStyle(.secondary)
            extra
        }
    }
}

extension PageHeader where Actions == EmptyView {
    init(title: LocalizedStringKey, subtitle: LocalizedStringKey, @ViewBuilder extra: () -> Extra) {
        self.init(title: title, subtitle: subtitle, extra: extra, actions: { EmptyView() })
    }
}

extension PageHeader where Extra == EmptyView, Actions == EmptyView {
    init(title: LocalizedStringKey, subtitle: LocalizedStringKey) {
        self.init(title: title, subtitle: subtitle, extra: { EmptyView() }, actions: { EmptyView() })
    }
}

/// An action on a device or on a page: a symbol and its words in the app's button, sitting in a GlassEffectContainer with its neighbours.
struct GlassActionButton: View {
    let title: LocalizedStringKey
    let symbol: String
    var prominent = false
    /// Takes the width it is given, so buttons side by side come out the same size whatever their labels are.
    var wide = false
    let action: () -> Void
    @Environment(\.actionButtonsAsRows) private var asRow

    var body: some View {
        if asRow {
            Button(action: action) { ActionRow(title: title, symbol: symbol, on: prominent) }
                .buttonStyle(BouncyButtonStyle())
        } else {
            pill
        }
    }

    private var pill: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                // A button that turns on or off changes its symbol and words: the symbol morphs, and the words fade across while the
                // pill springs to its new width (see the animation on the row).
                .contentTransition(.symbolEffect(.replace))
                .frame(maxWidth: wide ? .infinity : nil)
        }
        .buttonStyle(prominent ? .tandemGlassProminent : .tandemGlass)
        .controlSize(.large)
    }
}

/// The button with words of the whole app, outside the grouped forms of Settings (which use the system's push buttons, as System
/// Settings does). On the content of a page it is a pill of glass, because a control floats over the content. On something that is glass
/// already (a panel, a prompt) it is a fill, because glass on glass blurs twice; see `onGlass`.
///
/// Not the system's glass button: its glass leans towards the pointer while the words stay where they are, which made the words look
/// pinned to one side. The sizes are those of the system's glass buttons at each control size, measured, so a pill next to one of those
/// is exactly as tall. The pointer only brings a little light that fades in and out: no growing, as with the system's own buttons.
struct TandemGlassStyle: ButtonStyle {
    var prominent = false
    var tint: Color = Palette.indigo

    func makeBody(configuration: Configuration) -> some View {
        TandemGlassPill(configuration: configuration, prominent: prominent, tint: tint)
    }
}

private struct TandemGlassPill: View {
    let configuration: ButtonStyleConfiguration
    let prominent: Bool
    let tint: Color
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Pill(
            label: configuration.label, prominent: prominent, tint: tint, lit: hovering && isEnabled && hoverEnabled,
            pressed: configuration.isPressed, destructive: configuration.role == .destructive
        )
        .onHover { hovering = $0 }
    }
}

/// What a `TandemGlassStyle` button looks like, for a control that is not a button of its own, such as the label of a menu.
struct Pill<Content: View>: View {
    let label: Content
    var prominent = false
    var tint: Color = Palette.indigo
    /// Under the pointer.
    var lit = false
    var pressed = false
    var destructive = false
    @Environment(\.controlSize) private var size
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.onGlass) private var onGlass

    /// The font, and the room around the words, of the system's glass button at this size.
    private var metrics: (font: Font, horizontal: CGFloat, vertical: CGFloat) {
        switch size {
        case .mini: (.system(size: 10, weight: .medium), 5, 0)
        case .small: (.system(size: 11, weight: .medium), 10, 3)
        case .regular: (.system(size: 13, weight: .medium), 12, 4)
        case .large: (.system(size: 13, weight: .medium), 14, 6)
        case .extraLarge: (.system(size: 13, weight: .medium), 18, 10)
        @unknown default: (.system(size: 13, weight: .medium), 12, 4)
        }
    }

    private var ink: Color {
        if prominent { return .white }
        return destructive ? Palette.urgent : .primary
    }

    /// What lies under the words. A prominent button on glass is its colour; any other button on glass a thin fill, as the round
    /// buttons of the panels have; on a page nothing, because there the glass is the button.
    private var fill: Color {
        guard onGlass else { return .clear }
        return prominent ? tint : Color.primary.opacity(0.1)
    }

    /// The light under the pointer, and the shade of a press. A fill and not a change of the glass, because a new tint of the glass
    /// jumps from one to the other however it is animated.
    private var wash: Color {
        if pressed { return prominent ? .black.opacity(0.12) : Color.primary.opacity(0.12) }
        guard lit else { return .clear }
        return prominent ? .white.opacity(0.16) : Color.primary.opacity(0.08)
    }

    var body: some View {
        // A press takes a point off every side, by a real size and not by a scale, so the words stay in the middle of the glass. The
        // words are the content of the glass itself: glass drawn as a background of its own ends up over the words in a
        // `GlassEffectContainer`, which made them blurry.
        let inset: CGFloat = pressed ? 1 : 0
        label
            .font(metrics.font)
            .foregroundStyle(ink)
            .padding(.horizontal, metrics.horizontal - inset)
            .padding(.vertical, metrics.vertical - inset)
            .background {
                Capsule()
                    .fill(fill)
                    .overlay { if onGlass && !prominent { Capsule().strokeBorder(Color.primary.opacity(0.06), lineWidth: 1) } }
            }
            .contentShape(.capsule)
            .modifier(PillGlass(enabled: !onGlass, prominent: prominent, tint: tint))
            // The light lies on top of the glass and fades there on its own. Inside the glass every change of it had the glass drawn
            // again in the middle of the fade, which showed as a stutter under the pointer.
            .overlay {
                Capsule()
                    .fill(wash)
                    .allowsHitTesting(false)
                    .animation(.hover(lit), value: lit)
                    .animation(.tandemFade, value: pressed)
            }
            .padding(inset)
            .opacity(isEnabled ? 1 : 0.5)
            .animation(.tandemFade, value: prominent)
            .animation(.tandemFade, value: isEnabled)
            .animation(.tandemBouncy, value: pressed)
    }
}

/// A filter above a list: a pill that opens its choices, and is coloured while it narrows the list.
struct FilterMenu<Content: View>: View {
    let title: Text
    let symbol: String
    let active: Bool
    @ViewBuilder var content: Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Menu {
            content
        } label: {
            Pill(label: Label { title.lineLimit(1) } icon: { Image(systemName: symbol) }, prominent: active, lit: hovering && hoverEnabled)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .onHover { hovering = $0 }
    }
}

/// The search of a page: a capsule of glass in the page, on the row of its filters, the same on every page. Not the system's search in the
/// toolbar, which stood far from the list it narrows and looked different on every page.
struct PageSearchField: View {
    @Binding var text: String
    var prompt: LocalizedStringKey = "Search"
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.secondary)
            TextField(prompt, text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .focused($focused)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 30)
        .contentShape(.capsule)
        .onTapGesture { focused = true }
        .modifier(PillGlass(enabled: true, prominent: false, tint: Palette.indigo))
        .animation(.tandemFade, value: text.isEmpty)
    }
}

/// The glass of a pill, only where it does not sit on glass already.
private struct PillGlass: ViewModifier {
    let enabled: Bool
    let prominent: Bool
    let tint: Color

    func body(content: Content) -> some View {
        if !enabled {
            content
        } else if DebugSupport.flatGlass {
            content.background(prominent ? AnyShapeStyle(tint) : AnyShapeStyle(.regularMaterial), in: .capsule)
        } else {
            content.glassEffect(prominent ? .regular.tint(tint) : .regular, in: .capsule)
        }
    }
}

extension ButtonStyle where Self == TandemGlassStyle {
    static var tandemGlass: TandemGlassStyle { TandemGlassStyle() }
    static var tandemGlassProminent: TandemGlassStyle { TandemGlassStyle(prominent: true) }
    static func tandemGlassProminent(tint: Color) -> TandemGlassStyle { TandemGlassStyle(prominent: true, tint: tint) }
}

// MARK: Round icon buttons for the menu bar panel

/// An action as a row on a card: the symbol in a disc and the name next to it, as the tiles of the menu bar panel. The disc has the
/// colour of its group. Under the pointer only the row deepens; an action that is on, and the main one, has the disc filled with the
/// accent and says its name in it.
private struct ActionRow: View {
    let title: LocalizedStringKey
    let symbol: String
    let on: Bool
    @Environment(\.isEnabled) private var enabled
    @Environment(\.hoverEnabled) private var hoverEnabled
    @Environment(\.actionTone) private var tone
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(tone.fill)
                Circle().fill(Palette.indigo).opacity(on ? 1 : 0)
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
                    .fadingForeground(tone.ink, to: Color.white, when: on)
            }
            .frame(width: 30, height: 30)
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? Palette.indigo : Color.primary)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 6)
        .frame(height: 42)
        .background(shape.fill(Color.primary.opacity(hovering && enabled && hoverEnabled ? 0.07 : 0)))
        .contentShape(shape)
        .opacity(enabled ? 1 : 0.45)
        .onHover { hovering = $0 }
        .animation(.hover(hovering), value: hovering)
        .animation(.tandemFade, value: on)
    }
}

extension EnvironmentValues {
    /// Action buttons on a card of a group draw themselves as rows, as on the page of a device.
    @Entry var actionButtonsAsRows = false
    /// The colour of the discs of the actions in a group.
    @Entry var actionTone = Tone.indigo
    /// Round icon buttons in a row that share out its width as equal rounded keys, as under a device in the menu bar panel.
    @Entry var iconButtonsFill = false
}

/// The disc under an icon in the menu bar panel. A fill and not glass, because the panel is glass already, and the
/// same fill as the buttons with words there; it darkens a little under the pointer.
private struct RoundIconDisc: View {
    let symbol: String
    var tint: Color?
    var size: CGFloat
    var hovering: Bool
    var enabled: Bool
    @Environment(\.iconButtonsFill) private var fill

    var body: some View {
        // In a row that shares out its width (see `iconButtonsFill`) the disc becomes a rounded key as wide as its share.
        let shape = RoundedRectangle(cornerRadius: fill ? size * 0.36 : size / 2, style: .continuous)
        Image(systemName: symbol)
            .font(.system(size: size * 0.43, weight: .semibold))
            .contentTransition(.symbolEffect(.replace))
            .foregroundStyle(tint ?? Color.primary)
            .frame(width: fill ? nil : size, height: size)
            .frame(maxWidth: fill ? .infinity : nil)
            .background(shape.fill(Color.primary.opacity(hovering && enabled ? 0.18 : 0.1)))
            .overlay(shape.strokeBorder(Color.primary.opacity(0.06), lineWidth: 1))
            .contentShape(shape)
            .opacity(enabled ? 1 : 0.4)
            .animation(.hover(hovering), value: hovering)
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
        .buttonStyle(.bouncy)
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
    @Environment(\.iconButtonsFill) private var fill
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
        .fixedSize(horizontal: !fill, vertical: true)
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
    /// Not connected: the level is what it last said, so it is grey and does not say it charges.
    var connected = true
    /// False until it is on screen: the ring then fills from nothing and the number counts up, so a device that connects again
    /// brings its battery in, instead of a full ring that is suddenly there.
    @LocalState private var shown = false

    private var tint: Color {
        if !connected { return .gray }
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
                .trim(from: 0, to: shown ? arc : 0)
                .stroke(tint, style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.tandem, value: battery.level)
            VStack(spacing: 0) {
                Text("\(shown ? battery.level : 0)")
                    .font(.system(size: size * 0.28, weight: .bold, design: .rounded))
                    .contentTransition(.numericText(value: Double(shown ? battery.level : 0)))
                    .animation(.tandem, value: battery.level)
                if battery.charging && connected {
                    PulsingSymbol(name: "bolt.fill", pointSize: size * 0.16, color: .systemGreen)
                        .frame(width: size * 0.2, height: size * 0.2)
                }
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(shown ? 1 : 0.8)
        .opacity(shown ? 1 : 0)
        .animation(.tandemFade, value: tint)
        .onAppear { withAnimation(.tandem.delay(0.05)) { shown = true } }
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

/// Why a file ended without arriving, in the words of the person. The core gives a fixed code (R-V13, docs/TRANSFERS.md); anything
/// else is an older version talking, whose words are not shown as they are.
enum TransferReason {
    static func text(_ code: String?, incoming: Bool, peerName: String) -> String {
        switch code {
        case "cancelled": String(localized: "Cancelled")
        case "declined": String(localized: "Declined")
        case "expired": String(localized: "Expired")
        case "gone": incoming ? String(localized: "No longer on \(peerName)") : String(localized: "The file is no longer there")
        case "changed": incoming ? String(localized: "Changed on \(peerName) since it was sent") : String(localized: "Changed since it was sent")
        case "no space": incoming ? String(localized: "Not enough space on this Mac") : String(localized: "Not enough space on \(peerName)")
        case "connection lost": String(localized: "The connection with \(peerName) dropped")
        case "checksum failed": String(localized: "It arrived damaged. Send it again.")
        case "not saved": incoming ? String(localized: "Could not be saved on this Mac") : String(localized: "Could not be saved on \(peerName)")
        default: String(localized: "Failed")
        }
    }
}

/// How much of a transfer has a column of its own: nothing in a plain list, only how it stands in a narrow table, and the size, the
/// time and how it stands in a wide one.
enum TransferColumns { case none, state, all }

extension TransferReason {
    /// Why a transfer is not a file that is here, with the colour to say it in: it failed, or it arrived and is gone since.
    @MainActor
    static func of(_ item: TransferItem, peerName: String, model: EngineModel) -> (text: String, tint: Color)? {
        if item.state == .failed {
            return (text(item.error, incoming: item.incoming, peerName: peerName), Palette.urgent)
        }
        if item.state == .done, item.location != nil, !model.canOpen(item) { return (String(localized: "No longer there"), Color.secondary) }
        return nil
    }
}

/// How a kind of file looks in a row: the colour of its tile and the symbol on it.
extension SharedKind {
    var tone: Tone {
        switch self {
        case .pictures: .indigo
        case .videos: .rose
        case .documents: .mint
        case .audio: .peach
        case .all, .archives, .other: .plain
        }
    }

    var tileSymbol: String { self == .other || self == .all ? "doc" : symbol }
}

/// Why a row did not end as a file that is here, as a label at its end, directly before its three dots.
struct ReasonLabel: View {
    let text: String
    var tint: Color = Palette.urgent

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            // One line that may shrink: with a long name of a device it is cut before it runs past the card, and says the rest when
            // the pointer rests on it.
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 24)
            .background(tint.opacity(0.14), in: .capsule)
            .help(Text(verbatim: text))
    }
}

/// What a transfer looks like. Interaction lives in `TransferEntry`.
struct TransferRow: View {
    let item: TransferItem
    let peerName: String
    /// On the page Shared the size, the time and how it stands each have a column of their own.
    var columns = TransferColumns.none
    /// Chosen among others: the tile says so.
    var chosen = false
    @Environment(EngineModel.self) private var model

    /// The widths of the columns, shared with the head of the table.
    static let sizeWidth: CGFloat = 64
    static let whenWidth: CGFloat = 104
    static let stateWidth: CGFloat = 160

    var body: some View {
        HStack(spacing: 12) {
            leading
                .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                HStack(spacing: 4) {
                    Image(systemName: item.incoming ? "arrow.down" : "arrow.up").font(.system(size: 9, weight: .bold))
                    Text(detail)
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
            // The name keeps a little room of its own, so a long reason beside it cannot push it away altogether.
            .frame(minWidth: 96, maxWidth: .infinity, alignment: .leading)

            switch columns {
            case .all:
                column(item.total > 0 ? formatBytes(item.total) : "", width: Self.sizeWidth)
                column(when, width: Self.whenWidth)
                state.frame(width: Self.stateWidth, alignment: .leading)
            case .state:
                // Outside a column the reason has the room first and the name gives way.
                state.layoutPriority(1)
            case .none:
                if let reason {
                    ReasonLabel(text: reason.text, tint: reason.tint)
                        .layoutPriority(1)
                        .transition(.opacity)
                }
            }
        }
        .padding(.vertical, 6)
        .animation(.tandem, value: item.state)
        .animation(.tandemFade, value: chosen)
        .task(id: item.state == .done ? item.location : nil) { await loadThumbnail() }
        // A file that is here can be dragged out of the list, into Finder, a mail or any app that takes files (R-D13).
        .modifier(FileDrag(path: model.canOpen(item) ? model.filePath(item) : nil))
    }

    private func column(_ text: String, width: CGFloat) -> some View {
        Text(text)
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .frame(width: width, alignment: .leading)
    }

    /// How it stands, in the last column: the reason it is not here, otherwise how far it is or which way it went.
    @ViewBuilder
    private var state: some View {
        if let reason {
            ReasonLabel(text: reason.text, tint: reason.tint)
        } else {
            Text(item.state == .active ? "\(Int(item.fraction * 100))%" : (item.incoming ? String(localized: "Received") : String(localized: "Sent")))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    /// The line under the name: who it was with, and in a list without columns how big it is and when it was.
    private var detail: String {
        let with = item.incoming ? String(localized: "From \(peerName)") : String(localized: "To \(peerName)")
        if item.state == .active {
            return String(localized: "\(formatBytes(item.done)) of \(formatBytes(item.total))  \(formatSpeed(item.speed))")
        }
        if columns != .none { return with }
        let size = item.state == .done && item.total > 0 ? formatBytes(item.total) : nil
        return [with, size, when].compactMap { $0 }.joined(separator: " \u{00B7} ")
    }

    private var when: String { item.updated.formatted(.relative(presentation: .named)) }

    private var reason: (text: String, tint: Color)? { TransferReason.of(item, peerName: peerName, model: model) }

    @LocalState private var thumbnail: NSImage?

    /// The picture of the file when there is one to show, otherwise a tile that says what kind of file it is.
    @ViewBuilder
    private var leading: some View {
        let shape = RoundedRectangle(cornerRadius: 11, style: .continuous)
        if chosen {
            ZStack {
                shape.fill(Palette.indigo)
                Image(systemName: "checkmark").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
            }
            .transition(.scale(scale: 0.8).combined(with: .opacity))
        } else if let thumbnail {
            Image(nsImage: thumbnail)
                .resizable()
                .scaledToFill()
                .frame(width: 40, height: 40)
                .clipShape(shape)
        } else {
            let kind = SharedKind.of(item.name)
            ZStack {
                shape.fill(kind.tone.fill)
                Image(systemName: kind.tileSymbol)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(kind.tone.ink)
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

}

/// The name of a round button, shown the moment the pointer is on it, not after the wait of a tooltip.
///
/// The button only says where it is and what it is called; the window draws the name (`showsNames`). Drawn by the button itself
/// the name lay on the button, and under whatever comes after the button: the next row, the next card. In a window that draws no
/// names the button has the tooltip of the system.
struct InstantName: ViewModifier {
    let name: LocalizedStringKey
    @Environment(\.hoverEnabled) private var hoverEnabled
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.showsNames) private var hosted
    @LocalState private var hovering = false
    @LocalState private var id = UUID()

    func body(content: Content) -> some View {
        let shown = (hovering || DebugSupport.pointerEverywhere) && hoverEnabled && isEnabled
        Group {
            if hosted {
                content
                    .onHover { hovering = $0 }
                    .anchorPreference(key: ShownNames.self, value: .bounds) { shown ? [ShownName(id: id, name: name, bounds: $0)] : [] }
            } else {
                content.help(name)
            }
        }
        .accessibilityLabel(Text(name))
    }
}

/// A name the window has to draw: of which button, and where that button is.
struct ShownName: Equatable, Identifiable {
    let id: UUID
    let name: LocalizedStringKey
    let bounds: Anchor<CGRect>
}

struct ShownNames: PreferenceKey {
    static let defaultValue: [ShownName] = []
    static func reduce(value: inout [ShownName], nextValue: () -> [ShownName]) { value += nextValue() }
}

/// Puts a name under its button with a gap between them, in the middle of it, as on Linux and Windows. Near the edge of the
/// window it moves sideways to stay whole, and at the bottom it goes above the button. It never lies on the button it names.
private struct NamePlacement: Layout {
    let button: CGRect
    private static let gap: CGFloat = 6
    private static let edge: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let name = subviews.first else { return }
        let size = name.sizeThatFits(.unspecified)
        let x = max(Self.edge, min(button.midX - size.width / 2, bounds.width - size.width - Self.edge))
        let below = button.maxY + Self.gap + size.height <= bounds.height
        let y = below ? button.maxY + Self.gap : button.minY - size.height - Self.gap
        name.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + y), anchor: .topLeading, proposal: .unspecified)
    }
}

extension EnvironmentValues {
    /// This window draws the names of its round buttons (`showsNames`).
    @Entry var showsNames = false
}

extension View {
    func instantName(_ name: LocalizedStringKey) -> some View { modifier(InstantName(name: name)) }

    /// The names of the round buttons in this view are drawn here, over everything in it. For the whole of a window.
    func showsNames() -> some View {
        environment(\.showsNames, true)
            .overlayPreferenceValue(ShownNames.self) { names in
                GeometryReader { proxy in
                    ForEach(names) { item in
                        NamePlacement(button: proxy[item.bounds]) {
                            Text(item.name)
                                .font(.system(size: 11.5, weight: .medium))
                                .foregroundStyle(Color(nsColor: .windowBackgroundColor))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.primary.opacity(0.9), in: .rect(cornerRadius: 8, style: .continuous))
                                .fixedSize()
                        }
                        .transition(.opacity.combined(with: .offset(y: 3)))
                    }
                }
                .animation(.hover(!names.isEmpty), value: names.map(\.id))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
    }
}

/// The disc of a small round button at the end of a row: nothing at rest, a faint fill under the pointer, the same for a button
/// and for a menu.
private struct RowButtonDisc: View {
    let symbol: String
    let hovering: Bool
    var pressed = false
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.hoverEnabled) private var hoverEnabled

    /// One size for every round button of a row.
    static let size: CGFloat = 28

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(.secondary)
            .frame(width: Self.size, height: Self.size)
            .background(Circle().fill(Color.primary.opacity(pressed ? 0.16 : (hovering && isEnabled && hoverEnabled ? 0.09 : 0))))
            .contentShape(Circle())
            .opacity(isEnabled ? 1 : 0.4)
            .animation(.hover(hovering), value: hovering)
    }
}

/// A small button at the end of a row (pin, delete): the symbol in grey on a disc that fades in under the pointer, with its name
/// shown at once.
struct RowActionButton: View {
    let symbol: String
    let help: LocalizedStringKey
    let action: () -> Void
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) { RowButtonDisc(symbol: symbol, hovering: hovering) }
            .buttonStyle(.bouncy)
            .onHover { hovering = $0 }
            .instantName(help)
    }
}

/// The same, opening a menu: the three dots of a row.
struct RowMenuButton<Content: View>: View {
    var symbol = "ellipsis"
    let help: LocalizedStringKey
    @ViewBuilder var content: Content
    @LocalState private var hovering = false

    var body: some View {
        Menu {
            content
        } label: {
            RowButtonDisc(symbol: symbol, hovering: hovering)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .onHover { hovering = $0 }
        .instantName(help)
    }
}

/// A transfer you can act on: a click opens the file, a right click offers the rest,
/// and Delete takes it off the list while the row has the keyboard focus. A row of a
/// card, like the rows of the clipboard and the notifications.
struct TransferEntry: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem
    let peerName: String
    /// Chosen among others on the page Shared, which then acts on all of them at once.
    var chosen = false
    /// What a click does instead of opening, for a page where rows can be chosen; nil opens.
    var onTap: (() -> Void)?
    /// What the second click of a double click does, for a page where a click only picks the row; nil leaves it at the two clicks.
    var onDoubleTap: (() -> Void)?
    /// As a line of the table on the page Shared.
    var columns = TransferColumns.none
    /// The row whose file is shown beside the list.
    var picked = false
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Metrics.cardInner, style: .continuous)
        HStack(spacing: 6) {
            TransferRow(item: item, peerName: peerName, columns: columns, chosen: chosen)
            // Everything that can be done with it, where it can be seen: the same as the right click.
            RowMenuButton(help: "More") { TransferMenu(item: item) }
        }
        .padding(.horizontal, 10)
        .hoverHighlight(radius: Metrics.cardInner, tint: focused || chosen || picked ? Palette.indigo : .primary, selected: focused || chosen || picked)
        .contentShape(shape)
        .onTapGesture {
            focused = true
            if let onTap { onTap() } else { model.open(item) }
        }
        // Beside the single click and not before it: the first click acts at once, and a second one in time is the double click. As
        // a gesture of its own it does not depend on which click the app says is being handled when the single one is called.
        .simultaneousGesture(TapGesture(count: 2).onEnded { onDoubleTap?() }, including: onDoubleTap == nil ? .subviews : .all)
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
}

/// The right-click menu of a transfer.
struct TransferMenu: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem

    var body: some View {
        let available = model.canOpen(item)
        // While it runs the one thing to do is stop it.
        if item.state == .active {
            Button("Cancel transfer", systemImage: "xmark", role: .destructive) { model.cancelTransfer(item) }
            Divider()
        }
        Button("Open", systemImage: "arrow.up.forward.square") { model.open(item) }
            .disabled(!available)
        Button("Open File Location", systemImage: "folder") { model.reveal(item) }
            .disabled(!available)
        Button("Copy path", systemImage: "doc.on.doc") { model.copyPath(item) }
            .disabled(item.location == nil)
        Divider()
        Button("Remove from List", systemImage: "minus.circle") {
            withAnimation(.tandem) { model.removeFromList([item.id]) }
        }
        .disabled(item.state == .active)
        // Only what arrived on this Mac. A file that was sent is the person's own original.
        if item.incoming {
            Button("Move File to Trash…", systemImage: "trash", role: .destructive) { model.requestTrash(item) }
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

/// The toast floats over the content, so it is glass. The same capsule in the window and at the bottom of the screen.
struct ToastView: View {
    let text: String
    var symbol: String?

    var body: some View {
        HStack(spacing: 9) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Palette.indigo)
            }
            Text(text)
                .font(.callout.weight(.medium))
                .lineLimit(2)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 11)
        .floatingGlass(in: .capsule)
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }
}

/// Lets a row be dragged as the file at `path`, and only then: a row without a file starts no empty drag.
struct FileDrag: ViewModifier {
    let path: String?

    func body(content: Content) -> some View {
        if let path {
            content.onDrag { Self.provider(for: URL(fileURLWithPath: path)) }
        } else {
            content
        }
    }

    /// The contents of the file, not its address: with the address Finder moves a file it drops on the same disk, and the file would be
    /// gone from Downloads with the row pointing at nothing. Handed over as contents, the place of the drop always gets a copy.
    static func provider(for url: URL) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = url.lastPathComponent
        let type = (try? url.resourceValues(forKeys: [.contentTypeKey]))?.contentType ?? .data
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { done in
            done(url, false, nil)
            return nil
        }
        return provider
    }
}
