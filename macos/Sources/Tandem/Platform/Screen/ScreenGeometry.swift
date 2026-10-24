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

    /// A screen made for a viewer: the points the system lays it out in, whether it is drawn at twice the points (hiDPI), the pixels it is
    /// drawn in, and the size of the picture that goes over.
    struct ExtendedShape: Equatable {
        var pointsWidth: Int
        var pointsHeight: Int
        var hiDPI: Bool
        var pixelsWidth: Int
        var pixelsHeight: Int
        var streamWidth: Int
        var streamHeight: Int
    }

    /// The most pixels a display of software is drawn in: what a 6K display has.
    static let maxBackingArea = 6144.0 * 3456.0

    /// When the viewer says its logical size, the screen has that many points, so text is as big as on the viewer's own screen, and the
    /// picture is the viewer's real pixels, so the viewer shows it as it comes and nothing is scaled there. A Mac draws hiDPI at exactly
    /// twice the points; for a viewer at 125% to 175% the capture brings that down to the viewer's pixels, which is what a Mac does for the
    /// scaled modes of its own screen. Without a logical size it is the pixels that were asked for, as before.
    static func extendedShape(width: Int, height: Int, logicalWidth: Int, logicalHeight: Int) -> ExtendedShape {
        guard logicalWidth > 0, logicalHeight > 0, width > 0, height > 0 else {
            let old = extendedSize(width: width, height: height)
            return ExtendedShape(
                pointsWidth: old.hiDPI ? old.width / 2 : old.width, pointsHeight: old.hiDPI ? old.height / 2 : old.height, hiDPI: old.hiDPI,
                pixelsWidth: old.width, pixelsHeight: old.height, streamWidth: old.width, streamHeight: old.height
            )
        }
        let stream = extendedSize(width: width, height: height)
        let scale = Double(width) / Double(logicalWidth)
        // A screen at 100% (or nearly) is drawn as it is.
        guard scale >= 1.2 else {
            return ExtendedShape(
                pointsWidth: stream.width, pointsHeight: stream.height, hiDPI: false,
                pixelsWidth: stream.width, pixelsHeight: stream.height, streamWidth: stream.width, streamHeight: stream.height
            )
        }
        var points = (width: max(320, logicalWidth) & ~1, height: max(180, logicalHeight) & ~1)
        let backing = Double(points.width * 2) * Double(points.height * 2)
        if backing > maxBackingArea {
            let factor = (maxBackingArea / backing).squareRoot()
            points = (Int(Double(points.width) * factor) & ~1, Int(Double(points.height) * factor) & ~1)
        }
        let pixels = (width: points.width * 2, height: points.height * 2)
        // A viewer above 200% has more pixels than the screen is drawn in: making the picture bigger adds bits, not detail.
        let fits = stream.width <= pixels.width && stream.height <= pixels.height
        return ExtendedShape(
            pointsWidth: points.width, pointsHeight: points.height, hiDPI: true, pixelsWidth: pixels.width, pixelsHeight: pixels.height,
            streamWidth: fits ? stream.width : pixels.width, streamHeight: fits ? stream.height : pixels.height
        )
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
