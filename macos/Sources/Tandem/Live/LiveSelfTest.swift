import AppKit
import CoreVideo
import Foundation
import TandemCore

/// Checks of the live video logic that need nothing but this process: the Annex B handling, the decoder on real frames,
/// the orientation and the words. A test target would be the usual place, but the test macros of Swift only ship with
/// Xcode and this project builds with the Command Line Tools, so the checks run inside the app:
/// `TANDEM_DEBUG_LIVE_SELFTEST=1` with `TANDEM_DEBUG_DIR` set writes `selftest.txt` there and quits.
@MainActor
enum LiveSelfTest {
    private static var failures: [String] = []
    private static var passed = 0

    private static func check(_ condition: Bool, _ name: String) {
        if condition {
            passed += 1
        } else {
            failures.append(name)
        }
    }

    static func run(directory: URL) {
        Task { @MainActor in
            annexB()
            recording()
            orientation()
            words()
            await decoder()
            let text = (failures.isEmpty ? "all passed" : "FAILED") + ": \(passed) checks passed, \(failures.count) failed\n"
                + failures.map { "failed: \($0)\n" }.joined()
            try? text.write(to: directory.appendingPathComponent("selftest.txt"), atomically: true, encoding: .utf8)
            NSApp.terminate(nil)
        }
    }

    // MARK: Annex B

