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

    /// Enough bits for text to stay sharp when little moves. What the link carries less of comes off it later (the core brings the target down
    /// when the viewer reports trouble), so the start is what a good network can take: the second thirty frames of a second count for less
    /// than the first, because they differ little from the ones before them.
    static func startingBitrate(width: Int, height: Int, fps: Int, requestedMax: Int) -> Int {
        let frames = Double(min(fps, 30)) + Double(max(fps - 30, 0)) * 0.4
        let wanted = Int(0.12 * Double(width * height) * max(1, frames))
        let bounded = min(max(wanted, 3_000_000), 24_000_000)
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

    /// The size of a screen made for a viewer: the pixels it asked for, even numbers, and no more than a good encoder takes (4K at most). It
    /// shows hiDPI (twice the points per pixel) where the screen is so large that the system would otherwise draw everything small.
    static func extendedSize(width: Int, height: Int) -> (width: Int, height: Int, hiDPI: Bool) {
        var w = max(640, width), h = max(360, height)
        let limit = 3840.0 * 2160.0
        let area = Double(w) * Double(h)
        if area > limit {
            let scale = (limit / area).squareRoot()
            w = Int(Double(w) * scale)
            h = Int(Double(h) * scale)
        }
        w &= ~1
        h &= ~1
        return (w, h, max(w, h) >= 2400 && min(w, h) >= 1500)
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
