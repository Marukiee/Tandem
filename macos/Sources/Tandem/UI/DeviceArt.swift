import SwiftUI
import TandemCore

/// What a device is, for the drawing of it.
enum DeviceKind: Hashable {
    case phone, tablet, laptop, desktop, watch, tv

    /// From the icon it has: the one the person picked, otherwise the one of its system.
    init(symbol: String) {
        switch symbol {
        case "iphone": self = .phone
        case "ipad": self = .tablet
        case "laptopcomputer": self = .laptop
        case "applewatch": self = .watch
        case "tv": self = .tv
        default: self = .desktop
        }
    }
}

/// One part of the drawing, in a box of 64 by 72 that is scaled to the room it gets. The same shapes as on Linux and Windows.
private struct DeviceArtPart: Shape {
    enum Part { case body, lines, dots }
    let kind: DeviceKind
    let part: Part

    func path(in rect: CGRect) -> Path {
        var path = Path()
        func box(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat, _ radius: CGFloat) {
            path.addRoundedRect(in: CGRect(x: x, y: y, width: width, height: height), cornerSize: CGSize(width: radius, height: radius), style: .continuous)
        }
        func line(_ points: (CGFloat, CGFloat)...) {
            path.move(to: CGPoint(x: points[0].0, y: points[0].1))
            for point in points.dropFirst() { path.addLine(to: CGPoint(x: point.0, y: point.1)) }
        }
        switch (kind, part) {
        case (.phone, .body): box(13, 2, 38, 68, 9)
        case (.phone, .dots): path.addEllipse(in: CGRect(x: 30.2, y: 7.7, width: 3.6, height: 3.6))
        case (.tablet, .body): box(7, 6, 50, 60, 7)
        case (.tablet, .dots): path.addEllipse(in: CGRect(x: 30.4, y: 10.9, width: 3.2, height: 3.2))
        case (.laptop, .body): box(9, 14, 46, 34, 5)
        case (.laptop, .lines): line((3, 56), (61, 56))
        case (.desktop, .body): box(5, 9, 54, 38, 5)
        case (.desktop, .lines):
            line((32, 47), (32, 57))
            line((21, 59), (43, 59))
        case (.watch, .body): box(18, 19, 28, 34, 9)
        case (.watch, .lines):
            line((24, 19), (26, 7), (38, 7), (40, 19))
            line((24, 53), (26, 65), (38, 65), (40, 53))
            line((49, 32), (49, 39))
        case (.tv, .body): box(3, 12, 58, 37, 5)
        case (.tv, .lines):
            line((22, 49), (17, 59))
            line((42, 49), (47, 59))
        default: break
        }
        let scale = min(rect.width / 64, rect.height / 72)
        return path
            .applying(CGAffineTransform(scaleX: scale, y: scale))
            .offsetBy(dx: rect.midX - 32 * scale, dy: rect.midY - 36 * scale)
    }
}

/// A device drawn as what it is: an outline, filled softly from indigo to rose while it is there and grey while it is not.
struct DeviceDrawing: View {
    let kind: DeviceKind
    var lit: Bool
    var height: CGFloat = 84

    var body: some View {
        let line = max(1.4, height / 72 * 2.5)
        let ink = lit ? Color.primary : Color.primary.opacity(0.35)
        ZStack {
            DeviceArtPart(kind: kind, part: .body).fill(Color(hex: 0x7F7F8C, opacity: 0.14))
            DeviceArtPart(kind: kind, part: .body)
                .fill(LinearGradient(
                    colors: [
                        Color(light: 0x5B5BD6, dark: 0x7878EB, lightOpacity: 0.30, darkOpacity: 0.36),
                        Color(light: 0xFFB0CB, dark: 0xFF8CB4, lightOpacity: 0.42, darkOpacity: 0.22),
                    ],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                ))
                .opacity(lit ? 1 : 0)
            DeviceArtPart(kind: kind, part: .body).stroke(ink, style: StrokeStyle(lineWidth: line, lineJoin: .round))
            DeviceArtPart(kind: kind, part: .lines).stroke(ink, style: StrokeStyle(lineWidth: line, lineCap: .round, lineJoin: .round))
            DeviceArtPart(kind: kind, part: .dots).fill(ink)
        }
        .frame(width: height * 64 / 72, height: height)
        .animation(.tandemFade, value: lit)
        .accessibilityHidden(true)
    }
}

/// The drawing of a device at the head of its page. It follows the icon the person picked for it.
struct DeviceArt: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    var height: CGFloat = 84

    var body: some View {
        let kind = DeviceKind(symbol: model.deviceIcons[device.id] ?? device.platform.symbol)
        // Another icon is another drawing: the old one shrinks away as the new one comes.
        ZStack {
            DeviceDrawing(kind: kind, lit: device.online, height: height)
                .id(kind)
                .transition(.scale(scale: 0.86).combined(with: .opacity))
        }
        .animation(.tandemSpringy, value: kind)
    }
}
