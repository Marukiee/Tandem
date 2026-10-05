import AVFoundation
import AppKit
import CoreImage
import SwiftUI
import TandemCore

/// How the picture is turned for the person: what the phone says (`rotation`, degrees clockwise to look upright), what
/// the person added by hand, and a mirror for the front camera. Pure, so the sizes and the aspect can be tested.
struct VideoOrientation: Equatable {
    var phoneRotation = 0
    var extraRotation = 0
    var mirrored = false

    /// Always one of 0, 90, 180, 270.
    var degrees: Int { (((phoneRotation + extraRotation) % 360) + 360) % 360 }
    var swapsAxes: Bool { degrees == 90 || degrees == 270 }

    /// The size the picture has on screen once it is turned.
    func shown(width: Int, height: Int) -> (width: Int, height: Int) {
        swapsAxes ? (height, width) : (width, height)
    }
}

/// A layer that shows decoded pictures the moment they arrive. The sample buffers carry no time, so the layer has
/// nothing to wait for and nothing queues up behind it.
final class VideoSurfaceView: NSView {
    private let displayLayer = AVSampleBufferDisplayLayer()
    private var orientation = VideoOrientation()
    private var lastImage: CVPixelBuffer?
    private let imageLock = NSLock()

    /// Where the mouse and the keys of this Mac go when the phone has let them control it. Nothing is sent while
    /// [isControlActive] says no, and the view then lets the events pass as usual.
    var onInput: ((TandemMediaInput) -> Void)?
    var isControlActive: () -> Bool = { false }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
        // Fill, not fit: the window is locked to the shape of the picture, and a pixel of difference must not show as a black edge.
        displayLayer.videoGravity = .resizeAspectFill
        displayLayer.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(displayLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        applyOrientation()
    }

    func setOrientation(_ new: VideoOrientation) {
        guard new != orientation else { return }
        orientation = new
        applyOrientation()
    }