    private static func annexB() {
        let mixed = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9, 9])
        let units = AnnexB.units(in: mixed)
        check(units == [Data([0x67, 1, 2]), Data([0x68, 3]), Data([0x65, 9, 9, 9])], "three and four byte start codes")
        check(units.map(AnnexB.type(of:)) == [7, 8, 5], "NAL types")
        check(AnnexB.units(in: Data([0, 0, 1, 0x41, 5, 0, 0, 0, 0, 1, 0x41, 6])) == [Data([0x41, 5]), Data([0x41, 6])], "zero bytes belong to the next start code")
        check(AnnexB.units(in: Data()).isEmpty && AnnexB.units(in: Data([1, 2, 3, 4, 5])).isEmpty && AnnexB.units(in: Data([0, 0, 1])).isEmpty, "empty and garbage")

        let key = AnnexB.parse(Data([0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 7, 7]))
        check(key.sps == Data([0x67, 1]) && key.pps == Data([0x68, 2]), "parameter sets are found")
        check(key.pictures == [Data([0x65, 7, 7])] && key.isKeyframe, "a keyframe and its picture")
        let delta = AnnexB.parse(Data([0, 0, 0, 1, 0x41, 1, 2, 3]))
        check(!delta.isKeyframe && delta.pictures.count == 1 && delta.sps == nil, "a plain picture is no keyframe")

        check(AnnexB.lengthPrefixed([Data([0x65, 1, 2]), Data([0x41])]) == Data([0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 1, 0x41]), "length prefixes are big-endian")
        check(AnnexB.unescaped(Data([0x67, 0, 0, 3, 1, 0, 0, 3])) == [0x67, 0, 0, 1, 0, 0], "emulation prevention bytes go")
        check(AnnexB.unescaped(Data([0x67, 0, 3, 1])) == [0x67, 0, 3, 1], "a lone 3 after one zero stays")

        if let dump = FrameDump(data: TinyStream.data), let first = dump.frames.first, let sps = AnnexB.parse(first.data).sps {
            let size = AnnexB.dimensions(ofSPS: sps)
            check(size?.width == 96 && size?.height == 64, "the size comes out of a real parameter set")
            check(AnnexB.dimensions(ofSPS: sps.prefix(5)) == nil, "a cut-off parameter set gives nothing")
        } else {
            check(false, "the tiny stream has a parameter set")
        }

        var reader = BitReader([0b1010_0110, 0b0100_0010, 0b1000_0000])
        check([reader.ue(), reader.ue(), reader.ue(), reader.ue(), reader.ue()] == [0, 1, 2, 3, 4], "Exp-Golomb numbers")
        var signed = BitReader([0b0100_1100, 0b1000_0000])
        check([signed.se(), signed.se()] == [1, -1], "signed Exp-Golomb numbers")
    }

    // MARK: Recording

    private static func recording() {
        let dump = FrameDump(data: TinyStream.data)
        check(dump?.width == 96 && dump?.height == 64 && dump?.rotation == 0, "the recording header")
        check(dump?.frames.count == 3 && dump?.frames[0].keyframe == true && dump?.frames[1].keyframe == false, "the recording frames")
        check(FrameDump(data: Data("nope".utf8)) == nil && FrameDump(data: Data()) == nil, "something else is refused")
    }

    // MARK: Orientation

    private static func orientation() {
        var value = VideoOrientation(phoneRotation: 90, extraRotation: 0, mirrored: false)
        check(value.degrees == 90 && value.swapsAxes, "a quarter turn swaps the axes")
        let shown = value.shown(width: 1280, height: 720)
        check(shown.width == 720 && shown.height == 1280, "the turned size")
        value.extraRotation = 270
        check(value.degrees == 0 && !value.swapsAxes, "turns add up")
        value.phoneRotation = 180
        value.extraRotation = 90
        check(value.degrees == 270, "180 and 90 is 270")
        check(VideoOrientation(phoneRotation: -90).degrees == 270, "a negative turn is brought into range")
    }

    // MARK: Words

    private static func words() {
        let reasons: [TandemMediaEnd] = [
            .ended, .declined, .policy, .busy, .unsupported, .unavailable, .timeout, .replaced, .unknownSession, .peerGone, .error,
        ]
        var titles = Set<String>()
        for reason in reasons {
            let copy = LiveCopy.ended(LiveEnd(reason, byMe: false), name: "Pixel", kind: .screen)
            check(!copy.title.isEmpty, "a message for \(reason)")
            titles.insert(copy.title)
        }
        check(titles.count == 9, "nine different messages for eleven reasons")
        check([TandemMediaEnd.ended, .peerGone, .error, .timeout].allSatisfy { LiveEnd($0, byMe: true) == .stopped }, "a stop from here is never the phone's")
        check(LiveEnd(.ended, byMe: false) == .phoneStopped, "a stop by the phone")
        check(!LiveCopy.ended(.refused, name: "P", kind: .camera).canRetry && !LiveCopy.ended(.unsupported, name: "P", kind: .camera).canRetry, "no retry when it will not work")
        check(LiveCopy.ended(.declined, name: "P", kind: .camera).canRetry && LiveCopy.ended(.lost, name: "P", kind: .screen).canRetry, "retry when it may")
        check(LiveCopy.requesting(name: "Pixel", kind: .camera).detail.localizedCaseInsensitiveContains("camera"), "the camera is named while waiting")
        check(LiveView.webcamNote.localizedCaseInsensitiveContains("not a virtual webcam"), "the camera says it is not a webcam")
        check(LiveQuality.high.box(for: .camera).long > LiveQuality.standard.box(for: .camera).long, "1080p is bigger than 720p")
        let box = LiveQuality.standard.box(for: .camera)
        check(box.long == 1280 && box.short == 720, "the 720p box")
        var stats = LiveStats()
        stats.fps = 29.6
        stats.megabitsPerSecond = 5.84
        check(stats.line.contains("30 fps") && stats.line.contains("5.8 Mbit/s"), "the stats line")
    }

    // MARK: Decoder

    private static func decoder() async {
        guard let dump = FrameDump(data: TinyStream.data) else { return check(false, "the tiny stream is readable") }

        // Real frames come out as pictures of the right size.
        let decoded = H264Decoder()
        let sizes = Box<[String]>([])
        let lock = NSLock()
        decoded.onImage = { image, _ in
            lock.lock()
            sizes.value.append("\(CVPixelBufferGetWidth(image))x\(CVPixelBufferGetHeight(image))")
            lock.unlock()
        }
        for frame in dump.frames { decoded.decode(frame.data, ptsUs: frame.ptsUs, keyframe: frame.keyframe, discontinuity: false) }
        for _ in 0..<100 where decoded.counters.framesDecoded < 3 { try? await Task.sleep(for: .milliseconds(50)) }
        lock.lock()
        let got = sizes.value
        lock.unlock()
        check(decoded.counters.framesDecoded == 3 && decoded.counters.errors == 0, "three frames decode without errors")
        check(got.count == 3 && got.allSatisfy { $0 == "96x64" }, "the pictures are 96 by 64")

        // Nothing before the first keyframe.
        let early = H264Decoder()
        for frame in dump.frames.dropFirst() { early.decode(frame.data, ptsUs: frame.ptsUs, keyframe: false, discontinuity: false) }
        try? await Task.sleep(for: .milliseconds(300))
        check(early.counters.framesDecoded == 0 && early.counters.framesDropped == 2, "frames before a keyframe are dropped")

        // A decoder that is behind skips and asks for a keyframe.
        let slow = H264Decoder()
        let asked = Box(0)
        slow.onNeedsKeyframe = { asked.value += 1 }
        slow.decode(dump.frames[0].data, ptsUs: 0, keyframe: true, discontinuity: false)
        for index in 1...40 {
            slow.decode(dump.frames[1].data, ptsUs: UInt64(index) * 33_000, keyframe: false, discontinuity: false)
        }
        try? await Task.sleep(for: .milliseconds(500))
        check(slow.counters.framesIn == 41 && slow.counters.framesDropped > 0, "a backlog is dropped, not shown late")
        check(asked.value >= 1, "a keyframe is asked for after dropping")
    }
}
