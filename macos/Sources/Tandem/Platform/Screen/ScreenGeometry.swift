import CoreGraphics
import Foundation

/// The arithmetic of sharing a screen, kept apart from capture and injection so it can be checked on its own.
enum ScreenGeometry {
    /// The size of the picture to stream: the display scaled down to fit what the viewer asked for and what a phone
    /// can usefully show. Even numbers, because the chroma planes are half size. Never larger than the display.
    static func fit(source: CGSize, maxWidth: Int, maxHeight: Int, longSideCap: Int = 2560) -> (width: Int, height: Int) {
        let sourceWidth = max(2, source.width)
        let sourceHeight = max(2, source.height)
        var scale = 1.0
        if maxWidth > 0 { scale = min(scale, Double(maxWidth) / sourceWidth) }
        if maxHeight > 0 { scale = min(scale, Double(maxHeight) / sourceHeight) }
        scale = min(scale, Double(longSideCap) / max(sourceWidth, sourceHeight))
        func even(_ value: Double) -> Int { max(2, Int((value / 2).rounded()) * 2) }
        return (even(sourceWidth * scale), even(sourceHeight * scale))
    }

    /// Enough bits for text to stay sharp when little moves, without asking a phone on Wi-Fi for more than it needs.
    static func startingBitrate(width: Int, height: Int, fps: Int, requestedMax: Int) -> Int {
        let wanted = Int(0.07 * Double(width * height) * Double(max(1, fps)))
        let bounded = min(max(wanted, 2_000_000), 16_000_000)
        return requestedMax > 0 ? max(500_000, min(bounded, requestedMax)) : bounded
    }

    static func frameRate(requested: Int) -> Int {
        requested > 0 ? min(max(requested, 10), 60) : 30
    }

    /// The frame rate to ask the capture for when the link can only carry part of the bitrate it started with.
    static func frameRate(for bitrate: Int, started: Int, base: Int) -> Int {
        guard started > 0 else { return base }
        let share = Double(bitrate) / Double(started)
        if share < 0.2 { return min(base, 12) }
        if share < 0.5 { return min(base, 20) }
        return base
    }

    /// A position in the streamed picture as a point on the display, kept inside it.
    static func point(x: Double, y: Double, in bounds: CGRect) -> CGPoint {
        CGPoint(
            x: min(max(bounds.minX + x * bounds.width, bounds.minX), bounds.maxX - 1),
            y: min(max(bounds.minY + y * bounds.height, bounds.minY), bounds.maxY - 1)
        )
    }

    /// Points on the display for one pixel of the streamed picture.
    static func pointsPerPixel(streamWidth: Int, bounds: CGRect) -> Double {
        bounds.width / Double(max(1, streamWidth))
    }
}

/// Moves that are smaller than a whole unit are kept and added to the next one, so a slow scroll or a slow pointer
/// does not round away to nothing.
struct Remainder {
    private var x = 0.0
    private var y = 0.0

    mutating func take(_ dx: Double, _ dy: Double) -> (Int32, Int32) {
        x += dx
        y += dy
        let wholeX = x.rounded(.towardZero)
        let wholeY = y.rounded(.towardZero)
        x -= wholeX
        y -= wholeY
        return (Int32(wholeX), Int32(wholeY))
    }

    mutating func reset() {
        x = 0
        y = 0
    }
}
