import AppKit
import Foundation
import TandemCore

/// The recording the debug harness plays: what an encoder on the phone produced, one access unit after the other.
///
///     "TFD1"                          4 bytes
///     width, height, rotation         3 x u16, big-endian
///     then for every frame:
///       length                        u32, big-endian
///       flags                         u8, bit 0 is a keyframe
///       pts in microseconds           u64, big-endian
///       payload                       Annex B, as the core would hand it over
struct FrameDump {
    struct Frame {
        var keyframe: Bool
        var ptsUs: UInt64
        var data: Data
    }

    var width: Int
    var height: Int
    var rotation: Int
    var frames: [Frame]

    init?(contentsOf url: URL) {
        guard let data = try? Data(contentsOf: url) else { return nil }
        self.init(data: data)
    }

    init?(data: Data) {
        guard data.count >= 10, data.prefix(4) == Data("TFD1".utf8) else { return nil }
        func u16(_ at: Int) -> Int { Int(data[at]) << 8 | Int(data[at + 1]) }
        width = u16(4)
        height = u16(6)
        rotation = u16(8)
        frames = []
        var position = 10
        while position + 13 <= data.count {
            var length = 0
            for byte in data[position..<position + 4] { length = length << 8 | Int(byte) }
            let flags = data[position + 4]
            var pts: UInt64 = 0
            for byte in data[position + 5..<position + 13] { pts = pts << 8 | UInt64(byte) }
            position += 13
            guard position + length <= data.count else { break }
            frames.append(Frame(keyframe: flags & 1 == 1, ptsUs: pts, data: data.subdata(in: position..<position + length)))
            position += length
        }
    }
}

/// Plays a recording into a real window and writes what came out, so the decoder and the window can be checked without a
/// phone and without recording the screen. Variables (all optional, next to `TANDEM_DEBUG_LIVE`):
///
/// - `TANDEM_DEBUG_LIVE_KIND=screen|camera`, `TANDEM_DEBUG_LIVE_NAME=<phone name>`, `TANDEM_DEBUG_LIVE_MIRROR=1`
/// - `TANDEM_DEBUG_LIVE_STATE=requesting|declined|refused|stopped|phoneStopped|lost|noAnswer|busy|unavailable`: show that
///   state instead of playing
/// - `TANDEM_DEBUG_LIVE_STATS=1`, `TANDEM_DEBUG_LIVE_FRAMELESS=1`, `TANDEM_DEBUG_LIVE_OFFSCREEN=1`, `TANDEM_DEBUG_LIVE_QUIT=1`
@MainActor
enum LiveDebug {
    private static func variable(_ name: String) -> String? { ProcessInfo.processInfo.environment[name] }

