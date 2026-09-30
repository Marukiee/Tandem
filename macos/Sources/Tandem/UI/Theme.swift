import SwiftUI

/// `@State` is a macro in the newest SDK, and its compiler plugin ships only with the
/// full Xcode app. The property wrapper underneath is still there, so this alias
/// reaches it without needing Xcode to build the app.
typealias LocalState<Value> = SwiftUI.State<Value>

/// The same two hues as MarkMaaktAI: a cool indigo, and a warm rose that only
/// appears when something needs to stand out.
enum Palette {
    static let indigo = Color(hex: 0x5B5BD6)
    static let indigoLight = Color(hex: 0x9E9DF0)
    static let indigoDeep = Color(hex: 0x2E2C6B)
    static let rose = Color(hex: 0xFFB0CB)
    static let roseDeep = Color(hex: 0x934464)
    static let urgent = Color(hex: 0xE8613C)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

/// One place for how things move, so the whole app moves the same way.
///
/// Anything that changes position or size uses a spring, because a spring carries
/// momentum and reads as a physical object being moved. Anything that only changes
/// colour or opacity uses a tween, because a bouncing colour looks like a bug.
extension Animation {
    /// Movement without overshoot: panels sliding, lists reordering.
    static let tandem = Animation.spring(response: 0.45, dampingFraction: 0.86)
    /// Movement that is allowed a little overshoot: press feedback, things arriving.
    static let tandemSpringy = Animation.spring(response: 0.5, dampingFraction: 0.68)
    /// The loosest one, kept for taps only.
    static let tandemBouncy = Animation.spring(response: 0.38, dampingFraction: 0.55)
    /// Colour and opacity.
    static let tandemFade = Animation.easeOut(duration: 0.22)
}

/// How far a pressed element shrinks. Small enough to feel, not to distract.
let pressedScale: CGFloat = 0.96

/// Corner radii that nest. A shape inside another one gets the outer radius minus the
/// gap between them, so the two curves stay parallel instead of looking pinched.
enum Metrics {
    static let card: CGFloat = 26
    static let cardInset: CGFloat = 10

    static func inner(_ outer: CGFloat, inset: CGFloat) -> CGFloat { max(4, outer - inset) }
    static var cardInner: CGFloat { inner(card, inset: cardInset) }
}

extension View {
    /// The space around a page. The toolbar already keeps the content clear of the window
    /// edge, so the top only needs a little on top of that, or the gap reads as wasted.
    func pagePadding() -> some View {
        padding(.horizontal, 26).padding(.bottom, 26).padding(.top, 4)
    }
}

// MARK: Surfaces

/// The surface content sits on. Glass floats above the content (toolbar, controls, the
/// toast), so the content itself gets a plain fill and never glass.
struct Surface: ViewModifier {
    var radius: CGFloat = Metrics.card
    var tint: Color?

    func body(content: Content) -> some View {
        content.background {
            let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
            shape
                .fill(Color(nsColor: .controlBackgroundColor))
                .overlay { if let tint { shape.fill(tint.opacity(0.10)) } }
                .overlay { shape.strokeBorder(Color.primary.opacity(0.06), lineWidth: 1) }
        }
    }
}

extension View {
    func surface(radius: CGFloat = Metrics.card, tint: Color? = nil) -> some View {
        modifier(Surface(radius: radius, tint: tint))
    }
}

// MARK: Hover

private struct HoverEnabledKey: EnvironmentKey { static let defaultValue = true }

extension EnvironmentValues {
    /// False while a sheet or dialog covers the window, so nothing behind it reacts to the pointer.
    var hoverEnabled: Bool {
        get { self[HoverEnabledKey.self] }
        set { self[HoverEnabledKey.self] = newValue }
    }
}

/// A card or button that rises a little under the pointer. The spring is the same one
/// used everywhere else. With Reduce Motion on, nothing moves and only the shadow
/// changes.
struct HoverLift: ViewModifier {
    var scale: CGFloat = 1.015
    var lift: CGFloat = 2
    var enabled = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    func body(content: Content) -> some View {
        let active = hovering && enabled && hoverEnabled
        content
            .scaleEffect(active && !reduceMotion ? scale : 1)
            .offset(y: active && !reduceMotion ? -lift : 0)
            .shadow(color: .black.opacity(active ? 0.10 : 0), radius: active ? 14 : 0, y: active ? 7 : 0)
            .onHover { hovering = $0 }
            .animation(reduceMotion ? .tandemFade : .tandemSpringy, value: active)
    }
}

/// A control that swells a little under the pointer, with no shadow. For glass buttons, which
/// do not react to the pointer in a menu bar panel by themselves.
struct HoverSwell: ViewModifier {
    var scale: CGFloat = 1.06
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(hovering && hoverEnabled && !reduceMotion ? scale : 1)
            .onHover { hovering = $0 }
            .animation(.tandemSpringy, value: hovering)
    }
}

