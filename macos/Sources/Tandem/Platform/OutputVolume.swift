import AudioToolbox
import CoreAudio
import Foundation

/// The volume of this Mac's sound output, as a gain for the sound it sends while another device is its speaker. The tap leaves the volume
/// of the output alone (it only mutes what plays here), so the volume keys and the slider in the menu bar still move it; applied to what
/// is sent, they turn the other device up and down as if it were this Mac's own speaker, and mute silences it.
final class OutputVolume: @unchecked Sendable {
    static let shared = OutputVolume()

    private let lock = NSLock()
    private var current: Float = 1
    private var device = AudioObjectID(kAudioObjectUnknown)
    private var watching = false

    /// What to multiply the samples by, 0 to 1.
    var gain: Float {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    /// Follows the default output and its volume from now on. Cheap: it only listens for changes.
    func start() {
        lock.lock()
        let started = watching
        watching = true
        lock.unlock()
        guard !started else { return }
        var address = Self.address(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal)
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main) { [weak self] _, _ in
            self?.follow()
        }
        follow()
    }

    /// A new default output: listen to its volume and mute instead of the old one's.
    private func follow() {
        var id = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var address = Self.address(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &id) == noErr else { return }
        lock.lock()
        let same = id == device
        device = id
        lock.unlock()
        if !same {
            for selector in [kAudioHardwareServiceDeviceProperty_VirtualMainVolume, kAudioDevicePropertyMute] {
                var watched = Self.address(selector, kAudioDevicePropertyScopeOutput)
                AudioObjectAddPropertyListenerBlock(id, &watched, DispatchQueue.main) { [weak self] _, _ in self?.read() }
            }
        }
        read()
    }

    private func read() {
        lock.lock()
        let id = device
        lock.unlock()
        var volume: Float32 = 1
        var size = UInt32(MemoryLayout<Float32>.size)
        var address = Self.address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume, kAudioDevicePropertyScopeOutput)
        if AudioObjectGetPropertyData(id, &address, 0, nil, &size, &volume) != noErr { volume = 1 }
        var muted: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        address = Self.address(kAudioDevicePropertyMute, kAudioDevicePropertyScopeOutput)
        if AudioObjectGetPropertyData(id, &address, 0, nil, &size, &muted) != noErr { muted = 0 }
        // The slider is a scale for the ear: half way is about a quarter of the level, not half of it.
        let gain = muted != 0 ? 0 : min(max(volume, 0), 1) * min(max(volume, 0), 1)
        lock.lock()
        current = gain
        lock.unlock()
    }

    private static func address(_ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    /// Scales 16-bit samples by the gain, in place. Untouched at full volume.
    static func apply(_ gain: Float, to pcm: inout Data) {
        guard gain < 0.999 else { return }
        pcm.withUnsafeMutableBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for index in samples.indices {
                samples[index] = Int16(Float(samples[index]) * gain)
            }
        }
    }
}
