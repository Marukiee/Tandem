import CoreGraphics
import CoreMedia
import Foundation
import ScreenCaptureKit

/// The main display as a stream of pictures, with the pointer drawn into them.
///
/// The pointer is part of the picture on purpose: the wire has no separate cursor, and a pointer that is in the video is
/// always where the Mac believes it is, whoever moved it (the person at the Mac, or the phone).
final class ScreenCapturer: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    enum Failure: Error, CustomStringConvertible {
        case noPermission
        case noDisplay

        var description: String {
            switch self {
            case .noPermission: "Screen Recording is not allowed for Tandem"
            case .noDisplay: "there is no display to capture"
            }
        }
    }

    /// What the display is right now, in the units each part needs.
    struct DisplayInfo: Equatable {
        var id: CGDirectDisplayID
        /// Where it sits in the space events are posted in.
        var bounds: CGRect
        /// Its real pixels, which are more than the points on a Retina display.
        var pixels: CGSize
    }

    /// Called on the capture queue for every new picture. The buffer is only good for as long as it is held.
    var onFrame: ((CVPixelBuffer) -> Void)?
    /// The system ended the stream (the display went away, the permission was taken back).
    var onStopped: ((Error?) -> Void)?

    private var stream: SCStream?
    private var configuration: SCStreamConfiguration?
    private let queue = DispatchQueue(label: "nl.markmaaktmedia.tandem.screen-capture", qos: .userInteractive)

    static var hasPermission: Bool { CGPreflightScreenCaptureAccess() }

    /// The capture refused to start because the person has not allowed it (or took it back).
    static func isPermissionError(_ error: Error) -> Bool {
        if let failure = error as? Failure, failure == .noPermission { return true }
        let nsError = error as NSError
        return nsError.domain == SCStreamErrorDomain && nsError.code == SCStreamError.userDeclined.rawValue
    }

    static func mainDisplay() -> DisplayInfo {
        let id = CGMainDisplayID()
        let bounds = CGDisplayBounds(id)
        var pixels = bounds.size
        if let mode = CGDisplayCopyDisplayMode(id) {
            pixels = CGSize(width: mode.pixelWidth, height: mode.pixelHeight)
        }
        return DisplayInfo(id: id, bounds: bounds, pixels: pixels)
    }

    func start(display: DisplayInfo, width: Int, height: Int, fps: Int) async throws {
        guard Self.hasPermission else { throw Failure.noPermission }
        // A display of software is one of the monitors of the system a moment before the capture can list it: it is looked for for a while,
        // and no other display stands in for it (the viewer would be shown a screen it did not ask for).
        let isMain = display.id == CGMainDisplayID()
        var found: SCDisplay?
        for attempt in 0..<(isMain ? 1 : 15) {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            found = content.displays.first(where: { $0.displayID == display.id }) ?? (isMain ? content.displays.first : nil)
            if found != nil { break }
            if attempt < 14 { try? await Task.sleep(for: .milliseconds(200)) }
        }
        guard let target = found else { throw Failure.noDisplay }
        let configuration = SCStreamConfiguration()
        configuration.width = width
        configuration.height = height
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, fps)))
        // What the encoder takes without converting anything.
        configuration.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        configuration.showsCursor = true
        configuration.queueDepth = 5
        configuration.scalesToFit = true
        configuration.colorSpaceName = CGColorSpace.sRGB
        self.configuration = configuration

        let stream = SCStream(filter: SCContentFilter(display: target, excludingWindows: []), configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        try await stream.startCapture()
        self.stream = stream
    }

    func setFrameRate(_ fps: Int) async {
        guard let stream, let configuration else { return }
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, fps)))
        try? await stream.updateConfiguration(configuration)
    }

    func stop() async {
        let current = stream
        stream = nil
        onFrame = nil
        onStopped = nil
        try? await current?.stopCapture()
    }

    // MARK: SCStreamOutput

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid else { return }
        // The capture also reports that nothing changed, or that the picture is blank or being started. Those carry
        // no new picture.
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let buffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else { return }
        onFrame?(buffer)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        let report = onStopped
        self.stream = nil
        report?(error)
    }
}
