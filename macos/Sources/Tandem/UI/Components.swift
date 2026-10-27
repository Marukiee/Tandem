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

/// The title of a page and the line under it, the same on every page that has one.
struct PageHeader<Extra: View>: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    @ViewBuilder var extra: Extra

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.largeTitle.weight(.bold))
            Text(subtitle)
                .font(.callout)
                .foregroundStyle(.secondary)
            extra
        }
    }
}

extension PageHeader where Extra == EmptyView {
    init(title: LocalizedStringKey, subtitle: LocalizedStringKey) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
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

    var body: some View {
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
                    .animation(.tandemFade, value: lit)
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

/// Views side by side, each as wide as it needs to be, on to the next line when the row is full.
struct WrapRow: Layout {
    var spacing: CGFloat = 10

    private struct Line {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func lines(_ subviews: Subviews, fitting width: CGFloat) -> [Line] {
        var result: [Line] = [Line()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = (result[result.count - 1].indices.isEmpty ? 0 : spacing) + size.width
            if !result[result.count - 1].indices.isEmpty, result[result.count - 1].width + needed > width {
                result.append(Line())
            }
            let first = result[result.count - 1].indices.isEmpty
            result[result.count - 1].width += (first ? 0 : spacing) + size.width
            result[result.count - 1].height = max(result[result.count - 1].height, size.height)
            result[result.count - 1].indices.append(index)
        }
        return result
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = lines(subviews, fitting: width)
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: width.isFinite ? width : (rows.map(\.width).max() ?? 0), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in lines(subviews, fitting: bounds.width) {
            var x = bounds.minX
            for index in line.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y + (line.height - size.height) / 2), anchor: .topLeading, proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + spacing
        }
    }
}

// MARK: Round icon buttons for the menu bar panel

/// The disc under an icon in the menu bar panel. A fill and not glass, because the panel is glass already, and the
/// same fill as the buttons with words there; it darkens a little under the pointer.
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
            .background(Circle().fill(Color.primary.opacity(hovering && enabled ? 0.18 : 0.1)))
            .overlay(Circle().strokeBorder(Color.primary.opacity(0.06), lineWidth: 1))
            .contentShape(Circle())
            .opacity(enabled ? 1 : 0.4)
            .animation(.tandemFade, value: hovering)
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
    /// False until it is on screen: the ring then fills from nothing and the number counts up, so a device that connects again
    /// brings its battery in, instead of a full ring that is suddenly there.
    @LocalState private var shown = false

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
                .trim(from: 0, to: shown ? arc : 0)
                .stroke(tint, style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.tandem, value: battery.level)
            VStack(spacing: 0) {
                Text("\(shown ? battery.level : 0)")
                    .font(.system(size: size * 0.28, weight: .bold, design: .rounded))
                    .contentTransition(.numericText(value: Double(shown ? battery.level : 0)))
                    .animation(.tandem, value: battery.level)
                if battery.charging {
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

/// What a transfer looks like. Interaction lives in `TransferEntry`.
struct TransferRow: View {
    let item: TransferItem
    let peerName: String
    @Environment(EngineModel.self) private var model

    var body: some View {
        HStack(spacing: 12) {
            leading
                .frame(width: 40, height: 40)

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

/// A small button at the end of a row (pin, delete): the bare symbol in grey, at the size the lists of the system use, with no plate
/// behind it, and its name as a tooltip.
struct RowActionButton: View {
    let symbol: String
    let help: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .medium))
                .frame(width: 24, height: 24)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.secondary)
        .help(help)
        .accessibilityLabel(Text(help))
    }
}

/// The same, opening a menu: the three dots of a row.
struct RowMenuButton<Content: View>: View {
    var symbol = "ellipsis"
    let help: LocalizedStringKey
    @ViewBuilder var content: Content

    var body: some View {
        Menu {
            content
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .medium))
                .frame(width: 24, height: 24)
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.borderless)
        .menuIndicator(.hidden)
        .foregroundStyle(.secondary)
        .fixedSize()
        .help(help)
        .accessibilityLabel(Text(help))
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
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Metrics.cardInner, style: .continuous)
        HStack(spacing: 6) {
            TransferRow(item: item, peerName: peerName)
            // The three dots of the Android app: everything the right click offers, where it can be seen.
            RowMenuButton(help: "More") { TransferMenu(item: item) }
        }
        .padding(.horizontal, 10)
        .hoverHighlight(radius: Metrics.cardInner, tint: focused || chosen ? Palette.indigo : .primary, selected: focused || chosen)
        .contentShape(shape)
        .onTapGesture {
            focused = true
            if let onTap { onTap() } else { model.open(item) }
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
}

/// The right-click menu of a transfer.
struct TransferMenu: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem

    var body: some View {
        let available = model.canOpen(item)
        // While it runs the one thing to do is stop it.
        if item.state == .active {
            Button("Cancel transfer", role: .destructive) { model.cancelTransfer(item) }
            Divider()
        }
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
