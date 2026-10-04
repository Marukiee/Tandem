import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// H.264 for a screen: hardware, low delay, no frame reordering, one slice per picture, and an Annex B output with the
/// parameter sets in front of every keyframe (the core refuses a keyframe without them).
///
/// A screen is different from a camera in one way that matters here: when nothing moves, the capture delivers nothing.
/// Two things follow. A keyframe that is asked for on a still screen has no new picture to be made of, so the last
/// picture is encoded again. And a still screen is encoded again a few times after it stopped moving, which lets the
/// rate control refine what the first frame left blurry (text stays sharp), and keeps a trickle of frames going so the
/// viewer can tell a quiet screen from a dead link.
final class ScreenEncoder: @unchecked Sendable {
    struct Config: Equatable {
        var width: Int
        var height: Int
        var fps: Int
        var bitrate: Int
    }

    struct Frame {
        var data: [UInt8]
        var ptsUs: UInt64
        var keyframe: Bool
    }

    enum Failure: Error, CustomStringConvertible {
        case create(OSStatus)
        case prepare(OSStatus)

        var description: String {
            switch self {
            case let .create(status): "VideoToolbox could not make an H.264 session (\(status))"
            case let .prepare(status): "VideoToolbox could not start the session (\(status))"
            }
        }
    }

    struct Counters {
        var submitted = 0
        var encoded = 0
        var dropped = 0
        var failed = 0
        var keyframes = 0
        var bytes = 0
    }

    /// Called on a VideoToolbox thread, one frame at a time and in order.
    var onFrame: ((Frame) -> Void)?

    private(set) var config: Config
    /// Whether the low delay rate control of VideoToolbox took. When not, the plain real time mode is in use.
    private(set) var lowLatency = false

