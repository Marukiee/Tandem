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

/// How much of the text colour a row takes on under the pointer, in every list.
let hoverOpacity: Double = 0.06

/// Corner radii that nest. A shape inside another one gets the outer radius minus the
/// gap between them, so the two curves stay parallel instead of looking pinched.
enum Metrics {
    /// Every card of content on a page, whatever it holds.
    static let card: CGFloat = 26
    static let cardInset: CGFloat = 10
    /// Everything that floats over other apps: a question at the top of the screen, a card in a corner, the quick panel.
    static let panel: CGFloat = 26

    static func inner(_ outer: CGFloat, inset: CGFloat) -> CGFloat { max(4, outer - inset) }
    /// A row inside a card: its highlight, its selection.
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

// MARK: Glass

private struct OnGlassKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    /// True on something that is glass itself: a floating panel, a prompt, the menu bar panel. The controls on it are drawn
    /// as fills there and not as glass of their own, because glass on glass blurs twice and loses its edge.
    var onGlass: Bool {
        get { self[OnGlassKey.self] }
        set { self[OnGlassKey.self] = newValue }
    }
}

extension View {
    /// The glass of something that floats, and the word to what is on it that it sits on glass. A debug run with
    /// `TANDEM_DEBUG_FLAT_GLASS` draws a material instead, because a snapshot cannot draw glass.
    @ViewBuilder
    func floatingGlass<S: Shape>(in shape: S) -> some View {
        if DebugSupport.flatGlass {
            environment(\.onGlass, true).background(.regularMaterial, in: shape)
        } else {
            environment(\.onGlass, true).glassEffect(.regular, in: shape)
        }
    }

    /// For a view that is on glass that it does not draw itself, such as the menu bar panel, whose glass is the system's.
    func onGlassSurface() -> some View {
        environment(\.onGlass, true)
    }
}

/// The card that floats over other apps: the questions at the top of the screen and their kin. One width, radius, padding and shadow for
/// all of them, and room around the glass inside the window for that shadow: a shadow that reaches past the edge of a window is cut off
/// in a straight line. The panels that place these count on that room (12 above, 36 under).
struct FloatingCard: ViewModifier {
    var width: CGFloat = 440

    func body(content: Content) -> some View {
        content
            .padding(18)
            .frame(width: width)
            .floatingGlass(in: RoundedRectangle(cornerRadius: Metrics.panel, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
            .padding(EdgeInsets(top: 12, leading: 28, bottom: 36, trailing: 28))
    }
}

extension View {
    func floatingCard(width: CGFloat = 440) -> some View {
        modifier(FloatingCard(width: width))
    }
}

/// The top of a floating card: what it is about as a symbol on a disc, the question, and a line of explanation.
struct PromptHeader: View {
    let symbol: String
    var tint: Color = Palette.indigo
    let title: Text
    let message: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(tint.opacity(0.14), in: .circle)
            VStack(alignment: .leading, spacing: 4) {
                title.font(.headline)
                message
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
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

// What the pointer does, everywhere: nothing grows or moves under it, as in the system's own apps. A row or a button only takes on a
// little colour, and that colour fades in and out.

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
                    .fill(tint.opacity(selected ? 0.16 : (hovering && hoverEnabled ? hoverOpacity : 0)))
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

/// A colour of words or of a symbol that changes with the pointer or a choice. SwiftUI swaps the colour of text and symbols in one frame
/// however it is animated, which reads as a hard jump, so the second colour is a copy on top that fades in and out.
struct FadingForeground<Base: ShapeStyle, Other: ShapeStyle>: ViewModifier {
    let base: Base
    let other: Other
    let on: Bool

    func body(content: Content) -> some View {
        content
            .foregroundStyle(base)
            .overlay {
                content
                    .foregroundStyle(other)
                    .opacity(on ? 1 : 0)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .animation(.tandemFade, value: on)
    }
}

extension View {
    func hoverHighlight(radius: CGFloat = 12, tint: Color = .primary, selected: Bool = false) -> some View {
        modifier(HoverHighlight(radius: radius, tint: tint, selected: selected))
    }

    func fadingForeground(_ base: some ShapeStyle, to other: some ShapeStyle, when on: Bool) -> some View {
        modifier(FadingForeground(base: base, other: other, on: on))
    }
}

// MARK: Button styles

/// Shrinks a little while pressed and springs back, on everything tappable that is not a
/// button of its own kind.
struct BouncyButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

/// A small round icon button: a faint disc fades in under the pointer.
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
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        configuration.label
            .frame(width: size, height: size)
            .background(Circle().fill(Color.primary.opacity(configuration.isPressed ? 0.16 : (hovering && isEnabled && hoverEnabled ? 0.09 : 0))))
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
