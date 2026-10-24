import AVFoundation
import Foundation
import TandemCore

/// On which side of this Mac a device stands, found by ear (see `side.rs` in the core): this Mac plays a sweep from its left and its right
/// speaker, the device records it and says which side was louder. The side is kept per device and used for a second screen (which goes
/// on that side) and for the shared mouse (the computer is put on that side of this screen).
@MainActor
enum DeviceSides {
    private static let key = "deviceSides"
    private static let askKey = "deviceSidesAsk"

    static func side(of id: String) -> String? {
        (UserDefaults.standard.dictionary(forKey: key) as? [String: String])?[id]
    }

    /// Puts the device on that side now; `remember` keeps it for next time, otherwise the next second screen asks again.
    static func set(_ side: String, for id: String, remember: Bool = true) {
        var all = (UserDefaults.standard.dictionary(forKey: key) as? [String: String]) ?? [:]
        var ask = Set(UserDefaults.standard.stringArray(forKey: askKey) ?? [])
        if remember {
            all[id] = side
            ask.remove(id)
        } else {
            all[id] = nil
            ask.insert(id)
        }
        UserDefaults.standard.set(all, forKey: key)
        UserDefaults.standard.set(Array(ask), forKey: askKey)
        apply(side, to: id)
    }

    /// Puts the device on that side: a computer next to this screen for the shared mouse, and a second screen that runs for it.
    private static func apply(_ side: String, to id: String) {
        let share = PointerShare.shared
        if let device = EngineModel.shared.device(id), device.isComputer || device.caps.contains("pointer.in") {
            // The offset along the edge stays when the edge does not change.
            let offset = share.layout[id]?.edge == side ? share.layout[id]?.offset : nil
            share.layout[id] = PointerShare.Placement(edge: side, offset: offset)
        }
        ScreenHost.shared.placeExtended(for: id, onLeft: side == "left")
    }
}

/// Plays the sweeps of `side.rs` once, from the left and the right speaker.
enum SideTone {
    static func play(loudness: Float = 1) async {
        let rate = 48_000.0
        let samples = tandemSidePattern(rate: UInt32(rate), loudness: loudness)
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count / 2)),
              let channels = buffer.floatChannelData
        else { return }
        let frames = samples.count / 2
        buffer.frameLength = AVAudioFrameCount(frames)
        for i in 0..<frames {
            channels[0][i] = samples[i * 2]
            channels[1][i] = samples[i * 2 + 1]
        }
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { return }
        player.play()
        await player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack)
        player.stop()
        engine.stop()
    }
}

enum SideFinder {
    @MainActor private static var busy: Set<String> = []

    /// Asks the device to listen, plays the sweeps once it does, and reads its answer. Soft first, so a quiet room is not startled; when it
    /// was not heard well, once more at full loudness. Applies the side when it is clear; `remember` keeps it for next time.
    @MainActor
    static func find(_ device: TandemDevice, remember: Bool = true) async -> TandemSide {
        guard let engine = EngineModel.shared.tandem, !busy.contains(device.id) else { return .notHeard }
        busy.insert(device.id)
        defer { busy.remove(device.id) }
        var side = TandemSide.notHeard
        for loudness in [tandemSideFirstLoudness(), 1] {
            guard let id = try? await engine.sideStart(target: device.id) else { return .notHeard }
            await SideTone.play(loudness: loudness)
            side = await engine.sideResult(id: id)
            if side == .left || side == .right { break }
        }
        switch side {
        case .left: DeviceSides.set("left", for: device.id, remember: remember)
        case .right: DeviceSides.set("right", for: device.id, remember: remember)
        default: break
        }
        return side
    }

    /// What to tell the person after `find`.
    @MainActor
    static func message(_ side: TandemSide, name: String) -> String {
        switch side {
        case .left: String(localized: "\(name) stands on the left")
        case .right: String(localized: "\(name) stands on the right")
        case .unsure: String(localized: "Both sides sounded about the same. Put \(name) more to one side, or choose the side yourself.")
        case .notHeard: String(localized: "\(name) did not hear it. Turn the sound of this Mac up, and let Tandem use the microphone there.")
        }
    }
}

/// The other side of it: another computer plays, this Mac records and says how loud each side was.
enum SideListener {
    static func listen(from peer: String, id: UInt64, engine: TandemEngine) {
        Task.detached(priority: .userInitiated) {
            let levels = await record { try? await engine.sideReady(target: peer, id: id) }
            try? await engine.sideHeard(target: peer, id: id, levels: levels)
        }
    }

    /// Records for as long as the core says, after telling the other side that the microphone is on (`ready`).
    private static func record(ready: @escaping @Sendable () async -> Void) async -> TandemSideLevels? {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .audio) else { return nil }
        default: return nil
        }
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return nil }
        let collected = Collected()
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, _ in
            guard let data = buffer.floatChannelData else { return }
            let channels = Int(buffer.format.channelCount)
            let frames = Int(buffer.frameLength)
            var mono = [Float](repeating: 0, count: frames)
            for c in 0..<channels {
                for i in 0..<frames { mono[i] += data[c][i] / Float(channels) }
            }
            collected.append(mono)
        }
        do { try engine.start() } catch { return nil }
        await ready()
        try? await Task.sleep(for: .milliseconds(Int(tandemSideListenMs())))
        input.removeTap(onBus: 0)
        engine.stop()
        return tandemSideAnalyze(samples: collected.all(), rate: UInt32(format.sampleRate))
    }

    private final class Collected: @unchecked Sendable {
        private let lock = NSLock()
        private var samples: [Float] = []
        func append(_ more: [Float]) { lock.lock(); samples.append(contentsOf: more); lock.unlock() }
        func all() -> [Float] { lock.lock(); defer { lock.unlock() }; return samples }
    }
}
