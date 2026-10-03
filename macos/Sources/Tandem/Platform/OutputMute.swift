import CoreAudio
import Foundation

/// Whether the sound of this Mac is off: the default output is muted, or its volume is at nothing. A phone that remote
/// controls the Mac's player shows it on the mute button, so the phone is told when it changes, also when it changed
/// on the Mac itself (the keyboard, the menu bar, an app).
enum OutputMute {
    private static func address(_ selector: AudioObjectPropertySelector, element: AudioObjectPropertyElement) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioDevicePropertyScopeOutput, mElement: element)
    }

    /// The main element, then the two channels of a stereo output: a device has the property on one or the other.
    static let elements: [AudioObjectPropertyElement] = [kAudioObjectPropertyElementMain, 1, 2]

    /// True when the sound is off, false when it is on, nil when the output cannot say (some displays and cables cannot).
    static func isMuted() -> Bool? {
        guard let device = AudioDevices.defaultOutput() else { return nil }
        var known = false
        for element in elements {
            var muteAddress = address(kAudioDevicePropertyMute, element: element)
            if AudioObjectHasProperty(device, &muteAddress) {
                var muted: UInt32 = 0
                var size = UInt32(MemoryLayout<UInt32>.size)
                if AudioObjectGetPropertyData(device, &muteAddress, 0, nil, &size, &muted) == noErr {
                    known = true
                    if muted != 0 { return true }
                }
            }
        }
        for element in elements {
            var volumeAddress = address(kAudioDevicePropertyVolumeScalar, element: element)
            if AudioObjectHasProperty(device, &volumeAddress) {
                var volume: Float32 = 1
                var size = UInt32(MemoryLayout<Float32>.size)
                if AudioObjectGetPropertyData(device, &volumeAddress, 0, nil, &size, &volume) == noErr {
                    known = true
                    if volume <= 0.001 { return true }
                }
            }
        }
        return known ? false : nil
    }
}

/// Calls back when the mute state of the default output changes. It follows the default output when that is switched
/// to another device, and listens to the mute and the volume of whichever one is current.
final class OutputMuteWatcher: @unchecked Sendable {
    private let queue = DispatchQueue(label: "tandem.mute")
    private var device: AudioObjectID?
    private var last: Bool?
    private var deviceBlock: AudioObjectPropertyListenerBlock?
    private var systemBlock: AudioObjectPropertyListenerBlock?
    private var onChange: (@Sendable (Bool?) -> Void)?

    func start(onChange: @escaping @Sendable (Bool?) -> Void) {
        queue.async {
            self.onChange = onChange
            var address = AudioObjectPropertyAddress(
                mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                mScope: kAudioObjectPropertyScopeGlobal,
                mElement: kAudioObjectPropertyElementMain
            )
            let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in self?.queue.async { self?.deviceChanged() } }
            self.systemBlock = block
            AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, self.queue, block)
            self.deviceChanged()
        }
    }

    func stop() {
        queue.async {
            self.unwatchDevice()
            if let block = self.systemBlock {
                var address = AudioObjectPropertyAddress(
                    mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                    mScope: kAudioObjectPropertyScopeGlobal,
                    mElement: kAudioObjectPropertyElementMain
                )
                AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, self.queue, block)
            }
            self.systemBlock = nil
            self.onChange = nil
        }
    }

    /// The default output is another device now (or the first look): listen to that one instead.
    private func deviceChanged() {
        unwatchDevice()
        if let current = AudioDevices.defaultOutput() {
            device = current
            let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in self?.queue.async { self?.report() } }
            deviceBlock = block
            for address in addresses() {
                var copy = address
                if AudioObjectHasProperty(current, &copy) { AudioObjectAddPropertyListenerBlock(current, &copy, queue, block) }
            }
        }
        report()
    }

    private func unwatchDevice() {
        guard let old = device, let block = deviceBlock else { device = nil; return }
        for address in addresses() {
            var copy = address
            AudioObjectRemovePropertyListenerBlock(old, &copy, queue, block)
        }
        device = nil
        deviceBlock = nil
    }

    private func addresses() -> [AudioObjectPropertyAddress] {
        OutputMute.elements.flatMap { element in
            [kAudioDevicePropertyMute, kAudioDevicePropertyVolumeScalar].map {
                AudioObjectPropertyAddress(mSelector: $0, mScope: kAudioDevicePropertyScopeOutput, mElement: element)
            }
        }
    }

    private func report() {
        let now = OutputMute.isMuted()
        guard now != last else { return }
        last = now
        onChange?(now)
    }
}
