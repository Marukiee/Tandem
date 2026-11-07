import CoreAudio
import Foundation

/// The watch as a sound output of this Mac, over Bluetooth, but only when it is chosen. Once the watch is paired with this Mac as its
/// mouse, macOS also sees it as a headset (hands-free) and moves the sound and the microphone to it as soon as it connects. That is put
/// back at once, unless the person chose the watch as the speaker in Tandem.
@MainActor
final class WatchAudio {
    static let shared = WatchAudio()

    /// The watch plays this Mac's sound because the person asked for it.
    private(set) var chosen = false
    private var lastOutput: AudioObjectID?
    private var lastInput: AudioObjectID?
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        lastOutput = Self.current(kAudioHardwarePropertyDefaultOutputDevice).flatMap { Self.isWatch($0) ? nil : $0 }
        lastInput = Self.current(kAudioHardwarePropertyDefaultInputDevice).flatMap { Self.isWatch($0) ? nil : $0 }
        listen(kAudioHardwarePropertyDefaultOutputDevice) { [weak self] in self?.outputChanged() }
        listen(kAudioHardwarePropertyDefaultInputDevice) { [weak self] in self?.inputChanged() }
    }

    /// The Bluetooth audio device of the watch, while it is connected to this Mac.
    var device: AudioObjectID? { AudioDevices.all().first(where: Self.isWatch) }

    /// Plays this Mac's sound on the watch, or puts it back. False when the watch is not connected to this Mac over Bluetooth.
    @discardableResult
    func choose(_ on: Bool) -> Bool {
        if on {
            guard let watch = device else { return false }
            chosen = true
            Self.set(kAudioHardwarePropertyDefaultOutputDevice, to: watch)
        } else {
            chosen = false
            if let current = Self.current(kAudioHardwarePropertyDefaultOutputDevice), Self.isWatch(current) { restoreOutput() }
        }
        return true
    }

    private func outputChanged() {
        guard let now = Self.current(kAudioHardwarePropertyDefaultOutputDevice) else { return }
        if Self.isWatch(now) {
            if !chosen { restoreOutput() }
        } else {
            lastOutput = now
            // Moving the sound elsewhere by hand ends the choice: the watch is no longer the speaker.
            chosen = false
        }
    }

    private func inputChanged() {
        guard let now = Self.current(kAudioHardwarePropertyDefaultInputDevice) else { return }
        // The watch's microphone in hands-free quality is never what someone wants by surprise.
        if Self.isWatch(now) {
            if let back = lastInput ?? AudioDevices.all().first(where: { AudioDevices.isBuiltIn($0) && Self.hasInput($0) }) {
                Self.set(kAudioHardwarePropertyDefaultInputDevice, to: back)
            }
        } else {
            lastInput = now
        }
    }

    private func restoreOutput() {
        let back = lastOutput.flatMap { AudioDevices.uid(of: $0) != nil ? $0 : nil }
            ?? AudioDevices.builtInOutputUID().flatMap(AudioDevices.device(forUID:))
        if let back { Self.set(kAudioHardwarePropertyDefaultOutputDevice, to: back) }
    }

    private func listen(_ selector: AudioObjectPropertySelector, _ changed: @escaping @MainActor () -> Void) {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main) { _, _ in
            MainActor.assumeIsolated { changed() }
        }
    }

    /// A watch that speaks Bluetooth hands-free: CoreAudio knows no kind of device, so its name says it.
    private static func isWatch(_ device: AudioObjectID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyTransportType, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var transport: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport) == noErr,
              transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE else { return false }
        return AudioDevices.name(of: device)?.localizedCaseInsensitiveContains("watch") == true
    }

    private static func hasInput(_ device: AudioObjectID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration, mScope: kAudioObjectPropertyScopeInput, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr && size > UInt32(MemoryLayout<UInt32>.size)
    }

    private static func current(_ selector: AudioObjectPropertySelector) -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var device = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else { return nil }
        return device
    }

    private static func set(_ selector: AudioObjectPropertySelector, to device: AudioObjectID) {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var value = device
        AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &value)
    }
}