/// A soft highlight behind a row while the pointer is over it. Colour only, so a
/// tween is right, and it stays put under Reduce Motion.
struct HoverHighlight: ViewModifier {
    var radius: CGFloat = 12
    var tint: Color = .primary
    var selected = false

    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(tint.opacity(selected ? 0.14 : (hovering && hoverEnabled ? 0.075 : 0)))
            }
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .onHover { hovering = $0 }
            .animation(.tandemFade, value: hovering)
            .animation(.tandemFade, value: selected)
    }
}

/// Hands the hover state to a view that wants to react in more than one place, such
/// as a row whose icon nudges while its background lights up.
struct Hoverable<Content: View>: View {
    @ViewBuilder var content: (Bool) -> Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        content(hovering && hoverEnabled).onHover { hovering = $0 }
    }
}

extension View {
    func hoverLift(scale: CGFloat = 1.015, lift: CGFloat = 2, enabled: Bool = true) -> some View {
        modifier(HoverLift(scale: scale, lift: lift, enabled: enabled))
    }

    func hoverSwell(_ scale: CGFloat = 1.06) -> some View {
        modifier(HoverSwell(scale: scale))
    }

    func hoverHighlight(radius: CGFloat = 12, tint: Color = .primary, selected: Bool = false) -> some View {
        modifier(HoverHighlight(radius: radius, tint: tint, selected: selected))
    }
}

// MARK: Button styles

/// Shrinks a little while pressed and springs back, and swells a little under the
/// pointer, on everything tappable that is not a system glass button.
struct BouncyButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        BouncyButtonBody(configuration: configuration)
    }
}

private struct BouncyButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : (hovering && hoverEnabled && isEnabled && !reduceMotion ? 1.06 : 1))
            .onHover { hovering = $0 }
            .animation(.tandemBouncy, value: configuration.isPressed)
            .animation(.tandemSpringy, value: hovering)
    }
}

/// A small round icon button: a faint disc appears under the pointer.
struct IconButtonStyle: ButtonStyle {
    var size: CGFloat = 28

    func makeBody(configuration: Configuration) -> some View {
        IconButtonBody(configuration: configuration, size: size)
    }
}

private struct IconButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let size: CGFloat
    @Environment(\.isEnabled) private var isEnabled
    @LocalState private var hovering = false

    var body: some View {
        configuration.label
            .frame(width: size, height: size)
            .background(Circle().fill(Color.primary.opacity(configuration.isPressed ? 0.16 : (hovering && isEnabled ? 0.09 : 0))))
            .contentShape(Circle())
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .onHover { hovering = $0 }
            .animation(.tandemFade, value: hovering)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == BouncyButtonStyle {
    static var bouncy: BouncyButtonStyle { BouncyButtonStyle() }
}

extension ButtonStyle where Self == IconButtonStyle {
    static var icon: IconButtonStyle { IconButtonStyle() }
    static func icon(size: CGFloat) -> IconButtonStyle { IconButtonStyle(size: size) }
}

/// Formats a byte count the way a person reads it.
func formatBytes(_ bytes: UInt64) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
}

func formatSpeed(_ bytesPerSecond: Double) -> String {
    guard bytesPerSecond > 1 else { return "" }
    return ByteCountFormatter.string(fromByteCount: Int64(bytesPerSecond), countStyle: .file) + "/s"
}
