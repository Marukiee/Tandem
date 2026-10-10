import AppKit
import CoreMedia
import CoreText
import CoreVideo
import Foundation
import VideoToolbox

/// Development harness for the screen encoder, started with `TANDEM_DEBUG_DIR=<dir> TANDEM_DEBUG_SCREEN=encode`.
///
/// It draws a moving, desktop-like picture (text, a pointer, a scrolling area, a counter in the corner), pushes it through
/// the real `ScreenEncoder` at a real 30 frames a second, then decodes what came out with VideoToolbox's decoder, which
/// is the same thing that a hardware decoder on a phone does with Annex B. It proves:
///
/// - every keyframe holds its parameter sets and the first frame is a keyframe,
/// - the size in the SPS is the size that was asked for,
/// - a keyframe asked for while the screen moves and while it is still arrives promptly,
/// - a still screen keeps producing frames,
/// - the stream decodes, also when it is joined at a later keyframe, and looks like what went in (PSNR),
/// - the pure helpers (Annex B, geometry, key table) do what the contract says.
///
/// Nothing here touches the screen, so it needs no permission. The report is `screen-report.txt` in the debug folder, the
/// stream is `screen.h264` (play it with any player that reads raw Annex B).
enum ScreenDebug {
    private static let width = 1280
    private static let height = 720
    private static let fps = 30

    private final class Report: @unchecked Sendable {
        var lines: [String] = []
        var failed = false

        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            lines.append("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : ": \(detail)")")
            if !ok { failed = true }
        }