    /// The layer is laid out in the turned frame: for a quarter turn its width and height swap, so the picture still
    /// fits the view with its aspect, and the transform then turns it into place.
    private func applyOrientation() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let size = bounds.size
        let turned = orientation.swapsAxes ? CGSize(width: size.height, height: size.width) : size
        displayLayer.bounds = CGRect(origin: .zero, size: turned)
        displayLayer.position = CGPoint(x: size.width / 2, y: size.height / 2)
        var transform = CGAffineTransform(rotationAngle: -CGFloat(orientation.degrees) * .pi / 180)
        // The mirror is across the screen, after the turn, whatever the turn is.
        if orientation.mirrored { transform = transform.concatenating(CGAffineTransform(scaleX: -1, y: 1)) }
        displayLayer.setAffineTransform(transform)
        CATransaction.commit()
    }

    /// Shows one picture. Safe to call from any thread.
    func show(_ image: CVPixelBuffer) {
        imageLock.lock()
        lastImage = image
        imageLock.unlock()
        guard let sample = Self.sampleBuffer(for: image) else { return }
        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed { renderer.flush() }
        renderer.enqueue(sample)
    }

    /// Clears the layer, for a new session.
    func clear() {
        imageLock.lock()
        lastImage = nil
        imageLock.unlock()
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    var latestImage: CVPixelBuffer? {
        imageLock.lock()
        defer { imageLock.unlock() }
        return lastImage
    }

    private static func sampleBuffer(for image: CVPixelBuffer) -> CMSampleBuffer? {
        var description: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: image, formatDescriptionOut: &description) == noErr,
              let description
        else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .invalid, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: nil, imageBuffer: image, formatDescription: description, sampleTiming: &timing, sampleBufferOut: &sample
        ) == noErr, let sample else { return nil }
        // Without a time to wait for, the layer draws it now.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dictionary, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return sample
    }

    // MARK: Mouse and keys for the phone

    /// Where in the picture an event is, as a fraction of it, or nil outside it or when the picture is turned (the
    /// phone does not know about a turn the person made here).
    private func fraction(of event: NSEvent) -> (x: Float, y: Float)? {
        guard orientation.degrees == 0, !orientation.mirrored, let image = latestImage else { return nil }
        let iw = CGFloat(CVPixelBufferGetWidth(image))
        let ih = CGFloat(CVPixelBufferGetHeight(image))
        guard iw > 0, ih > 0, bounds.width > 0, bounds.height > 0 else { return nil }
        // The picture fills the view (see the layer), so a little of it can lie outside.
        let scale = max(bounds.width / iw, bounds.height / ih)
        let drawn = CGRect(x: (bounds.width - iw * scale) / 2, y: (bounds.height - ih * scale) / 2, width: iw * scale, height: ih * scale)
        let point = convert(event.locationInWindow, from: nil)
        guard bounds.contains(point) else { return nil }
        let x = min(max((point.x - drawn.minX) / drawn.width, 0), 1)
        let y = min(max(1 - (point.y - drawn.minY) / drawn.height, 0), 1)
        return (Float(x), Float(y))
    }

    private func send(_ input: TandemMediaInput) { onInput?(input) }

    override func mouseDown(with event: NSEvent) {
        guard isControlActive(), let at = fraction(of: event) else { return super.mouseDown(with: event) }
        window?.makeFirstResponder(self)
        send(.pointerAbs(x: at.x, y: at.y))
        send(.button(button: 0, down: true, clicks: UInt8(clamping: max(1, event.clickCount))))
    }

    override func mouseDragged(with event: NSEvent) {
        guard isControlActive(), let at = fraction(of: event) else { return super.mouseDragged(with: event) }
        send(.pointerAbs(x: at.x, y: at.y))
    }

    override func mouseUp(with event: NSEvent) {
        guard isControlActive() else { return super.mouseUp(with: event) }
        if let at = fraction(of: event) { send(.pointerAbs(x: at.x, y: at.y)) }
        send(.button(button: 0, down: false, clicks: UInt8(clamping: max(1, event.clickCount))))
    }

    /// The right button is Back on the phone.
    override func rightMouseDown(with event: NSEvent) {
        guard isControlActive(), fraction(of: event) != nil else { return super.rightMouseDown(with: event) }
        send(.button(button: 1, down: true, clicks: 1))
        send(.button(button: 1, down: false, clicks: 1))
    }

    /// The scroll wheel and two fingers on the pad move the content the way the fingers do.
    override func scrollWheel(with event: NSEvent) {
        guard isControlActive(), let at = fraction(of: event) else { return super.scrollWheel(with: event) }
        let dx = Int16(clamping: Int(event.scrollingDeltaX * (event.hasPreciseScrollingDeltas ? 1 : 10)))
        let dy = Int16(clamping: Int(event.scrollingDeltaY * (event.hasPreciseScrollingDeltas ? 1 : 10)))
        guard dx != 0 || dy != 0 else { return }
        send(.pointerAbs(x: at.x, y: at.y))
        send(.scroll(dx: dx, dy: dy))
    }

    /// Letters and digits are typed; Escape is Back, Delete takes a character off, Return goes in as a line break.
    override func keyDown(with event: NSEvent) {
        guard isControlActive() else { return super.keyDown(with: event) }
        let modifiers = event.modifierFlags.intersection([.command, .control])
        guard modifiers.isEmpty else { return super.keyDown(with: event) }
        switch event.keyCode {
        case 53: send(.key(code: 0x29, down: true, mods: 0, text: ""))
        case 51: send(.key(code: 0x2A, down: true, mods: 0, text: ""))
        case 36, 76: send(.key(code: 0x28, down: true, mods: 0, text: ""))
        default:
            if let text = event.characters, !text.isEmpty, text.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 }) {
                send(.text(text: text))
            } else {
                super.keyDown(with: event)
            }
        }
    }

    // MARK: Pictures of the picture

    private static let imageContext = CIContext(options: [.cacheIntermediates: false])

    /// The latest picture as the person sees it: turned, and mirrored when the view is.
    func renderedImage() -> NSImage? {
        guard let buffer = latestImage else { return nil }
        var image = CIImage(cvPixelBuffer: buffer)
        let degrees = orientation.degrees
        if degrees != 0 {
            // Clockwise on screen is counter-clockwise in Core Image's upright coordinates.
            image = image.oriented(Self.orientation(forClockwise: degrees))
        }
        if orientation.mirrored {
            image = image.transformed(by: CGAffineTransform(scaleX: -1, y: 1).translatedBy(x: -image.extent.width, y: 0))
        }
        guard let cg = Self.imageContext.createCGImage(image, from: image.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }

    // The layer that plays video is not drawn by `cacheDisplay`, so a snapshot of the window would show a black hole.
    // For the length of the snapshot an ordinary layer with the same picture takes its place.
    private var snapshotLayer: CALayer?

    func prepareSnapshot() {
        guard let buffer = latestImage else { return }
        let image = CIImage(cvPixelBuffer: buffer)
        guard let cg = Self.imageContext.createCGImage(image, from: image.extent) else { return }
        let layer = CALayer()
        layer.contents = cg
        layer.contentsGravity = .resizeAspect
        layer.bounds = displayLayer.bounds
        layer.position = displayLayer.position
        layer.setAffineTransform(displayLayer.affineTransform())
        self.layer?.addSublayer(layer)
        snapshotLayer = layer
        displayLayer.isHidden = true
    }

    func finishSnapshot() {
        snapshotLayer?.removeFromSuperlayer()
        snapshotLayer = nil
        displayLayer.isHidden = false
    }

    private static func orientation(forClockwise degrees: Int) -> CGImagePropertyOrientation {
        switch degrees {
        case 90: .right
        case 180: .down
        case 270: .left
        default: .up
        }
    }
}

struct VideoSurface: NSViewRepresentable {
    let surface: VideoSurfaceView
    var orientation: VideoOrientation

    func makeNSView(context: Context) -> VideoSurfaceView {
        surface.setOrientation(orientation)
        return surface
    }

    func updateNSView(_ view: VideoSurfaceView, context: Context) {
        view.setOrientation(orientation)
    }
}