    private let lock = NSLock()
    private var session: VTCompressionSession?
    private var lastBuffer: CVPixelBuffer?
    private var lastPts: UInt64 = 0
    private var lastSubmit = DispatchTime.now()
    private var inFlight = 0
    private var forceNext = true
    private var quietRepeats = 0
    private var counters = Counters()
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "nl.markmaaktmedia.tandem.screen-encoder", qos: .userInteractive)

    /// Frames that wait inside the encoder at most. More than this and the screen is moving faster than it can be
    /// encoded, so the newest picture replaces the one that was about to be dropped.
    private static let maxInFlight = 3

    init(_ config: Config) throws {
        self.config = config
        try makeSession()
        startTimer()
    }

    deinit { invalidate() }

    var stats: Counters {
        lock.lock()
        defer { lock.unlock() }
        return counters
    }

    // MARK: Session

    private func makeSession() throws {
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey: config.width,
            kCVPixelBufferHeightKey: config.height,
        ]
        func create(lowLatency: Bool) -> (VTCompressionSession?, OSStatus) {
            var made: VTCompressionSession?
            let specification: CFDictionary? = lowLatency
                ? [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary
                : nil
            let status = VTCompressionSessionCreate(
                allocator: nil, width: Int32(config.width), height: Int32(config.height), codecType: kCMVideoCodecType_H264,
                encoderSpecification: specification, imageBufferAttributes: attributes as CFDictionary,
                compressedDataAllocator: nil, outputCallback: nil, refcon: nil, compressionSessionOut: &made
            )
            return (made, status)
        }

        var (made, status) = create(lowLatency: true)
        lowLatency = made != nil && status == noErr
        if !lowLatency {
            (made, status) = create(lowLatency: false)
        }
        guard let made, status == noErr else { throw Failure.create(status) }

        func set(_ key: CFString, _ value: CFTypeRef) { _ = VTSessionSetProperty(made, key: key, value: value) }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel)
        set(kVTCompressionPropertyKey_H264EntropyMode, kVTH264EntropyMode_CABAC)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, config.fps as CFNumber)
        // Keyframes are expensive on a screen and the core asks for one whenever a viewer needs it, so the interval
        // is only a safety net.
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 8 as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, (config.fps * 8) as CFNumber)
        set(kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_ColorPrimaries, kCMFormatDescriptionColorPrimaries_ITU_R_709_2)
        set(kVTCompressionPropertyKey_TransferFunction, kCMFormatDescriptionTransferFunction_ITU_R_709_2)
        set(kVTCompressionPropertyKey_YCbCrMatrix, kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2)
        applyBitrate(config.bitrate, to: made)

        let prepared = VTCompressionSessionPrepareToEncodeFrames(made)
        guard prepared == noErr else {
            VTCompressionSessionInvalidate(made)
            throw Failure.prepare(prepared)
        }
        session = made
    }

    private func applyBitrate(_ bitrate: Int, to session: VTCompressionSession) {
        _ = VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        // A burst of at most half a second at 1.5 times the average, so a scroll does not flood a slow link.
        let limit = [Int(Double(bitrate) * 1.5 / 8 / 2), 0.5] as CFArray
        _ = VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: limit)
    }

    func setBitrate(_ bitrate: Int) {
        lock.lock()
        config.bitrate = bitrate
        let current = session
        lock.unlock()
        if let current { applyBitrate(bitrate, to: current) }
    }

    func setFrameRate(_ fps: Int) {
        lock.lock()
        config.fps = fps
        let current = session
        lock.unlock()
        if let current { _ = VTSessionSetProperty(current, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: fps as CFNumber) }
    }

    func invalidate() {
        lock.lock()
        let current = session
        session = nil
        lastBuffer = nil
        let running = timer
        timer = nil
        lock.unlock()
        running?.cancel()
        if let current {
            VTCompressionSessionCompleteFrames(current, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(current)
        }
    }

    // MARK: Input

    /// A new picture of the screen.
    func submit(_ buffer: CVPixelBuffer) {
        lock.lock()
        quietRepeats = 0
        lock.unlock()
        // One queue for every call into VideoToolbox: it wants the presentation times in the order they were made,
        // and a repeat from the timer must not overtake a picture from the capture.
        queue.async { [self] in encode(buffer, repeated: false) }
    }

    /// The next frame is a keyframe, and if the screen is still, the last picture is encoded again right now.
    func requestKeyframe() {
        lock.lock()
        forceNext = true
        let pending = lastBuffer
        lock.unlock()
        if let pending { queue.async { [self] in encode(pending, repeated: true) } }
    }

    private func encode(_ buffer: CVPixelBuffer, repeated: Bool) {
        lock.lock()
        lastBuffer = buffer
        guard let session else {
            lock.unlock()
            return
        }
        if inFlight >= Self.maxInFlight {
            counters.dropped += 1
            lock.unlock()
            return
        }
        inFlight += 1
        counters.submitted += 1
        let force = forceNext
        forceNext = false
        let now = DispatchTime.now().uptimeNanoseconds / 1000
        lastPts = max(lastPts + 1, now)
        let pts = lastPts
        lastSubmit = .now()
        lock.unlock()

        let properties: CFDictionary? = force ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        let status = VTCompressionSessionEncodeFrame(
            session, imageBuffer: buffer, presentationTimeStamp: CMTime(value: Int64(pts), timescale: 1_000_000),
            duration: .invalid, frameProperties: properties, infoFlagsOut: nil
        ) { [weak self] status, flags, sample in
            self?.finished(status: status, flags: flags, sample: sample, pts: pts)
        }
        if status != noErr {
            lock.lock()
            inFlight -= 1
            counters.failed += 1
            // The keyframe that was meant to go out did not, so the next frame has to be one.
            if force { forceNext = true }
            lock.unlock()
        }
    }

    // MARK: Output

    private func finished(status: OSStatus, flags: VTEncodeInfoFlags, sample: CMSampleBuffer?, pts: UInt64) {
        lock.lock()
        inFlight -= 1
        lock.unlock()
        guard status == noErr, !flags.contains(.frameDropped), let sample, CMSampleBufferDataIsReady(sample),
              let frame = Self.frame(from: sample, pts: pts)
        else {
            lock.lock()
            counters.failed += 1
            // A lost keyframe has to be made again, or the viewer waits for it until the next request.
            forceNext = true
            lock.unlock()
            return
        }
        lock.lock()
        counters.encoded += 1
        counters.bytes += frame.data.count
        if frame.keyframe { counters.keyframes += 1 }
        lock.unlock()
        onFrame?(frame)
    }

    /// VideoToolbox's sample as Annex B. Nil when it is damaged.
    static func frame(from sample: CMSampleBuffer, pts: UInt64) -> Frame? {
        guard let block = CMSampleBufferGetDataBuffer(sample), let format = CMSampleBufferGetFormatDescription(sample) else { return nil }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
        let keyframe = !((attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)

        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        var total = 0
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &length, totalLengthOut: &total, dataPointerOut: &pointer) == noErr,
              let pointer
        else { return nil }
        // A block buffer that is not in one piece is rare for a single picture. It is copied flat when it happens.
        var flat: [UInt8]?
        if length != total {
            var copy = [UInt8](repeating: 0, count: total)
            guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: total, destination: &copy) == noErr else { return nil }
            flat = copy
        }

        var sets: [[UInt8]] = []
        var lengthSize: Int32 = 4
        if keyframe {
            var count = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &lengthSize
            ) == noErr else { return nil }
            for index in 0 ..< count {
                var set: UnsafePointer<UInt8>?
                var size = 0
                guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: index, parameterSetPointerOut: &set, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
                ) == noErr, let set else { return nil }
                sets.append(Array(UnsafeBufferPointer(start: set, count: size)))
            }
            // Nothing a decoder could start from.
            guard sets.count >= 2 else { return nil }
        } else {
            _ = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: &lengthSize
            )
        }

        let data: [UInt8]?
        if let flat {
            data = flat.withUnsafeBytes { H264.annexB(lengthPrefixed: $0, lengthSize: Int(lengthSize), leading: sets) }
        } else {
            data = H264.annexB(lengthPrefixed: UnsafeRawBufferPointer(start: pointer, count: total), lengthSize: Int(lengthSize), leading: sets)
        }
        guard let data else { return nil }
        return Frame(data: data, ptsUs: pts, keyframe: keyframe)
    }

    // MARK: Still screens

    private func startTimer() {
        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
        source.setEventHandler { [weak self] in self?.tick() }
        lock.lock()
        timer = source
        lock.unlock()
        source.resume()
    }

    /// Four quick repeats of a screen that went still, then one a second.
    private func tick() {
        lock.lock()
        let waited = Double(DispatchTime.now().uptimeNanoseconds - lastSubmit.uptimeNanoseconds) / 1_000_000_000
        let due = quietRepeats < 4 ? 0.2 : 0.95
        let pending = lastBuffer
        if waited >= due, pending != nil { quietRepeats += 1 }
        lock.unlock()
        if waited >= due, let pending { encode(pending, repeated: true) }
    }
}
