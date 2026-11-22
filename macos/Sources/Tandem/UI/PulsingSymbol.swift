import AppKit
import SwiftUI

/// A symbol that pulses without the app's help.
///
/// SwiftUI's own `symbolEffect(.pulse)` is redrawn by the app on every frame of the screen: a window with a charging
/// phone on it kept the app at 13 to 30 percent of a core for as long as it was open. Here the symbol is one layer
/// with one Core Animation animation, which the system runs by itself in its compositor. The app hands it over and
/// goes back to sleep.
struct PulsingSymbol: NSViewRepresentable {
    let name: String
    var pointSize: CGFloat
    var weight: NSFont.Weight = .regular
    var color: NSColor
    /// Whether it pulses. A symbol that does not is still drawn by the same view, so it does not jump when it starts.
    var active = true
    /// A heart beat instead of a fade: two quick swells and a rest, about once a second, as on the phone and on Linux.
    var beat = false

    func makeNSView(context: Context) -> PulseLayerView { PulseLayerView() }

    func updateNSView(_ view: PulseLayerView, context: Context) {
        view.show(symbol: name, pointSize: pointSize, weight: weight, color: color)
        view.beat = beat
        view.pulsing = active
    }
}

final class PulseLayerView: NSView {
    private let glyph = CALayer()
    private var drawn: (String, CGFloat, NSFont.Weight, NSColor, CGFloat)?

    var pulsing = false {
        didSet {
            guard pulsing != oldValue else { return }
            applyAnimation()
        }
    }

    var beat = false {
        didSet {
            guard beat != oldValue else { return }
            needsLayout = true
            if pulsing { applyAnimation() }
        }
    }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.addSublayer(glyph)
        glyph.contentsGravity = .center
    }

    required init?(coder: NSCoder) { fatalError("not from a nib") }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        // A heart swells around its own middle, a little above the middle of the view where its round tops make it heavy
        // (y counts up here), or it seems to grow from the bottom up.
        glyph.anchorPoint = beat ? CGPoint(x: 0.5, y: 0.54) : CGPoint(x: 0.5, y: 0.5)
        glyph.frame = bounds
        CATransaction.commit()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        redraw()
    }

    func show(symbol: String, pointSize: CGFloat, weight: NSFont.Weight, color: NSColor) {
        let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2
        if let drawn, drawn.0 == symbol, drawn.1 == pointSize, drawn.2 == weight, drawn.3 == color, drawn.4 == scale { return }
        drawn = (symbol, pointSize, weight, color, scale)
        redraw()
    }

    private func redraw() {
        guard let (symbol, pointSize, weight, color, _) = drawn else { return }
        let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2
        let configuration = NSImage.SymbolConfiguration(pointSize: pointSize, weight: weight)
        guard let image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(configuration) else {
            glyph.contents = nil
            return
        }
        // Drawn at the pixels of the screen, so it is as sharp as any symbol.
        let pixels = CGSize(width: ceil(image.size.width * scale), height: ceil(image.size.height * scale))
        var cgImage: CGImage?
        if let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: Int(pixels.width), pixelsHigh: Int(pixels.height), bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ), let context = NSGraphicsContext(bitmapImageRep: rep) {
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = context
            let all = NSRect(origin: .zero, size: pixels)
            image.draw(in: all)
            // The colour goes on what was drawn and nowhere else, so the parts a symbol cuts out stay cut out.
            color.set()
            all.fill(using: .sourceAtop)
            NSGraphicsContext.restoreGraphicsState()
            cgImage = rep.cgImage
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        glyph.contentsScale = scale
        glyph.contents = cgImage
        CATransaction.commit()
    }

    private func applyAnimation() {
        glyph.removeAnimation(forKey: "pulse")
        guard pulsing else { return }
        if beat {
            // On the glyph and not on the view's own layer: that one is anchored at its lower left corner, so it would
            // grow from there. The glyph is anchored in its middle, so the heart swells from its centre.
            let swell = CAKeyframeAnimation(keyPath: "transform.scale")
            swell.values = [1.0, 1.18, 1.0, 1.12, 1.0, 1.0]
            swell.keyTimes = [0, 0.12, 0.24, 0.36, 0.52, 1]
            swell.duration = 1
            swell.repeatCount = .infinity
            swell.timingFunctions = Array(repeating: CAMediaTimingFunction(name: .easeInEaseOut), count: 5)
            glyph.add(swell, forKey: "pulse")
            return
        }
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 1.0
        fade.toValue = 0.35
        fade.duration = 1.1
        fade.autoreverses = true
        fade.repeatCount = .infinity
        fade.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        glyph.add(fade, forKey: "pulse")
    }
}
