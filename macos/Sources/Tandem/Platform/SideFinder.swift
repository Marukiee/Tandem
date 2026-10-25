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

    /// The person wanted to be asked each time a second screen starts (Remember was off).
    static func asksEachTime(_ id: String) -> Bool {
        UserDefaults.standard.stringArray(forKey: askKey)?.contains(id) == true
    }

    /// A check by itself found the device on `side`: what already stands left or right of this Mac moves there, and it is kept for next
    /// time unless the person wants to be asked. Nothing is put anywhere it was not: a computer that is not next to this screen stays
    /// away, and one put above or below stays there.
    static func moved(to side: String, for id: String) {
        if !asksEachTime(id) {
            var all = (UserDefaults.standard.dictionary(forKey: key) as? [String: String]) ?? [:]
            all[id] = side
            UserDefaults.standard.set(all, forKey: key)
        }
        let share = PointerShare.shared
        if let placement = share.layout[id], ["left", "right"].contains(placement.edge), placement.edge != side {
            // Left and right both count along the height of this screen, so it stays at the same height on the other side.
            share.layout[id] = PointerShare.Placement(edge: side, offset: placement.offset)
        }
        ScreenHost.shared.placeExtended(for: id, onLeft: side == "left")
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
        SideWatch.shared.chosen(side, for: id)
        apply(side, to: id)
    }

    /// Puts the device on that side: a computer next to this screen for the shared mouse, and a second screen that runs for it.
    private static func apply(_ side: String, to id: String) {
        let share = PointerShare.shared
        // A computer goes next to this screen for the shared mouse. A phone only moves when it was put there before: finding its side is
        // for a second screen, and a phone that never asked for the mouse should not become a wall the pointer bounces off.
        if let device = EngineModel.shared.device(id), device.isComputer || share.layout[id] != nil {
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
        _ = await play { tandemSidePattern(rate: $0, loudness: loudness) }
    }

    /// The quiet sweeps of a check by itself. False when there was nothing to play them with.
    static func playQuiet() async -> Bool {
        await play { tandemSideQuietPattern(rate: $0) }
    }

    /// Made at the rate the sound card runs at: a resampler on the way cuts off near the top of what it carries, where the quiet sweeps are.
    /// Always on the Mac's own speakers, whatever the sound of the Mac goes to now.
    private static func play(_ make: (UInt32) -> [Float]) async -> Bool {
        let engine = AVAudioEngine()
        if let speakers = AudioRoute.builtInSpeakers() {
            do { try engine.outputNode.auAudioUnit.setDeviceID(speakers) } catch { PointerLog.write("side: could not play on the Mac's own speakers: \(error)") }
        }
        let rate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        guard rate > 0 else { return false }
        let samples = make(UInt32(rate))
        guard !samples.isEmpty,
              let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count / 2)),
              let channels = buffer.floatChannelData
        else { return false }
        let frames = samples.count / 2
        buffer.frameLength = AVAudioFrameCount(frames)
        for i in 0..<frames {
            channels[0][i] = samples[i * 2]
            channels[1][i] = samples[i * 2 + 1]
        }
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { return false }
        player.play()
        await player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack)
        player.stop()
        engine.stop()
        return true
    }
}

/// One sound at a time from these speakers: a check by itself and a search the person started that played over each other would leave
/// both listeners hearing the other's sweeps.
@MainActor
private enum SpeakerTurn {
    private static var taken = false
    private static var waiting: [CheckedContinuation<Void, Never>] = []

    static func take() async {
        guard taken else {
            taken = true
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    static func give() {
        if waiting.isEmpty { taken = false } else { waiting.removeFirst().resume() }
    }
}

enum SideFinder {
    @MainActor private static var busy: Set<String> = []

    /// Which sweeps "Find by sound" plays: `quiet` (too high to hear, the first choice) or `audible`, for a device whose microphone does
    /// not pick up the quiet ones.
    static let soundKey = "sideSound"
    static var audibleChosen: Bool { UserDefaults.standard.string(forKey: soundKey) == "audible" }

    /// "Find by sound": the quiet sweeps first, which nobody hears and most devices pick up well. When they were not heard, it asks
    /// whether to play the audible ones, soft first and then at full loudness; with "Audible" chosen in Settings it plays those at once.
    /// Applies the side when it is clear; `remember` keeps it for next time.
    @MainActor
    static func find(_ device: TandemDevice, remember: Bool = true) async -> TandemSide {
        guard let engine = EngineModel.shared.tandem, !busy.contains(device.id) else { return .notHeard }
        busy.insert(device.id)
        defer { busy.remove(device.id) }
        await SpeakerTurn.take()
        defer { SpeakerTurn.give() }
        var side = TandemSide.notHeard
        var audible = audibleChosen
        lastProblem = nil
        guard AudioRoute.speakersCanPlay() else {
            PointerLog.write("side: \(device.name): nothing played, the Mac's own speakers are off, nearly off, or headphones are plugged in")
            lastProblem = .speakers
            return .notHeard
        }
        if !audible {
            do {
                PointerLog.write("side: \(device.name): asked to listen for the quiet sound")
                let id = try await engine.sideCheckStart(target: device.id)
                let played = await SideTone.playQuiet()
                let heard = await engine.sideCheckResult(id: id)
                PointerLog.write("side: \(device.name): played \(played), heard \(heard)")
                if played { side = heard }
            } catch {
                PointerLog.write("side: \(device.name): did not start listening: \(error)")
                lastProblem = .notListening
                return .notHeard
            }
            if side != .left && side != .right {
                audible = await askForAudible(name: device.name)
            }
        }
        if audible {
            for loudness in [tandemSideFirstLoudness(), 1] {
                do {
                    PointerLog.write("side: \(device.name): asked to listen, audible at \(loudness)")
                    let id = try await engine.sideStart(target: device.id)
                    await SideTone.play(loudness: loudness)
                    side = await engine.sideResult(id: id)
                    PointerLog.write("side: \(device.name): heard \(side)")
                } catch {
                    PointerLog.write("side: \(device.name): did not start listening: \(error)")
                    lastProblem = .notListening
                    return .notHeard
                }
                if side == .left || side == .right { break }
            }
        }
        switch side {
        case .left: DeviceSides.set("left", for: device.id, remember: remember)
        case .right: DeviceSides.set("right", for: device.id, remember: remember)
        default: break
        }
        return side
    }

    /// The quiet sweeps were not heard: may the audible ones play? Not now when nobody answers.
    @MainActor
    private static func askForAudible(name: String) async -> Bool {
        await withCheckedContinuation { continuation in
            var answered = false
            let answer: (Bool) -> Void = { yes in
                guard !answered else { return }
                answered = true
                continuation.resume(returning: yes)
            }
            GlassQuestion.shared.ask(
                symbol: "speaker.wave.2",
                title: String(localized: "\(name) did not hear the quiet sound"),
                body: String(localized: "Some microphones hear less up high. The audible sound is a short sweep you can hear."),
                answers: [
                    GlassQuestion.Answer(title: "Not now") { answer(false) },
                    GlassQuestion.Answer(title: "Use audible sound", prominent: true) { answer(true) },
                ],
                closeAfter: .seconds(30)
            )
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(31))
                answer(false)
            }
        }
    }

