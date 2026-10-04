#!/usr/bin/env swift
// Makes a short H.264 recording for the live video debug harness (TANDEM_DEBUG_LIVE), in the format of FrameDump in
// macos/Sources/Tandem/Live/LiveDebug.swift. Annex B, no B-frames, parameter sets in front of every keyframe, like the
// encoder on the phone. The picture says which way is up, so a wrong turn is visible.
//
//     swift scripts/make-test-stream.swift out.tfd [width height rotation frames]
import AppKit
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    print("usage: make-test-stream.swift out.tfd [width height rotation frames]")
    exit(1)
}
let output = URL(fileURLWithPath: arguments[1])
let width = arguments.count > 2 ? Int(arguments[2])! : 540
let height = arguments.count > 3 ? Int(arguments[3])! : 1200
let rotation = arguments.count > 4 ? Int(arguments[4])! : 0
let total = arguments.count > 5 ? Int(arguments[5])! : 90

var dump = Data("TFD1".utf8)
func put16(_ value: Int) { dump.append(contentsOf: [UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]) }
put16(width)
put16(height)
put16(rotation)

let startCode: [UInt8] = [0, 0, 0, 1]

func annexB(from sample: CMSampleBuffer, keyframe: Bool) -> Data {
    var out = Data()
    if keyframe, let format = CMSampleBufferGetFormatDescription(sample) {
        for index in 0..<2 {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: index, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
            )
            if let pointer {
                out.append(contentsOf: startCode)
                out.append(pointer, count: size)
            }
        }
    }
    guard let block = CMSampleBufferGetDataBuffer(sample) else { return out }
    var length = 0
    var base: UnsafeMutablePointer<Int8>?
    CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &base)
    guard let base else { return out }
    let bytes = UnsafeRawPointer(base).assumingMemoryBound(to: UInt8.self)
    var offset = 0
    while offset + 4 <= length {
        let unit = Int(bytes[offset]) << 24 | Int(bytes[offset + 1]) << 16 | Int(bytes[offset + 2]) << 8 | Int(bytes[offset + 3])
        offset += 4
        out.append(contentsOf: startCode)
        out.append(bytes + offset, count: unit)
        offset += unit
    }
    return out
}

var session: VTCompressionSession?
let created = VTCompressionSessionCreate(
    allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
    encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: nil,
    refcon: nil, compressionSessionOut: &session
)
guard created == noErr, let session else { print("no encoder: \(created)"); exit(2) }
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 30 as CFNumber)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: 3_000_000 as CFNumber)
VTCompressionSessionPrepareToEncodeFrames(session)

func picture(_ index: Int) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, [kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary, &buffer)
    guard let buffer else { return nil }
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    guard let context = CGContext(
        data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
    ) else { return nil }
    let w = CGFloat(width), h = CGFloat(height)
    let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [
        CGColor(red: 0.16, green: 0.17, blue: 0.45, alpha: 1), CGColor(red: 0.95, green: 0.55, blue: 0.7, alpha: 1),
    ] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: h), end: CGPoint(x: w, y: 0), options: [])
    // The top edge is red and the left edge is green: after a wrong turn or a mirror it shows at once.
    context.setFillColor(CGColor(red: 0.9, green: 0.1, blue: 0.1, alpha: 1))
    context.fill(CGRect(x: 0, y: h - 28, width: w, height: 28))
    context.setFillColor(CGColor(red: 0.1, green: 0.8, blue: 0.3, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: 28, height: h))
    // Something that moves, so the frames differ.
    let t = CGFloat(index) / CGFloat(total)
    context.setFillColor(CGColor(gray: 1, alpha: 0.92))
    context.fillEllipse(in: CGRect(x: w * (0.15 + 0.55 * t), y: h * (0.2 + 0.5 * abs(sin(t * 6))), width: w * 0.2, height: w * 0.2))
    let text = NSAttributedString(string: "TOP  \(index)", attributes: [
        .font: NSFont.systemFont(ofSize: w * 0.12, weight: .bold), .foregroundColor: NSColor.white,
    ])
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    text.draw(at: NSPoint(x: w * 0.08, y: h - w * 0.28))
    NSGraphicsContext.restoreGraphicsState()
    return buffer
}

var frames = 0
for index in 0..<total {
    guard let image = picture(index) else { continue }
    let pts = CMTime(value: CMTimeValue(index), timescale: 30)
    var info = VTEncodeInfoFlags()
    let properties = index == 0 ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
    VTCompressionSessionEncodeFrame(
        session, imageBuffer: image, presentationTimeStamp: pts, duration: CMTime(value: 1, timescale: 30),
        frameProperties: properties, infoFlagsOut: &info
    ) { status, _, sample in
        guard status == noErr, let sample else { return }
        let attachments = (CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]])?.first
        let keyframe = !((attachments?[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)
        let payload = annexB(from: sample, keyframe: keyframe)
        var length = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &length) { dump.append(contentsOf: $0) }
        dump.append(keyframe ? 1 : 0)
        var micros = UInt64(CMSampleBufferGetPresentationTimeStamp(sample).seconds * 1_000_000).bigEndian
        withUnsafeBytes(of: &micros) { dump.append(contentsOf: $0) }
        dump.append(payload)
        frames += 1
    }
}
VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
try dump.write(to: output)
print("wrote \(frames) frames, \(dump.count) bytes to \(output.path)")
