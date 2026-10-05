import AVFoundation
import Foundation
import Observation
import SwiftUI
import TandemCore

/// How much sound waits before the speakers. Less is closer to live and gives up some steadiness: a packet that is late
/// is heard as a short hiccup. More rides out a slow network and is heard as a later sound. The sound itself is not
/// compressed in any of them.
enum SoundDelay: Int, CaseIterable, Identifiable {
    case low, normal, smooth
    var id: Int { rawValue }

    /// What is collected before playing starts, and what may wait at most before the oldest sound is dropped.
    var prebufferMs: Int { [30, 90, 200][rawValue] }
    var maxMs: Int { [150, 320, 600][rawValue] }
    var keepMs: Int { [60, 120, 260][rawValue] }

    var title: LocalizedStringKey {
        switch self {
        case .low: "Low"
        case .normal: "Normal"
        case .smooth: "Smooth"
        }
    }
}

/// The sound of a phone, playing on this Mac. The phone sends 16 bit samples in datagrams; they wait in a short buffer and
/// an audio engine pulls them out at the pace of the speakers. One phone at a time.
@MainActor @Observable
final class PhoneSound {
    static let shared = PhoneSound()

    /// The stream number a phone's sound uses, apart from the one the Mac's own sound uses towards the phone.
    nonisolated static let stream: UInt8 = 9

    private static let enabledKey = "phoneSoundEnabled"
    private static let delayKey = "phoneSoundDelay"

    /// Whether a phone may play its sound here at all.
    var enabled: Bool = UserDefaults.standard.object(forKey: PhoneSound.enabledKey) as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.enabledKey)
            if !enabled, let device { stop(device: device, tell: true) }
        }
    }

    var delay: SoundDelay = SoundDelay(rawValue: UserDefaults.standard.integer(forKey: PhoneSound.delayKey)) ?? .normal {
        didSet { UserDefaults.standard.set(delay.rawValue, forKey: Self.delayKey) }
    }

    /// The phone that is playing here, or nil.
    private(set) var device: String?
    private(set) var deviceName = ""

    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private let ring = SoundRing()
    @ObservationIgnored let sink = PhoneSoundSink()

    private init() {
        sink.ring = ring
    }

    /// Read from the connection's thread for every packet, so it is kept where a lock can reach it.
    @ObservationIgnored nonisolated(unsafe) fileprivate var expectedDevice: String?

    /// A phone asks to play. Refused (and told so) when sound from phones is off or another phone is playing.
    func start(from id: String, name: String, sampleRate: UInt32, channels: UInt8, engineModel: EngineModel) {
        guard enabled, device == nil || device == id else {
            Task { try? await engineModel.tandem?.sendAudioStop(target: id, stream: Self.stream) }
            return
        }
        stopEngine()
        let channelCount = AVAudioChannelCount(max(1, min(2, Int(channels))))
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: channelCount) else { return }
        ring.reset(
            channels: Int(channelCount), sampleRate: Int(sampleRate),
            prebufferMs: delay.prebufferMs, maxMs: delay.maxMs, keepMs: delay.keepMs
        )
        let ring = self.ring
        let source = AVAudioSourceNode(format: format) { _, _, frames, list -> OSStatus in
            ring.fill(list: list, frames: Int(frames))
            return noErr
        }
        let created = AVAudioEngine()
        created.attach(source)
        created.connect(source, to: created.mainMixerNode, format: format)
        do {
            try created.start()
        } catch {
            Task { try? await engineModel.tandem?.sendAudioStop(target: id, stream: Self.stream) }
            return
        }
        engine = created
        device = id
        expectedDevice = id
        deviceName = name
        FloatingToast.show(String(localized: "Sound from \(name) plays on this Mac"), symbol: "speaker.wave.2.fill")
    }

    /// The phone stopped, went away, or the person turned this off.
    func stop(device id: String, tell: Bool = false) {
        guard device == id else { return }
        stopEngine()
        device = nil
        expectedDevice = nil
        if tell, let model = EngineModel.shared.tandem {
            Task { try? await model.sendAudioStop(target: id, stream: Self.stream) }
        }
    }

    private func stopEngine() {
        engine?.stop()
        engine = nil
        ring.clear()
    }
}

/// The packets of a phone's sound, from the network thread to the audio thread.
final class PhoneSoundSink: TandemAudioSink, @unchecked Sendable {
    var ring: SoundRing?