        func note(_ text: String) { lines.append("     \(text)") }
    }

    static func run(into directory: URL) {
        Task.detached(priority: .userInitiated) {
            let report = Report()
            await harness(report, directory: directory)
            report.lines.append(report.failed ? "RESULT: FAIL" : "RESULT: PASS")
            try? report.lines.joined(separator: "\n").appending("\n").write(
                to: directory.appendingPathComponent("screen-report.txt"), atomically: true, encoding: .utf8
            )
            exit(report.failed ? 1 : 0)
        }
    }

    private static func harness(_ report: Report, directory: URL) async {
        pureChecks(report)

        let config = ScreenEncoder.Config(width: width, height: height, fps: fps, bitrate: 4_000_000)
        let encoder: ScreenEncoder
        do { encoder = try ScreenEncoder(config) } catch {
            report.check("encoder starts", false, "\(error)")
            return
        }
        report.note("low latency rate control: \(encoder.lowLatency)")
        report.check("encoder starts", true)

        // What comes out, with when it came out.
        final class Sink: @unchecked Sendable {
            let lock = NSLock()
            var frames: [(frame: ScreenEncoder.Frame, at: Double)] = []
            func add(_ frame: ScreenEncoder.Frame, at: Double) {
                lock.lock()
                frames.append((frame, at))
                lock.unlock()
            }

            func all() -> [(frame: ScreenEncoder.Frame, at: Double)] {
                lock.lock()
                defer { lock.unlock() }
                return frames
            }
        }
        let sink = Sink()
        let clock = Date()
        encoder.onFrame = { frame in sink.add(frame, at: Date().timeIntervalSince(clock)) }

        guard let transfer = makeTransfer() else {
            report.check("pixel transfer", false)
            return
        }
        var sources: [Int: [UInt8]] = [:]
        var requestedWhileMoving = 0.0
        var requestedWhileStill = 0.0

        let moving = 150
        let still = 60
        for index in 0 ..< moving + still {
            let started = Date()
            if index < moving {
                guard let buffer = makePicture(index: index, transfer: transfer) else {
                    report.check("pictures", false)
                    return
                }
                if index % 10 == 0 { sources[index] = luma(buffer) }
                encoder.submit(buffer)
            }
            if index == 90 {
                requestedWhileMoving = Date().timeIntervalSince(clock)
                encoder.requestKeyframe()
            }
            if index == moving + 25 {
                requestedWhileStill = Date().timeIntervalSince(clock)
                encoder.requestKeyframe()
            }
            if index == 110 { encoder.setBitrate(1_000_000) }
            let spent = Date().timeIntervalSince(started)
            try? await Task.sleep(for: .seconds(max(0, 1.0 / Double(fps) - spent)))
        }
        try? await Task.sleep(for: .milliseconds(500))
        encoder.invalidate()

        let output = sink.all()
        var stream = [UInt8]()
        for entry in output { stream += entry.frame.data }
        try? Data(stream).write(to: directory.appendingPathComponent("screen.h264"))

        let stats = encoder.stats
        report.note("submitted \(stats.submitted), encoded \(stats.encoded), dropped \(stats.dropped), failed \(stats.failed), keyframes \(stats.keyframes), \(stats.bytes) bytes")

        // The shape of the stream.
        report.check("frames came out", output.count >= moving, "\(output.count) frames")
        report.check("first frame is a keyframe", output.first?.frame.keyframe == true)
        let keyframes = output.filter(\.frame.keyframe)
        let allWithSets = keyframes.allSatisfy { H264.parameterSets(in: $0.frame.data) != nil }
        report.check("every keyframe carries SPS and PPS", allWithSets, "\(keyframes.count) keyframes")
        report.check("keyframe flag agrees with the stream", output.allSatisfy { $0.frame.keyframe == H264.isKeyframe($0.frame.data) })
        if let sets = output.first.flatMap({ H264.parameterSets(in: $0.frame.data) }), let size = H264.dimensions(ofSPS: sets.sps) {
            report.check("SPS says \(width)x\(height)", size.width == width && size.height == height, "\(size.width)x\(size.height)")
        } else {
            report.check("SPS can be read", false)
        }
        var increasing = true
        for pair in zip(output, output.dropFirst()) where pair.1.frame.ptsUs <= pair.0.frame.ptsUs { increasing = false }
        report.check("presentation times increase", increasing)
        let units = H264.accessUnits(inStream: stream)
        report.check("the stream splits back into its frames", units.count == output.count, "\(units.count) of \(output.count)")

        // A keyframe on request.
        let afterMoving = output.first { $0.frame.keyframe && $0.at > requestedWhileMoving && $0.at < requestedWhileMoving + 1.0 }
        if let afterMoving {
            let delay = afterMoving.at - requestedWhileMoving
            report.check("keyframe on request while moving", delay < 0.25, String(format: "%.0f ms", delay * 1000))
        } else {
            report.check("keyframe on request while moving", false, "none within a second")
        }
        let afterStill = output.first { $0.frame.keyframe && $0.at > requestedWhileStill && $0.at < requestedWhileStill + 1.0 }
        if let afterStill {
            let delay = afterStill.at - requestedWhileStill
            report.check("keyframe on request while still", delay < 0.35, String(format: "%.0f ms", delay * 1000))
        } else {
            report.check("keyframe on request while still", false, "none within a second")
        }
        let stillStart = Double(moving) / Double(fps)
        let quiet = output.filter { $0.at > stillStart + 0.3 }.count
        report.check("a still screen keeps producing frames", quiet >= 4, "\(quiet) frames in the still part")
        let seconds = (output.last?.at ?? 1) - (output.first?.at ?? 0)
        let rate = Double(stats.bytes) * 8 / max(seconds, 1)
        report.note(String(format: "average %.2f Mbit/s over %.1f s", rate / 1e6, seconds))
        report.check("bitrate is in the neighbourhood of the target", rate > 100_000 && rate < 8_000_000)

        // Decode, from the start and from a later keyframe.
        let decoder = Decoder(report: report)
        let all = decoder.decode(output.map(\.frame.data))
        report.check("decodes from the first frame", all.count >= output.count - 2, "\(all.count) pictures of \(output.count)")
        if let late = keyframes.dropFirst().first, let position = output.firstIndex(where: { $0.frame.ptsUs == late.frame.ptsUs }) {
            let joined = Decoder(report: report).decode(output[position...].map(\.frame.data))
            report.check("decodes when joined at a later keyframe", joined.count >= output.count - position - 2, "\(joined.count) pictures from frame \(position)")
        }
        var worst = 99.0
        var compared = 0
        for decoded in all {
            guard let index = readCounter(decoded), let source = sources[index] else { continue }
            let value = psnr(source, luma(decoded))
            worst = min(worst, value)
            compared += 1
        }
        report.note(String(format: "PSNR over %d compared pictures, worst %.1f dB", compared, worst))
        report.check("what comes out looks like what went in", compared >= 5 && worst > 28, String(format: "%.1f dB", worst))
    }

    // MARK: Pure helpers

    private static func pureChecks(_ report: Report) {
        // Annex B from length prefixed: two NAL units, four byte lengths.
        let avcc: [UInt8] = [0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 9]
        let converted = avcc.withUnsafeBytes { H264.annexB(lengthPrefixed: $0, lengthSize: 4, leading: [[0x67, 7], [0x68, 8]]) }
        report.check("Annex B conversion", converted == [0, 0, 0, 1, 0x67, 7, 0, 0, 0, 1, 0x68, 8, 0, 0, 0, 1, 0x65, 1, 2, 0, 0, 0, 1, 0x41, 9])
        let damaged: [UInt8] = [0, 0, 0, 9, 0x65, 1]
        report.check("a damaged buffer is refused", damaged.withUnsafeBytes { H264.annexB(lengthPrefixed: $0, lengthSize: 4) } == nil)
        let stream: [UInt8] = [0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 0x88, 5, 0, 0, 0, 1, 0x41, 0x9A, 6, 0, 0, 0, 1, 0x41, 0x9A, 7]
        report.check("access units are cut where a picture starts", H264.accessUnits(inStream: stream).count == 3)
        report.check("a keyframe is told from the NAL type", H264.isKeyframe(Array(stream[0 ..< 20])) && !H264.isKeyframe(Array(stream[20...])))

        let wide = ScreenGeometry.fit(source: CGSize(width: 3024, height: 1964), maxWidth: 0, maxHeight: 0)
        report.check("a large display is brought down to a long side of 2560", wide.width == 2560 && wide.height % 2 == 0, "\(wide.width)x\(wide.height)")
        let phone = ScreenGeometry.fit(source: CGSize(width: 3024, height: 1964), maxWidth: 1600, maxHeight: 900)
        report.check("the viewer's limit is respected and the shape kept",
                     phone.width <= 1600 && phone.height <= 900 && abs(Double(phone.width) / Double(phone.height) - 3024.0 / 1964.0) < 0.01, "\(phone.width)x\(phone.height)")
        let small = ScreenGeometry.fit(source: CGSize(width: 800, height: 600), maxWidth: 4000, maxHeight: 4000)
        report.check("a small display is never blown up", small.width == 800 && small.height == 600)
        let extended = ScreenGeometry.extendedSize(width: 2400, height: 1080)
        report.check("a screen made for a viewer has the pixels it asked for, even, and stays below 4K",
            extended.width == 2400 && extended.height == 1080 && !extended.hiDPI
            && ScreenGeometry.extendedSize(width: 5120, height: 2880).width * ScreenGeometry.extendedSize(width: 5120, height: 2880).height <= 3840 * 2160
            && ScreenGeometry.extendedSize(width: 2561, height: 1601).width % 2 == 0
            && ScreenGeometry.extendedSize(width: 2560, height: 1600).hiDPI)
        report.check("starting bitrate stays within bounds", ScreenGeometry.startingBitrate(width: 1920, height: 1080, fps: 30, requestedMax: 0) <= 24_000_000
            && ScreenGeometry.startingBitrate(width: 1920, height: 1080, fps: 30, requestedMax: 3_000_000) == 3_000_000)
        report.check("frame rate follows the bitrate down", ScreenGeometry.frameRate(for: 400_000, started: 4_000_000, base: 30) == 12
            && ScreenGeometry.frameRate(for: 3_000_000, started: 4_000_000, base: 30) == 30)
        let bounds = CGRect(x: 0, y: 0, width: 1512, height: 982)
        let corner = ScreenGeometry.point(x: 1, y: 1, in: bounds)
        report.check("the far corner stays on the display", corner.x == 1511 && corner.y == 981)
        var remainder = Remainder()
        var total: Int32 = 0
        for _ in 0 ..< 12 { total += remainder.take(0.25, 0).0 }
        report.check("small moves add up instead of vanishing", total == 3)
        report.check("HID letters and arrows map to Mac keys",
                     HidKeys.macKeyCode(forUsage: 0x04) == 0 && HidKeys.macKeyCode(forUsage: 0x1D) == 6
                         && HidKeys.macKeyCode(forUsage: 0x52) == 126 && HidKeys.macKeyCode(forUsage: 0x28) == 36
                         && HidKeys.macKeyCode(forUsage: 0xE1) == nil && HidKeys.isModifier(0xE1))
    }

    // MARK: Pictures

    private static func makeTransfer() -> VTPixelTransferSession? {
        var session: VTPixelTransferSession?
        guard VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &session) == noErr else { return nil }
        return session
    }

    private static func newBuffer(_ format: OSType) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary
        CVPixelBufferCreate(nil, width, height, format, attributes, &buffer)
        return buffer
    }

    /// A desktop-like picture that changes with `index`: text, a scrolling list, a pointer, a counter.
    private static func makePicture(index: Int, transfer: VTPixelTransferSession) -> CVPixelBuffer? {
        guard let bgra = newBuffer(kCVPixelFormatType_32BGRA), let nv12 = newBuffer(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) else { return nil }
        CVPixelBufferLockBaseAddress(bgra, [])
        defer { CVPixelBufferUnlockBaseAddress(bgra, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(bgra), width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(bgra), space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }

        context.setFillColor(CGColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(CGColor(red: 0.2, green: 0.22, blue: 0.45, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 220, height: height))
        let attributes: [NSAttributedString.Key: Any] = [
            .font: CTFontCreateWithName("Menlo" as CFString, 18, nil),
            .foregroundColor: CGColor(gray: 0.05, alpha: 1),
        ]
        // A list that scrolls: 40 lines, the window onto it moves four pixels a frame.
        context.saveGState()
        context.clip(to: CGRect(x: 260, y: 80, width: 700, height: 520))
        for line in 0 ..< 40 {
            let y = CGFloat(560 - line * 28 + (index * 4) % 28)
            let text = NSAttributedString(string: "line \(line + index / 7)  Tandem remote desktop 0123456789 abcdefghij", attributes: attributes)
            context.textPosition = CGPoint(x: 280, y: y)
            CTLineDraw(CTLineCreateWithAttributedString(text), context)
        }
        context.restoreGState()
        // A box that crosses the screen and a pointer that follows a circle.
        context.setFillColor(CGColor(red: 0.9, green: 0.3, blue: 0.2, alpha: 1))
        context.fill(CGRect(x: 1000 + (index * 3) % 240, y: 120 + (index * 2) % 400, width: 40, height: 40))
        let angle = Double(index) / 15
        let pointer = CGPoint(x: 640 + 300 * cos(angle), y: 360 + 200 * sin(angle))
        context.setFillColor(CGColor(gray: 0, alpha: 1))
        context.move(to: pointer)
        context.addLine(to: CGPoint(x: pointer.x + 12, y: pointer.y - 18))
        context.addLine(to: CGPoint(x: pointer.x + 2, y: pointer.y - 14))
        context.addLine(to: CGPoint(x: pointer.x - 4, y: pointer.y - 22))
        context.closePath()
        context.fillPath()
        // The counter: sixteen squares of 16 pixels, white for a one, black for a zero. Block based coding keeps
        // squares this size readable, which is how a decoded picture is matched with its source.
        for bit in 0 ..< 16 {
            context.setFillColor(CGColor(gray: (index >> bit) & 1 == 1 ? 1 : 0, alpha: 1))
            context.fill(CGRect(x: bit * 16, y: height - 16, width: 16, height: 16))
        }
        guard VTPixelTransferSessionTransferImage(transfer, from: bgra, to: nv12) == noErr else { return nil }
        return nv12
    }

    private static func luma(_ buffer: CVPixelBuffer) -> [UInt8] {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidthOfPlane(buffer, 0)
        let h = CVPixelBufferGetHeightOfPlane(buffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else { return [] }
        var out = [UInt8](repeating: 0, count: w * h)
        for row in 0 ..< h { memcpy(&out[row * w], base + row * stride, w) }
        return out
    }

    private static func readCounter(_ buffer: CVPixelBuffer) -> Int? {
        let plane = luma(buffer)
        let w = CVPixelBufferGetWidthOfPlane(buffer, 0)
        let h = CVPixelBufferGetHeightOfPlane(buffer, 0)
        guard plane.count == w * h, h >= 16 else { return nil }
        var value = 0
        for bit in 0 ..< 16 {
            // The squares sit on the top 16 rows of the picture.
            let sample = plane[8 * w + bit * 16 + 8]
            if sample > 128 { value |= 1 << bit }
        }
        return value
    }

    private static func psnr(_ a: [UInt8], _ b: [UInt8]) -> Double {
        guard a.count == b.count, !a.isEmpty else { return 0 }
        var sum = 0.0
        for index in 0 ..< a.count {
            let d = Double(a[index]) - Double(b[index])
            sum += d * d
        }
        let mse = sum / Double(a.count)
        return mse == 0 ? 99 : 10 * log10(255 * 255 / mse)
    }

    // MARK: Decoding

    /// What a viewer does: Annex B in, pictures out. The session is made from the parameter sets in the first keyframe.
    private final class Decoder {
        let report: Report
        init(report: Report) { self.report = report }

        func decode(_ frames: [[UInt8]]) -> [CVPixelBuffer] {
            guard let first = frames.first(where: { H264.isKeyframe($0) }), let sets = H264.parameterSets(in: first) else {
                report.check("decoder gets parameter sets", false)
                return []
            }
            var format: CMVideoFormatDescription?
            let made = sets.sps.withUnsafeBufferPointer { sps in
                sets.pps.withUnsafeBufferPointer { pps in
                    var pointers: [UnsafePointer<UInt8>] = [sps.baseAddress!, pps.baseAddress!]
                    var sizes = [sets.sps.count, sets.pps.count]
                    return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                        allocator: nil, parameterSetCount: 2, parameterSetPointers: &pointers, parameterSetSizes: &sizes,
                        nalUnitHeaderLength: 4, formatDescriptionOut: &format
                    )
                }
            }
            guard made == noErr, let format else {
                report.check("decoder format", false, "\(made)")
                return []
            }
            var session: VTDecompressionSession?
            let attributes = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange] as CFDictionary
            guard VTDecompressionSessionCreate(
                allocator: nil, formatDescription: format, decoderSpecification: nil, imageBufferAttributes: attributes,
                outputCallback: nil, decompressionSessionOut: &session
            ) == noErr, let session else {
                report.check("decoder session", false)
                return []
            }
            let lock = NSLock()
            var pictures: [CVPixelBuffer] = []
            var failures = 0
            var waiting = true
            for frame in frames {
                // Frames before the first keyframe cannot be decoded, a late joiner skips them.
                if waiting {
                    if !H264.isKeyframe(frame) { continue }
                    waiting = false
                }
                let payload = H264.lengthPrefixed(fromAnnexB: frame)
                var block: CMBlockBuffer?
                guard CMBlockBufferCreateWithMemoryBlock(
                    allocator: nil, memoryBlock: nil, blockLength: payload.count, blockAllocator: nil, customBlockSource: nil,
                    offsetToData: 0, dataLength: payload.count, flags: 0, blockBufferOut: &block
                ) == noErr, let block,
                    CMBlockBufferReplaceDataBytes(with: payload, blockBuffer: block, offsetIntoDestination: 0, dataLength: payload.count) == noErr
                else { failures += 1; continue }
                var sample: CMSampleBuffer?
                var size = payload.count
                var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .zero, decodeTimeStamp: .invalid)
                guard CMSampleBufferCreateReady(
                    allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1, sampleTimingEntryCount: 1,
                    sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample
                ) == noErr, let sample else { failures += 1; continue }
                let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: nil) { status, _, image, _, _ in
                    lock.lock()
                    if status == noErr, let image { pictures.append(image) } else { failures += 1 }
                    lock.unlock()
                }
                if status != noErr { failures += 1 }
            }
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            VTDecompressionSessionInvalidate(session)
            if failures > 0 { report.note("decoder failures: \(failures)") }
            return pictures
        }
    }
}
