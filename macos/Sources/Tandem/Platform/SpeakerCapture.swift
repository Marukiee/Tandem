import CoreAudio
import Foundation

/// What records this Mac's sound for a phone that is its speaker: the tap on all apps, or Tandem Speaker once that is installed and chosen.
protocol SoundCapture: AnyObject {
    var lastDelivery: Date { get }
    func prepare(muteLocal: Bool) throws -> SystemAudioTap.Format
    func start(handler: @escaping @Sendable (Data) -> Void) throws
    func stop()
}

extension SystemAudioTap: SoundCapture {}

enum SpeakerCapture {
    /// Tandem Speaker only while it is the output: apps then play to it and nowhere else. Otherwise (the sound for a device that looks at
    /// this screen, or no driver) the tap, which hears whatever the output is.
    @MainActor
    static func make() -> any SoundCapture {
        if TandemAudioDriver.shared.active, let output = AudioDevices.defaultOutput(), AudioDevices.uid(of: output) == TandemAudioDriver.speakerUID {
            return DriverSpeakerCapture()
        }
        return SystemAudioTap()
    }
}

/// Records Tandem Speaker Return, the hidden other half of Tandem Speaker: what apps play to Tandem Speaker comes out here, 48 kHz stereo
/// float. Recording an input asks for the microphone permission once, like any input; without it the sound is silence.
final class DriverSpeakerCapture: SoundCapture, @unchecked Sendable {
    private var device = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private let queue = DispatchQueue(label: "tandem.audio.speaker", qos: .userInteractive)

    private let lastBeat = NSLock()
    private var beatAt = Date.distantPast
    var lastDelivery: Date { lastBeat.lock(); defer { lastBeat.unlock() }; return beatAt }

    /// `muteLocal` has nothing to do here: what plays to Tandem Speaker plays nowhere else.
    func prepare(muteLocal: Bool) throws -> SystemAudioTap.Format {
        guard let found = TandemAudioDriver.device(TandemAudioDriver.speakerReturnUID) else { throw SystemAudioTap.Failure.step("Tandem Speaker", -1) }
        device = found
        return SystemAudioTap.Format(sampleRate: 48_000, channels: 2)
    }

    /// Like the tap: a stretch of silence longer than a second is not sent, so a Mac that plays nothing sends nothing.
    func start(handler: @escaping @Sendable (Data) -> Void) throws {
        var silentFor = 0
        let status = AudioDeviceCreateIOProcIDWithBlock(&procID, device, queue) { [weak self] _, input, _, _, _ in
            guard let self else { return }
            self.lastBeat.lock(); self.beatAt = Date(); self.lastBeat.unlock()
            let pcm = SystemAudioTap.convert(input, channels: 2, planar: false, isFloat: true, bits: 32)
            guard !pcm.isEmpty else { return }
            if SystemAudioTap.isSilent(pcm) {
                silentFor += pcm.count / 4
                if silentFor > 48_000 { return }
            } else {
                silentFor = 0
            }
            handler(pcm)
        }
        guard status == noErr, procID != nil else { throw SystemAudioTap.Failure.step("callback", status) }
        let started = AudioDeviceStart(device, procID)
        guard started == noErr else { throw SystemAudioTap.Failure.step("start", started) }
        lastBeat.lock(); beatAt = Date(); lastBeat.unlock()
    }

    func stop() {
        if device != kAudioObjectUnknown, let procID {
            AudioDeviceStop(device, procID)
            AudioDeviceDestroyIOProcID(device, procID)
        }
        procID = nil
    }

    deinit { stop() }
}