    func onAudio(from: String, stream: UInt8, seq: UInt32, pcm: Data) {
        guard stream == PhoneSound.stream, from == PhoneSound.shared.expectedDeviceUnsafe else { return }
        ring?.push(seq: seq, pcm: pcm)
    }
}

extension PhoneSound {
    nonisolated var expectedDeviceUnsafe: String? { expectedDevice }
}

/// A buffer of samples between the network and the speakers: it collects a cushion before it lets the speakers start,
/// puts a moment of silence where a packet was lost, throws old sound away when it has grown into a delay, and goes back
/// to collecting when it runs dry. Safe to push from one thread and fill from another.
final class SoundRing: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [Int16] = []
    private var readIndex = 0
    private var channels = 2
    private var framesPerMs = 48
    private var prebufferFrames = 0
    private var maxFrames = 0
    private var keepFrames = 0
    private var playing = false
    private var lastSeq: UInt32?

    func reset(channels: Int, sampleRate: Int, prebufferMs: Int, maxMs: Int, keepMs: Int) {
        lock.lock()
        defer { lock.unlock() }
        samples.removeAll(keepingCapacity: true)
        readIndex = 0
        self.channels = channels
        framesPerMs = max(1, sampleRate / 1000)
        prebufferFrames = prebufferMs * framesPerMs
        maxFrames = maxMs * framesPerMs
        keepFrames = keepMs * framesPerMs
        playing = false
        lastSeq = nil
    }

    func clear() {
        lock.lock()
        samples.removeAll(keepingCapacity: true)
        readIndex = 0
        playing = false
        lastSeq = nil
        lock.unlock()
    }

    private var queuedFrames: Int { (samples.count - readIndex) / max(1, channels) }

    func push(seq: UInt32, pcm: Data) {
        lock.lock()
        defer { lock.unlock() }
        if let last = lastSeq {
            // Older than what was already played, or the same packet twice.
            if seq <= last, last &- seq < 1_000_000 { return }
            let missing = Int(seq &- last) - 1
            if missing > 0, missing < 50 {
                // A hole of a few packets is silence of that length, so the rest does not slide forward.
                let gapFrames = min(missing * (pcm.count / (2 * max(1, channels))), 40 * framesPerMs)
                samples.append(contentsOf: [Int16](repeating: 0, count: gapFrames * channels))
            }
        }
        lastSeq = seq
        pcm.withUnsafeBytes { raw in
            let values = raw.bindMemory(to: Int16.self)
            samples.append(contentsOf: values)
        }
        if queuedFrames > maxFrames {
            // The two clocks drifted apart: keep the newest part.
            let drop = queuedFrames - keepFrames
            readIndex += drop * channels
        }
        if readIndex > 1 << 20 {
            samples.removeFirst(readIndex)
            readIndex = 0
        }
    }

    /// Fills what the speakers asked for. Silence while the cushion builds up or when the sound has run dry.
    func fill(list: UnsafeMutablePointer<AudioBufferList>, frames: Int) {
        let buffers = UnsafeMutableAudioBufferListPointer(list)
        lock.lock()
        defer { lock.unlock() }
        if !playing, queuedFrames >= prebufferFrames, prebufferFrames >= 0 { playing = true }
        let available = queuedFrames
        let take = playing ? min(frames, available) : 0
        for channel in 0..<buffers.count {
            guard let data = buffers[channel].mData?.assumingMemoryBound(to: Float.self) else { continue }
            for frame in 0..<frames {
                if frame < take {
                    let source = readIndex + frame * channels + min(channel, channels - 1)
                    data[frame] = Float(samples[source]) / 32768
                } else {
                    data[frame] = 0
                }
            }
        }
        readIndex += take * channels
        if playing, take < frames { playing = false }
    }
}

/// The page of Settings about sound from phones.
struct PhoneSoundSettings: View {
    @Bindable private var sound = PhoneSound.shared