    static func run(path: String, directory: URL) {
        let kind: TandemMediaKind = variable("TANDEM_DEBUG_LIVE_KIND") == "camera" ? .camera : .screen
        let name = variable("TANDEM_DEBUG_LIVE_NAME") ?? "Pixel 9"
        let mirrored = variable("TANDEM_DEBUG_LIVE_MIRROR") == "1"
        let dump = FrameDump(contentsOf: URL(fileURLWithPath: path))
        let stateName = variable("TANDEM_DEBUG_LIVE_STATE")
        let phase = stateName.flatMap(Self.phase(named:)) ?? .active
        let session = LiveManager.shared.debugOpen(
            kind: kind, name: name, width: dump?.width ?? 1080, height: dump?.height ?? 2400, rotation: dump?.rotation ?? 0,
            mirrored: mirrored, phase: phase
        )
        session.showStats = variable("TANDEM_DEBUG_LIVE_STATS") == "1"
        guard let controller = LiveManager.shared.debugController(session) else { return }
        controller.contentSizeChanged()
        if variable("TANDEM_DEBUG_LIVE_FRAMELESS") == "1" { controller.setFrameless(true) }
        if variable("TANDEM_DEBUG_LIVE_OFFSCREEN") == "1" { controller.window.setFrameOrigin(NSPoint(x: -6000, y: 200)) }

        guard phase == .active else {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.2))
                writeWindow(controller, directory: directory, name: "live-window.png")
                finish()
            }
            return
        }
        guard let dump else {
            report("could not read \(path)\n", to: directory)
            finish()
            return
        }

        let decoder = H264Decoder()
        let started = Date()
        let firstPicture = Box<TimeInterval?>(nil)
        decoder.onImage = { [weak session] image, _ in
            session?.surface.show(image)
            if firstPicture.value == nil { firstPicture.value = Date().timeIntervalSince(started) }
            if session?.firstPicture.exchange(true) == false {
                Task { @MainActor in session?.hasPicture = true }
            }
        }

        Task.detached {
            var last: UInt64 = dump.frames.first?.ptsUs ?? 0
            for frame in dump.frames {
                let wait = Double(frame.ptsUs &- last) / 1_000_000
                if wait > 0 && wait < 1 { try? await Task.sleep(for: .seconds(wait)) }
                last = frame.ptsUs
                decoder.decode(frame.data, ptsUs: frame.ptsUs, keyframe: frame.keyframe, discontinuity: false)
            }
            try? await Task.sleep(for: .seconds(0.8))
            await MainActor.run {
                let counters = decoder.counters
                var lines = [
                    "frames in the recording: \(dump.frames.count)",
                    "keyframes: \(dump.frames.filter(\.keyframe).count)",
                    "declared size: \(dump.width) x \(dump.height), rotation \(dump.rotation)",
                    "decoder: in \(counters.framesIn), decoded \(counters.framesDecoded), dropped \(counters.framesDropped), errors \(counters.errors)",
                    String(format: "average decode: %.2f ms", counters.averageDecodeMilliseconds),
                    String(format: "first picture after: %.3f s", firstPicture.value ?? -1),
                ]
                if let first = dump.frames.first(where: \.keyframe), let sps = AnnexB.parse(first.data).sps,
                   let size = AnnexB.dimensions(ofSPS: sps) {
                    lines.append("size from the stream: \(size.width) x \(size.height)")
                }
                if let image = session.surface.renderedImage(), let tiff = image.tiffRepresentation,
                   let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                    try? png.write(to: directory.appendingPathComponent("live-frame.png"))
                    lines.append("picture on screen: \(Int(image.size.width)) x \(Int(image.size.height))")
                } else {
                    lines.append("picture on screen: none")
                }
                writeWindow(controller, directory: directory, name: "live-window.png")
                let window = controller.window!
                lines.append("window frame: \(window.frame), content: \(window.contentView?.frame ?? .zero), layout rect: \(window.contentLayoutRect)")
                lines.append("screen visible frame: \(window.screen?.visibleFrame ?? .zero)")
                report(lines.joined(separator: "\n") + "\n", to: directory)
                finish()
            }
        }
    }

    private static func writeWindow(_ controller: LiveWindowController, directory: URL, name: String) {
        if let png = controller.snapshotPNG() { try? png.write(to: directory.appendingPathComponent(name)) }
    }

    private static func report(_ text: String, to directory: URL) {
        try? text.write(to: directory.appendingPathComponent("live-report.txt"), atomically: true, encoding: .utf8)
    }

    private static func finish() {
        if variable("TANDEM_DEBUG_LIVE_QUIT") == "1" { NSApp.terminate(nil) }
    }

    private static func phase(named name: String) -> LivePhase? {
        switch name {
        case "requesting": .requesting
        case "declined": .ended(.declined)
        case "refused": .ended(.refused)
        case "stopped": .ended(.stopped)
        case "phoneStopped": .ended(.phoneStopped)
        case "lost": .ended(.lost)
        case "noAnswer": .ended(.noAnswer)
        case "busy": .ended(.busy)
        case "unavailable": .ended(.unavailable)
        default: nil
        }
    }
}

final class Box<Value>: @unchecked Sendable {
    var value: Value
    init(_ value: Value) { self.value = value }
}
