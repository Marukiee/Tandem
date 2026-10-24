import AppKit

/// Scrolling as whole points, the way it goes over the wire, without losing what is less than one. A trackpad moves in steps such as
/// 0.4 and 1.7 points: cut off one by one, a slow scroll lost most of its way and came out in jerks. What is left is kept for the next step.
struct ScrollCarry {
    private var x = 0.0
    private var y = 0.0

    /// The whole points to send for this event, nil when it is not a whole one yet. A wheel that clicks counts in lines, ten points each.
    mutating func take(_ event: NSEvent) -> (Int16, Int16)? {
        let scale = event.hasPreciseScrollingDeltas ? 1.0 : 10.0
        // A new gesture starts from nothing: what was left of the last one belongs to it.
        if event.phase == .began { x = 0; y = 0 }
        x += event.scrollingDeltaX * scale
        y += event.scrollingDeltaY * scale
        let wholeX = x.rounded(.towardZero)
        let wholeY = y.rounded(.towardZero)
        x -= wholeX
        y -= wholeY
        guard wholeX != 0 || wholeY != 0 else { return nil }
        return (Int16(clamping: Int(wholeX)), Int16(clamping: Int(wholeY)))
    }
}
