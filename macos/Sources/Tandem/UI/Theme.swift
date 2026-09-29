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

/// Shrinks a little while pressed and springs back, on everything tappable.
struct BouncyButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == BouncyButtonStyle {
    static var bouncy: BouncyButtonStyle { BouncyButtonStyle() }
}

/// Formats a byte count the way a person reads it.
func formatBytes(_ bytes: UInt64) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
}

func formatSpeed(_ bytesPerSecond: Double) -> String {
    guard bytesPerSecond > 1 else { return "" }
    return ByteCountFormatter.string(fromByteCount: Int64(bytesPerSecond), countStyle: .file) + "/s"
}