    var body: some View {
        Section("Sound from your phone") {
            DescribedToggle(
                "Let a phone play its sound here",
                subtitle: "Start it on the phone, on the page of this Mac. Turning this off stops it",
                isOn: $sound.enabled
            )
            Picker("Delay", selection: $sound.delay) {
                ForEach(SoundDelay.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            Text("Low plays closer to live and can stutter on a poor connection. Smooth waits a little longer to stay steady. It applies the next time a phone starts.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// Checks of the buffer that need nothing but this process: `TANDEM_DEBUG_SOUNDRING=1` with `TANDEM_DEBUG_DIR` set writes
/// `soundring.txt` and quits (a test target needs Xcode, which this project is built without).
enum SoundRingSelfTest {
    static func run() -> [String] {
        var failures: [String] = []
        func check(_ condition: Bool, _ name: String) { if !condition { failures.append(name) } }

        /// A packet of `frames` stereo frames, every sample the value `base + frame`.
        func packet(_ frames: Int, base: Int16) -> Data {
            var values: [Int16] = []
            for frame in 0..<frames { values += [base &+ Int16(frame), base &+ Int16(frame)] }
            return values.withUnsafeBufferPointer { Data(buffer: $0) }
        }
        /// What the speakers get for `frames` frames, the left channel.
        func pull(_ ring: SoundRing, _ frames: Int) -> [Float] {
            let list = AudioBufferList.allocate(maximumBuffers: 2)
            let left = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            let right = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            left.initialize(repeating: -1, count: frames)
            right.initialize(repeating: -1, count: frames)
            list[0] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * 4), mData: left)
            list[1] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * 4), mData: right)
            ring.fill(list: list.unsafeMutablePointer, frames: frames)
            let result = Array(UnsafeBufferPointer(start: left, count: frames))
            left.deallocate()
            right.deallocate()
            list.unsafeMutablePointer.deallocate()
            return result
        }

        let ring = SoundRing()
        ring.reset(channels: 2, sampleRate: 48_000, prebufferMs: 90, maxMs: 320, keepMs: 120)
        // 20 ms is less than the cushion of 90 ms: the speakers get silence and nothing is used up.
        ring.push(seq: 0, pcm: packet(960, base: 100))
        check(pull(ring, 100).allSatisfy { $0 == 0 }, "silence while the cushion builds")
        // Enough for the cushion: the sound comes out in order.
        for n in 1...4 { ring.push(seq: UInt32(n), pcm: packet(960, base: 100)) }
        let first = pull(ring, 4)
        check(first.map { Int($0 * 32768) } == [100, 101, 102, 103], "samples come out in order")
        // A packet that went missing is a stretch of silence, not a jump.
        let gap = SoundRing()
        gap.reset(channels: 2, sampleRate: 48_000, prebufferMs: 0, maxMs: 320, keepMs: 120)
        gap.push(seq: 0, pcm: packet(10, base: 500))
        gap.push(seq: 2, pcm: packet(10, base: 900))
        let spread = pull(gap, 30).map { Int($0 * 32768) }
        check(Array(spread[0..<10]) == Array(500..<510) && spread[10..<20].allSatisfy { $0 == 0 } && spread[20] == 900, "a lost packet is silence")
        // The same packet twice and an old one are dropped.
        gap.push(seq: 2, pcm: packet(10, base: 777))
        check(pull(gap, 10).allSatisfy { $0 == 0 }, "a repeated packet is dropped")
        // Too much waiting: the oldest goes and the newest stays.
        let long = SoundRing()
        long.reset(channels: 2, sampleRate: 48_000, prebufferMs: 0, maxMs: 100, keepMs: 40)
        for n in 0..<20 { long.push(seq: UInt32(n), pcm: packet(480, base: Int16(n * 1000))) }
        let kept = pull(long, 4000).map { Int($0 * 32768) }.filter { $0 != 0 }
        check(!kept.isEmpty && kept.count <= 100 * 48, "a long wait is cut down")
        check(kept.last.map { $0 >= 19_000 } ?? false, "what stays is the newest")
        // Dry: silence, then the cushion builds again before it plays.
        let dry = SoundRing()
        dry.reset(channels: 2, sampleRate: 48_000, prebufferMs: 20, maxMs: 320, keepMs: 120)
        dry.push(seq: 0, pcm: packet(960, base: 10))
        _ = pull(dry, 960)
        check(pull(dry, 10).allSatisfy { $0 == 0 }, "silence when it runs dry")
        dry.push(seq: 1, pcm: packet(100, base: 50))
        check(pull(dry, 10).allSatisfy { $0 == 0 }, "waits for the cushion again")
        return failures
    }
}
