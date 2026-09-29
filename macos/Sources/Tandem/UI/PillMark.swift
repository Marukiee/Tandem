import AppKit
import SwiftUI

/// The geometry of the two pills in the app icon (scripts/make-icon.swift), in the
/// units of its 1024 point canvas. Every drawing of the mark reads from here, so the
/// lean and the spacing cannot drift apart from the icon again.
enum PillArt {
    static let length: CGFloat = 470
    static let width: CGFloat = 170
    /// The icon rotates counter-clockwise in a y-up context, so the top of each pill
    /// leans to the left, like a backslash. SwiftUI rotates the other way round.
    static let leanDegrees: Double = 32
    /// Distance of each pill from the centre of the pair, sideways.
    static let spread: CGFloat = 78
    /// Width and height of the pair, rotated. The mark is scaled to fit this.
    static let pairWidth: CGFloat = 550
    /// The plate the icon sits on inside its canvas.
    static let plateSide: CGFloat = 824
    static let plateRadius: CGFloat = 186
}

/// The two pills of the app icon: a light one on the left, a rose one on the right,
/// centred as a pair. `.plate` draws the rounded square behind them like the icon.
struct PillMark: View {
    enum Style { case plain, plate }

    var size: CGFloat = 28
    var style: Style = .plain

    private var unit: CGFloat {
        style == .plate ? size / PillArt.plateSide : size / (PillArt.pairWidth + 10)
    }

    var body: some View {
        ZStack {
            if style == .plate { plate }
            // The rose pill first: in the icon the light one lies over it.
            pill(bottom: Color(hex: 0xFFB0CB), top: Color(hex: 0xE8779F))
                .offset(x: PillArt.spread * unit)
            pill(bottom: Color(hex: 0xFFFFFF), top: Color(hex: 0xC9C8FA))
                .offset(x: -PillArt.spread * unit)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private func pill(bottom: Color, top: Color) -> some View {
        let shape = Capsule()
        return shape
            .fill(LinearGradient(colors: [top, bottom], startPoint: .top, endPoint: .bottom))
            .overlay(alignment: .top) {
                LinearGradient(colors: [.white.opacity(0.35), .white.opacity(0)], startPoint: .top, endPoint: .bottom)
                    .frame(height: PillArt.length * unit / 2)
                    .clipShape(shape)
            }
            .overlay {
                // A hairline so the light pill still reads on a light window.
                if style == .plain { shape.strokeBorder(Palette.indigo.opacity(0.22), lineWidth: max(0.5, 3 * unit)) }
            }
            .frame(width: PillArt.width * unit, height: PillArt.length * unit)
            .rotationEffect(.degrees(-PillArt.leanDegrees))
            .shadow(color: .black.opacity(style == .plate ? 0.28 : 0.16), radius: 22 * unit, y: 10 * unit)
    }

    private var plate: some View {
        let radius = PillArt.plateRadius * unit
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return shape
            .fill(LinearGradient(
                colors: [Color(hex: 0x7A79EE), Color(hex: 0x4A49C4), Color(hex: 0x2E2C6B)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ))
            .overlay(alignment: .top) {
                LinearGradient(colors: [.white.opacity(0.22), .white.opacity(0)], startPoint: .top, endPoint: .bottom)
                    .frame(height: PillArt.plateSide * unit / 2)
                    .clipShape(shape)
            }
            .frame(width: PillArt.plateSide * unit, height: PillArt.plateSide * unit)
            .shadow(color: .black.opacity(0.30), radius: 14 * unit, y: 8 * unit)
    }
}

// MARK: Menu bar

/// The menu bar glyph: the same two pills, drawn as a template image so macOS tints
/// it for light and dark bars.
///
/// Filled and a little heavier while a device is connected, outlined while none is,
/// and the pills slide past each other while files are moving.
enum MenuBarGlyph {
    private static var cache: [Int: NSImage] = [:]
    /// Frames in one slide, enough to look continuous at the timer's pace.
    static let frames = 24

    /// `phase` is nil at rest, otherwise a position in a slide from 0 to 1.
    static func image(connected: Bool, phase: Double?) -> NSImage {
        let frame = phase.map { Int(($0 - floor($0)) * Double(frames)) % frames }
        let key = (connected ? 1000 : 0) + (frame.map { $0 + 1 } ?? 0)
        if let cached = cache[key] { return cached }

        let side: CGFloat = 18
        let unit = 17.5 / PillArt.pairWidth
        let slide: CGFloat = frame.map { CGFloat(sin(Double($0) / Double(frames) * 2 * .pi)) * 1.5 } ?? 0
        let widthScale: CGFloat = connected ? 1.06 : 0.94
        let stroke: CGFloat = 1.25

        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { _ in
            func path(spread: CGFloat, slide: CGFloat) -> NSBezierPath {
                let width = PillArt.width * unit * widthScale
                let length = PillArt.length * unit
                let rect = NSRect(x: -width / 2, y: -length / 2, width: width, height: length)
                let pill = NSBezierPath(roundedRect: rect, xRadius: width / 2, yRadius: width / 2)
                let cg = CGAffineTransform(translationX: side / 2 + spread * unit, y: side / 2)
                    .rotated(by: PillArt.leanDegrees * .pi / 180)
                    .translatedBy(x: 0, y: slide)
                pill.transform(using: AffineTransform(m11: cg.a, m12: cg.b, m21: cg.c, m22: cg.d, tX: cg.tx, tY: cg.ty))
                return pill
            }

            let right = path(spread: PillArt.spread, slide: slide)
            let left = path(spread: -PillArt.spread, slide: -slide)
            NSColor.black.setFill()
            NSColor.black.setStroke()

            if connected {
                right.fill()
            } else {
                right.lineWidth = stroke
                right.stroke()
            }

            // The left pill lies over the right one, as in the icon, with a thin gap
            // cut around it so the two stay apart at 18 points.
            if let context = NSGraphicsContext.current {
                context.saveGraphicsState()
                context.compositingOperation = .clear
                left.lineWidth = stroke * 2.4
                left.stroke()
                if !connected { left.fill() }
                context.restoreGraphicsState()
            }
            if connected {
                left.fill()
            } else {
                left.lineWidth = stroke
                left.stroke()
            }
            return true
        }
        image.isTemplate = true
        cache[key] = image
        return image
    }
}

/// The label of the menu bar item. It follows the model, and while files are moving
/// it advances a phase so the pills slide.
struct MenuBarIcon: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var phase = 0.0

    var body: some View {
        let moving = model.isTransferring && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        Image(nsImage: MenuBarGlyph.image(connected: model.onlineCount > 0, phase: moving ? phase : nil))
            .accessibilityLabel(Text("Tandem"))
            .task(id: moving) {
                guard moving else { return }
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(70))
                    phase += 1 / Double(MenuBarGlyph.frames)
                }
            }
    }
}
