import AVFoundation
import Foundation
import TandemCore

/// On which side of this Mac a device stands, found by ear (see `side.rs` in the core): this Mac plays a sound from its left and its right
/// speaker, the device records it and says which side was louder. The side is kept per device and used for a second screen (which goes
/// on that side) and for the shared mouse (the computer is put on that side of this screen).
@MainActor
enum DeviceSides {
    private static let key = "deviceSides"
    private static let soundKey = "sideSound"

    static func side(of id: String) -> String? {
        (UserDefaults.standard.dictionary(forKey: key) as? [String: String])?[id]
    }

    static func set(_ side: String, for id: String) {
        var all = (UserDefaults.standard.dictionary(forKey: key) as? [String: String]) ?? [:]
        all[id] = side
        UserDefaults.standard.set(all, forKey: key)
        apply(side, to: id)
    }

    /// The sound that is played, chosen in the settings of the shared mouse. The sweep holds up best in a room.
    static var sound: TandemSideSound {
        get {
            switch UserDefaults.standard.string(forKey: soundKey) {
            case "chime": .chime
            case "beep": .beep
            default: .sweep
            }
        }
        set {
            let name: String = switch newValue {
            case .sweep: "sweep"
            case .chime: "chime"
            case .beep: "beep"
            }
            UserDefaults.standard.set(name, forKey: soundKey)
        }
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

/// Plays the sound of `side.rs` once, from the left and the right speaker.
enum SideTone {
    static func play(_ sound: TandemSideSound) async {
        let rate = 48_000.0
        let samples = tandemSidePattern(sound: sound, rate: UInt32(rate))
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
    /// Plays the sound and asks the device what it heard. Remembers and applies the side when it is clear.
    @MainActor
    static func find(_ device: TandemDevice) async -> TandemSide {
        guard let engine = EngineModel.shared.tandem else { return .notHeard }
        let sound = DeviceSides.sound
        // The pattern starts with a moment of silence, which is the time the device has to start recording.
        async let answer: TandemSide? = try? engine.sideProbe(target: device.id, sound: sound)
        await SideTone.play(sound)
        let side = await answer ?? .notHeard
        switch side {
        case .left: DeviceSides.set("left", for: device.id)
        case .right: DeviceSides.set("right", for: device.id)
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

/// The other side of it: another device plays, this Mac records and says how loud each side was.
enum SideListener {
    static func listen(from peer: String, id: UInt64, sound: TandemSideSound, engine: TandemEngine) {
        Task.detached(priority: .userInitiated) {
            let levels = await record(sound: sound)
            try? await engine.sideHeard(target: peer, id: id, levels: levels)
        }
    }

    private static func record(sound: TandemSideSound) async -> TandemSideLevels? {
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
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
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
        try? await Task.sleep(for: .milliseconds(Int(tandemSideListenMs())))
        input.removeTap(onBus: 0)
        engine.stop()
        return tandemSideAnalyze(sound: sound, samples: collected.all(), rate: UInt32(format.sampleRate))
    }

    private final class Collected: @unchecked Sendable {
        private let lock = NSLock()
        private var samples: [Float] = []
        func append(_ more: [Float]) { lock.lock(); samples.append(contentsOf: more); lock.unlock() }
        func all() -> [Float] { lock.lock(); defer { lock.unlock() }; return samples }
    }
}
