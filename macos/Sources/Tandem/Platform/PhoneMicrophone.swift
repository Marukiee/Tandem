import AVFoundation
import CoreAudio
import Foundation
import Observation

/// A phone as this Mac's microphone. The phone records and sends what it hears (`MicRequest` in proto.rs, audio stream 10), and it is
/// played into Tandem Microphone Feed, the hidden other half of Tandem Microphone, so every app that records from Tandem Microphone hears
/// the phone. It needs Tandem's sound devices (TandemAudioDriver) and a phone that says `audio.mic`.
///
/// It starts from the button on the phone's page, and by itself when an app starts recording from Tandem Microphone; one started by an
/// app stops again when no app records any more.
@MainActor @Observable
final class PhoneMicrophone {
    static let shared = PhoneMicrophone()

    /// The stream a phone sends its microphone on (`MIC_STREAM` in proto.rs).
    nonisolated static let stream: UInt8 = 10
    static let capability = "audio.mic"
    private static let phoneKey = "micPhone"

    enum State: Equatable {
        case off
        case starting(String)
        case on(String)

        var device: String? {
            switch self {
            case let .starting(id), let .on(id): id
            case .off: nil
            }
        }
    }

    private(set) var state: State = .off

    @ObservationIgnored private var feed: MicrophoneFeed?
    @ObservationIgnored private var askedAt = Date.distantPast
    /// Started because an app records from Tandem Microphone, not from the button.
    @ObservationIgnored private var byApp = false
    @ObservationIgnored private var previousInput: String?
    @ObservationIgnored private var waiting: Task<Void, Never>?
    @ObservationIgnored private var watched = AudioObjectID(kAudioObjectUnknown)
    @ObservationIgnored private var listener: AudioObjectPropertyListenerBlock?

    private init() {}

    private var model: EngineModel { EngineModel.shared }

    private func name(_ id: String) -> String { model.device(id)?.name ?? String(localized: "that device") }

    // MARK: Starting and stopping

    func toggle(for id: String) {
        if state.device == id {
            stop(tell: true)
        } else {
            start(id, byApp: false)
        }
    }

    /// Says why it cannot start, or nil when it can.
    func problem(for id: String) -> String? {
        let driver = TandemAudioDriver.shared
        if !driver.active {
            return driver.installed
                ? String(localized: "Tandem Microphone is not there yet. Restart this Mac if it does not appear.")
                : String(localized: "Turn on Tandem Speaker and Microphone in Settings, General first")
        }
        guard let phone = model.device(id) else { return String(localized: "That device is not connected right now") }
        if !phone.caps.contains(Self.capability) { return String(localized: "Update Tandem on \(phone.name) to use it as a microphone") }
        if !phone.online { return String(localized: "That device is not connected right now") }
        return nil
    }

    private func start(_ id: String, byApp: Bool) {
        if let problem = problem(for: id) {
            model.showToast(problem)
            return
        }
        TandemAudioDriver.shared.offerUpdateIfNeeded()
        if state.device != nil { stop(tell: true) }
        state = .starting(id)
        askedAt = Date()
        self.byApp = byApp
        UserDefaults.standard.set(id, forKey: Self.phoneKey)
        Task { try? await model.tandem?.requestMic(target: id, on: true) }
        // From the button, Tandem Microphone becomes the input, as picking it in the sound menu would.
        if !byApp { useAsInput() }
        waiting?.cancel()
        waiting = Task { @MainActor [weak self] in
            // Long enough to tap the notification on a phone that is in a pocket.
            try? await Task.sleep(for: .seconds(45))
            guard let self, !Task.isCancelled, self.state == .starting(id) else { return }
            self.stop(tell: true)
            self.model.showToast(String(localized: "\(self.name(id)) did not answer. Open Tandem there, or tap its notification."))
        }
    }

    func stop(tell: Bool) {
        guard let id = state.device else { return }
        waiting?.cancel()
        end()
        if tell { Task { try? await model.tandem?.requestMic(target: id, on: false) } }
        restoreInput()
    }

    private func end() {
        if let id = state.device { PhoneSound.shared.sink.setMicrophone(nil, for: id) }
        feed?.stop()
        feed = nil
        state = .off
    }

    func phoneGone(_ id: String) {
        guard state.device == id else { return }
        stop(tell: false)
        model.showToast(String(localized: "\(name(id)) disconnected, so Tandem Microphone is silent"))
    }

    // MARK: From the phone

