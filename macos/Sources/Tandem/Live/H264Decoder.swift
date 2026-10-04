import CoreMedia
import Foundation
import VideoToolbox

/// Decodes the access units the core delivers into pixel buffers, with Video Toolbox.
///
/// Built for the shortest path from the network to the screen: the session is a real-time one, the hardware decoder is
/// asked for, nothing waits for a display time, and a frame that cannot be decoded in time is dropped instead of queued.
/// The core already guarantees the order and that decoding starts at a keyframe, so this has no reordering to do.
/// Everything happens on one serial queue; the callbacks come from there (or from the decoder's own thread).
final class H264Decoder: @unchecked Sendable {
    /// A picture is ready. `ptsUs` is the clock of the phone, only differences count.
    var onImage: (@Sendable (CVPixelBuffer, UInt64) -> Void)?
    /// The decoder lost the thread: ask the phone for a keyframe.
    var onNeedsKeyframe: (@Sendable () -> Void)?

    private let queue = DispatchQueue(label: "nl.markmaaktmedia.tandem.live.decode", qos: .userInteractive)
    private var session: VTDecompressionSession?
    private var format: CMVideoFormatDescription?
    private var sps: Data?
    private var pps: Data?
    private var waitingForKeyframe = true

    // Touched from the queue and from the caller, so under a lock.
    private let lock = NSLock()
    private var queued = 0
    private var _framesIn = 0
    private var _framesDecoded = 0
    private var _framesDropped = 0
    private var _errors = 0
    private var _decodeMicroseconds: Double = 0

    /// How many frames may wait for the decoder. More than this means the decoder is behind, and the answer to that is
    /// to skip to the next keyframe, not to show everything late.
    private let maxQueued = 5

    struct Counters: Equatable {
        var framesIn = 0
        var framesDecoded = 0
        var framesDropped = 0
        var errors = 0
        var averageDecodeMilliseconds = 0.0
    }

    var counters: Counters {
        lock.lock()
        defer { lock.unlock() }
        return Counters(
            framesIn: _framesIn, framesDecoded: _framesDecoded, framesDropped: _framesDropped, errors: _errors,
            averageDecodeMilliseconds: _framesDecoded == 0 ? 0 : _decodeMicroseconds / Double(_framesDecoded) / 1000
        )
    }

    deinit {
        if let session { VTDecompressionSessionInvalidate(session) }
    }

    /// Hands over one access unit. Returns at once.
    func decode(_ data: Data, ptsUs: UInt64, keyframe: Bool, discontinuity: Bool) {
        lock.lock()
        _framesIn += 1
        let backlog = queued
        let skip = backlog >= maxQueued && !keyframe
        if skip { _framesDropped += 1 } else { queued += 1 }
        lock.unlock()
        if skip {
            // Frames after the one that was skipped cannot be decoded without it.
            queue.async { [weak self] in self?.markBroken() }
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            self.decodeNow(data, ptsUs: ptsUs, keyframe: keyframe, discontinuity: discontinuity)
            self.lock.lock()
            self.queued -= 1
            self.lock.unlock()
        }
    }

    /// Throws the decoder state away, for a new session or after a long gap.
    func reset() {
        queue.async { [weak self] in
            guard let self else { return }
            self.invalidate()
            self.sps = nil
            self.pps = nil
            self.waitingForKeyframe = true
        }
    }

    // MARK: On the queue

    private func markBroken() {
        guard !waitingForKeyframe else { return }
        waitingForKeyframe = true
        onNeedsKeyframe?()
    }

    private func decodeNow(_ data: Data, ptsUs: UInt64, keyframe: Bool, discontinuity: Bool) {
        let access = AnnexB.parse(data)
        if discontinuity, let session {
            VTDecompressionSessionFinishDelayedFrames(session)
        }
        if let newSPS = access.sps, let newPPS = access.pps, newSPS != sps || newPPS != pps {
            sps = newSPS
            pps = newPPS
            makeSession(sps: newSPS, pps: newPPS)
        }
        if access.isKeyframe { waitingForKeyframe = false }
        guard !waitingForKeyframe, let session, let format, !access.pictures.isEmpty else {
            if waitingForKeyframe && !access.isKeyframe { countDropped() }
            return
        }

        let payload = AnnexB.lengthPrefixed(access.pictures)
        var block: CMBlockBuffer?
        let length = payload.count
        let status = payload.withUnsafeBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            // The block owns a copy: the decoder works after this call has returned.
            var created: CMBlockBuffer?
            let made = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: length, blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil, offsetToData: 0, dataLength: length, flags: 0, blockBufferOut: &created
            )
            guard made == noErr, let created else { return made }
            let copied = CMBlockBufferReplaceDataBytes(with: base, blockBuffer: created, offsetIntoDestination: 0, dataLength: length)
            block = created
            return copied
        }
        guard status == noErr, let block else { return countError() }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: CMTimeValue(ptsUs), timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var size = length
        var sample: CMSampleBuffer?
        let made = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size,
            sampleBufferOut: &sample
        )
        guard made == noErr, let sample else { return countError() }

        let started = DispatchTime.now().uptimeNanoseconds
        let flags: VTDecodeFrameFlags = [._EnableAsynchronousDecompression, ._1xRealTimePlayback]
        let result = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: flags, infoFlagsOut: nil) {
            [weak self] status, _, image, pts, _ in
            guard let self else { return }
            guard status == noErr, let image else {
                self.countError()
                self.queue.async { self.markBroken() }
                return
            }
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - started) / 1000
            self.lock.lock()
            self._framesDecoded += 1
            self._decodeMicroseconds += elapsed
            self.lock.unlock()
            let microseconds = pts.isValid ? UInt64(max(0, pts.seconds * 1_000_000)) : ptsUs
            self.onImage?(image, microseconds)
        }
        if result != noErr {
            countError()
            // A session that went bad (after sleep, after the GPU was reset) is made again from the same parameter sets.
            if result == kVTInvalidSessionErr || result == kVTVideoDecoderMalfunctionErr, let sps, let pps {
                makeSession(sps: sps, pps: pps)
            }
            markBroken()
        }
    }

    private func makeSession(sps: Data, pps: Data) {
        invalidate()
        var description: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw -> OSStatus in
                guard let spsBase = spsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                else { return -1 }
                let pointers = [spsBase, ppsBase]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let description else { return countError() }

        let specification: [CFString: Any] = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true]
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any](),
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        var created: VTDecompressionSession?
        let made = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault, formatDescription: description,
            decoderSpecification: specification as CFDictionary, imageBufferAttributes: attributes as CFDictionary,
            outputCallback: nil, decompressionSessionOut: &created
        )
        guard made == noErr, let created else { return countError() }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = created
        format = description
    }

    private func invalidate() {
        if let session {
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
        format = nil
    }

    private func countError() {
        lock.lock()
        _errors += 1
        lock.unlock()
    }

    private func countDropped() {
        lock.lock()
        _framesDropped += 1
        lock.unlock()
    }
}
