import AVFoundation
import AppKit
import CoreImage
import SwiftUI

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

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
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