    /// An `AudioStart`: true when it is the microphone (the stream says so), which is then this one's to play.
    func started(from id: String, stream: UInt8, sampleRate: UInt32, channels: UInt8) -> Bool {
        guard stream == Self.stream else { return false }
        guard state.device == id else {
            // Not asked for (any more): the phone is told, so it stops recording.
            Task { try? await model.tandem?.requestMic(target: id, on: false) }
            return true
        }
        waiting?.cancel()
        feed?.stop()
        guard let made = MicrophoneFeed(sampleRate: sampleRate, channels: channels) else {
            stop(tell: true)
            model.showToast(String(localized: "Tandem Microphone could not be opened"))
            return true
        }
        feed = made
        PhoneSound.shared.sink.setMicrophone(made.ring, for: id)
        state = .on(id)
        return true
    }

    /// An `AudioStop`: true when it was about the microphone.
    func stopped(from id: String, stream: UInt8) -> Bool {
        guard stream == Self.stream else { return false }
        guard state.device == id else { return true }
        let early = Date().timeIntervalSince(askedAt) < 3
        stop(tell: false)
        model.showToast(early
            ? String(localized: "\(name(id)) does not allow its microphone. Allow it in Tandem on the phone.")
            : String(localized: "\(name(id)) stopped its microphone"))
        return true
    }

    // MARK: The input of this Mac

    private func useAsInput() {
        guard let device = TandemAudioDriver.device(TandemAudioDriver.microphoneUID) else { return }
        let current = Self.defaultInput()
        guard current != device else { return }
        previousInput = current.flatMap { AudioDevices.uid(of: $0) }
        Self.setDefaultInput(device)
    }

    private func restoreInput() {
        guard let previous = previousInput else { return }
        previousInput = nil
        // Only when it is still Tandem Microphone: the person may have picked something else meanwhile.
        guard let current = Self.defaultInput(), AudioDevices.uid(of: current) == TandemAudioDriver.microphoneUID,
              let device = AudioDevices.device(forUID: previous) else { return }
        Self.setDefaultInput(device)
    }

    private static func defaultInput() -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        var device = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else { return nil }
        return device
    }

    private static func setDefaultInput(_ device: AudioObjectID) {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        var value = device
        AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &value)
    }

    // MARK: Apps that record from Tandem Microphone

    /// Follows whether any app records from Tandem Microphone. Called again when the devices come and go: after an install the device has a
    /// new ID.
    func watch() {
        let device = TandemAudioDriver.device(TandemAudioDriver.microphoneUID) ?? AudioObjectID(kAudioObjectUnknown)
        guard device != watched else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        if watched != kAudioObjectUnknown, let listener {
            AudioObjectRemovePropertyListenerBlock(watched, &address, DispatchQueue.main, listener)
        }
        watched = device
        guard device != kAudioObjectUnknown else { return }
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.recordingChanged() } }
        }
        listener = block
        AudioObjectAddPropertyListenerBlock(device, &address, DispatchQueue.main, block)
    }

    private func recordingChanged() {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        var running: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard watched != kAudioObjectUnknown, AudioObjectGetPropertyData(watched, &address, 0, nil, &size, &running) == noErr else { return }
        if running != 0, state == .off {
            guard let phone = choosePhone() else {
                model.showToast(String(localized: "An app listens to Tandem Microphone, but no phone with Tandem is connected"))
                return
            }
            start(phone, byApp: true)
        } else if running == 0, byApp, state != .off {
            stop(tell: true)
        }
    }

    /// The phone used last, else the first one that is there and can.
    private func choosePhone() -> String? {
        let able = model.devices.filter { $0.online && $0.caps.contains(Self.capability) }
        let last = UserDefaults.standard.string(forKey: Self.phoneKey)
        return able.first { $0.id == last }?.id ?? able.first?.id
    }
}

/// Plays the phone's microphone into Tandem Microphone Feed: the same buffer as other devices' sound (SoundRing), kept short, since a
/// microphone that lags behind is worse in a call than one that stutters now and then.
@MainActor
final class MicrophoneFeed {
    let ring = SoundRing()
    private let engine = AVAudioEngine()

    init?(sampleRate: UInt32, channels: UInt8) {
        guard var device = TandemAudioDriver.device(TandemAudioDriver.microphoneFeedUID), let unit = engine.outputNode.audioUnit else { return nil }
        // The output is chosen before anything is connected: the mixer takes its format from it.
        let set = AudioUnitSetProperty(
            unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device, UInt32(MemoryLayout<AudioObjectID>.size)
        )
        guard set == noErr else { return nil }
        let count = AVAudioChannelCount(max(1, min(2, Int(channels))))
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: count) else { return nil }
        let delay = SoundDelay.low
        ring.reset(channels: Int(count), sampleRate: Int(sampleRate), prebufferMs: delay.prebufferMs, maxMs: delay.maxMs, keepMs: delay.keepMs)
        let ring = ring
        let source = AVAudioSourceNode(format: format) { _, _, frames, list -> OSStatus in
            ring.fill(list: list, frames: Int(frames))
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { return nil }
    }

    func stop() { engine.stop() }
}