    /// Once, with the quiet sweeps, for a check by itself: nothing is applied, and nothing is played when this Mac's sound would not come
    /// out of its own speakers (see `AudioRoute`).
    @MainActor
    static func check(_ device: TandemDevice) async -> TandemSide {
        guard let engine = EngineModel.shared.tandem, !busy.contains(device.id) else { return .notHeard }
        busy.insert(device.id)
        defer { busy.remove(device.id) }
        await SpeakerTurn.take()
        defer { SpeakerTurn.give() }
        guard AudioRoute.speakersCanPlay(), let id = try? await engine.sideCheckStart(target: device.id) else { return .notHeard }
        let played = await SideTone.playQuiet()
        // Read either way, so the core lets go of the answer it waits for.
        let side = await engine.sideCheckResult(id: id)
        return played ? side : .notHeard
    }

    /// Why the last `find` found nothing, when it was not that the sound was not heard.
    enum Problem { case notListening, speakers }
    @MainActor static var lastProblem: Problem?

    /// What to tell the person after `find`.
    @MainActor
    static func message(_ side: TandemSide, name: String) -> String {
        if side == .notHeard, let problem = lastProblem {
            switch problem {
            case .notListening:
                return String(localized: "\(name) did not start listening. Its microphone may be in use by another app or not allowed for Tandem, or the phone is not reachable right now.")
            case .speakers:
                return String(localized: "The speakers of this Mac are off or very low, or headphones are plugged in. Turn them up and try again.")
            }
        }
        return switch side {
        case .left: String(localized: "\(name) stands on the left")
        case .right: String(localized: "\(name) stands on the right")
        case .unsure: String(localized: "Both sides sounded about the same. Put \(name) more to one side, or choose the side yourself.")
        case .notHeard: String(localized: "\(name) did not hear it. Turn the sound of this Mac up, and let Tandem use the microphone there.")
        }
    }
}

/// The other side of it: another computer plays, this Mac records and says how loud each side was.
enum SideListener {
    /// `quiet`: the other computer checks by itself. Then the microphone is not asked for, and not used while something else has it on;
    /// without a word that it listens, the other one plays nothing.
    static func listen(from peer: String, id: UInt64, quiet: Bool, engine: TandemEngine) {
        Task.detached(priority: .userInitiated) {
            let levels = await record(quiet: quiet) { try? await engine.sideReady(target: peer, id: id) }
            try? await engine.sideHeard(target: peer, id: id, levels: levels)
        }
    }

    /// Records for as long as the core says, after telling the other side that the microphone is on (`ready`).
    private static func record(quiet: Bool, ready: @escaping @Sendable () async -> Void) async -> TandemSideLevels? {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: break
        case .notDetermined:
            guard !quiet, await AVCaptureDevice.requestAccess(for: .audio) else { return nil }
        default: return nil
        }
        if quiet, AudioRoute.microphoneInUse() { return nil }
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return nil }
        // Too low a rate cannot hold the quiet sweeps.
        if quiet, format.sampleRate < Double(tandemSideQuietMinRate()) { return nil }
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
        let rate = UInt32(format.sampleRate)
        return quiet ? tandemSideAnalyzeQuiet(samples: collected.all(), rate: rate) : tandemSideAnalyze(samples: collected.all(), rate: rate)
    }

    private final class Collected: @unchecked Sendable {
        private let lock = NSLock()
        private var samples: [Float] = []
        func append(_ more: [Float]) { lock.lock(); samples.append(contentsOf: more); lock.unlock() }
        func all() -> [Float] { lock.lock(); defer { lock.unlock() }; return samples }
    }
}
