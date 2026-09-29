import SwiftUI
import TandemCore

// MARK: Glass

/// A floating glass card. Glass is for what floats above the content (cards, the
/// action row, the toast), never for the content itself.
struct GlassCard<Content: View>: View {
    var radius: CGFloat = 28
    var padding: CGFloat = 20
    var tint: Color? = nil
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .glassEffect(tint.map { Glass.regular.tint($0.opacity(0.18)) } ?? .regular, in: .rect(cornerRadius: radius))
    }
}

/// A capsule button on glass, used for the actions on a device.
struct GlassActionButton: View {
    let title: LocalizedStringKey
    let symbol: String
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.callout.weight(.semibold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
        }
        .buttonStyle(.bouncy)
        .modifier(GlassCapsule(prominent: prominent))
    }
}

private struct GlassCapsule: ViewModifier {
    let prominent: Bool

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .foregroundStyle(prominent ? Color.white : Color.primary)
            .glassEffect(
                prominent ? Glass.regular.tint(Palette.indigo).interactive() : Glass.regular.interactive(),
                in: .capsule
            )
    }
}

// MARK: Device visuals

extension TandemPlatform {
    var symbol: String {
        switch self {
        case .android: "candybarphone"
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

struct DeviceGlyph: View {
    let platform: TandemPlatform
    var online: Bool
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: platform.symbol)
            .font(.system(size: size * 0.46, weight: .semibold))
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(online ? Palette.indigo : Color.secondary)
            .frame(width: size, height: size)
            .glassEffect(.regular, in: .circle)
            .animation(.tandemFade, value: online)
    }
}

/// A dot that breathes while the device is online.
struct PresenceDot: View {
    let online: Bool
    @LocalState private var breathe = false

    var body: some View {
        Circle()
            .fill(online ? Color.green : Color.secondary.opacity(0.5))
            .frame(width: 8, height: 8)
            .overlay {
                if online {
                    Circle()
                        .stroke(Color.green.opacity(0.45), lineWidth: 2)
                        .scaleEffect(breathe ? 2.1 : 1)
                        .opacity(breathe ? 0 : 1)
                }
            }
            .onAppear {
                withAnimation(.easeOut(duration: 1.8).repeatForever(autoreverses: false)) { breathe = true }
            }
            .animation(.tandemFade, value: online)
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

    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.08), lineWidth: size * 0.11)
            Circle()
                .trim(from: 0, to: CGFloat(battery.level) / 100)
                .stroke(tint, style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.tandem, value: battery.level)
            VStack(spacing: 0) {
                Text("\(battery.level)")
                    .font(.system(size: size * 0.28, weight: .bold, design: .rounded))
                    .contentTransition(.numericText(value: Double(battery.level)))
                    .animation(.tandem, value: battery.level)
                if battery.charging {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: size * 0.16))
                        .foregroundStyle(.green)
                        .symbolEffect(.pulse)
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
        TimelineView(.animation) { context in
            let cycle = 2.6
            let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
            // Two eased turns, with a breath between them.
            let turn: Double = t < 0.42 ? ease(t / 0.42) : (t < 0.5 ? 1 : (t < 0.92 ? 1 + ease((t - 0.5) / 0.42) : 2))
            Capsule()
                .fill(Palette.indigo.gradient)
                .frame(width: size * 0.42, height: size)
                .rotationEffect(.degrees(turn * 180 + 35))
        }
        .frame(width: size, height: size)
    }

    private func ease(_ x: Double) -> Double {
        x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
    }
}

/// A slow ambient glow behind the content. It only moves while something is being
/// transferred, because an animation that always runs stops meaning anything.
struct AmbientBackdrop: View {
    let active: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: active ? 1 / 30 : 1, paused: !active)) { context in
            let t = active ? context.date.timeIntervalSinceReferenceDate : 0
            Canvas { canvas, size in
                let blobs: [(Color, Double, Double, Double)] = [
                    (Palette.indigo, 0.25, 0.20, 0.0),
                    (Palette.rose, 0.85, 0.85, 2.1),
                    (Palette.indigoLight, 0.75, 0.10, 4.2),
                ]
                for (color, fx, fy, phase) in blobs {
                    let wobble = 0.05
                    let x = (fx + sin(t / 5 + phase) * wobble) * size.width
                    let y = (fy + cos(t / 6 + phase) * wobble) * size.height
                    let radius = max(size.width, size.height) * 0.45
                    let rect = CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
                    canvas.fill(
                        Path(ellipseIn: rect),
                        with: .radialGradient(
                            Gradient(colors: [color.opacity(active ? 0.20 : 0.10), color.opacity(0)]),
                            center: CGPoint(x: x, y: y),
                            startRadius: 0,
                            endRadius: radius
                        )
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .animation(.tandemFade, value: active)
    }
}

// MARK: Transfers

struct TransferRow: View {
    let item: TransferItem
    let peerName: String

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(iconTint.opacity(0.16))
                Image(systemName: symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(iconTint)
                    .contentTransition(.symbolEffect(.replace))
            }
            .frame(width: 34, height: 34)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Group {
                    switch item.state {
                    case .active:
                        Text("\(formatBytes(item.done)) of \(formatBytes(item.total))  \(formatSpeed(item.speed))")
                    case .done:
                        Text(item.incoming ? "From \(peerName)" : "To \(peerName)")
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

            if item.state == .done, item.incoming, let location = item.location {
                Button {
                    NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: location)])
                } label: {
                    Image(systemName: "magnifyingglass").font(.callout)
                }
                .buttonStyle(.bouncy)
                .help("Show in Finder")
            }
        }
        .padding(.vertical, 6)
        .animation(.tandem, value: item.state)
    }

    private var symbol: String {
        switch item.state {
        case .active: item.incoming ? "arrow.down" : "arrow.up"
        case .done: "checkmark"
        case .failed: "exclamationmark"
        }
    }

    private var iconTint: Color {
        switch item.state {
        case .active: Palette.indigo
        case .done: .green
        case .failed: Palette.urgent
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
        .buttonStyle(.plain)
        .popover(isPresented: $shown) {
            Text(text).font(.callout).padding(14).frame(width: 260, alignment: .leading)
        }
    }
}
