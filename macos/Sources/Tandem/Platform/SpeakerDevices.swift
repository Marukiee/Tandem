import AppKit
import CoreAudio
import Foundation
import Observation

/// Puts a phone in the list of sound outputs of the system (System Settings, Sound, Output), so
/// choosing it there makes this Mac's sound play on the phone.
///
/// A real output device needs a driver, which needs an administrator to install and can break all
/// sound if it is wrong. This does it without: for each phone it makes a device from the Mac's own
/// speakers under another name, which the system lists like any output. Choosing it plays on the
/// speakers as usual, and Tandem, which sees that it was chosen, takes what is sent to it, mutes the
/// speakers and sends it on to the phone. Without Tandem running, or with the phone away, it just plays on
/// the speakers, so choosing it never leaves a Mac silent.
///
/// The cost is what a device made like this always has: macOS shows no volume slider for it, so the
/// volume is the phone's. The devices are removed when Tandem quits and when a phone is removed, and
/// are made again the next time, so nothing is left behind.
@MainActor
@Observable
final class SpeakerDevices {
    static let uidPrefix = AudioDevices.speakerUIDPrefix

    /// Called when one of them becomes the output (the phone and the device's UID), or when something
    /// else does after one had been (nil, nil).
    @ObservationIgnored var onSelect: ((String?, String?) -> Void)?

    /// The outputs that exist right now, by phone: what the system lists them as. For the settings to show.
    private(set) var created: [String: String] = [:]

    /// Why an output could not be made, for the settings to show. Nil when all went well.
    private(set) var problem: String?

    /// The phone whose output is the system output right now.
    private(set) var selectedPhone: String?

    @ObservationIgnored private var lastOutsideOutput: String?
    @ObservationIgnored private var listener: AudioObjectPropertyListenerBlock?
    /// What the devices were last made to match. Starts as something no list is, so the first call always
    /// runs and clears what an earlier run left behind.
    @ObservationIgnored private var signature = "-"
    @ObservationIgnored private var quitObserver: NSObjectProtocol?

    static func uid(forPhone id: String) -> String { uidPrefix + id }

    static func phone(fromUID uid: String) -> String? {
        uid.hasPrefix(uidPrefix) && uid.count > uidPrefix.count ? String(uid.dropFirst(uidPrefix.count)) : nil
    }

    // MARK: Which phones

    /// Makes the devices match the phones, or removes them all when [enabled] is false. Cheap to call
    /// often: it does nothing when nothing changed.
    func sync(phones: [(id: String, name: String)], enabled: Bool) {
        let wanted = enabled ? Dictionary(phones.map { (Self.uid(forPhone: $0.id), "\($0.name) (Tandem)") }, uniquingKeysWith: { first, _ in first }) : [:]
        let now = wanted.keys.sorted().map { "\($0)=\(wanted[$0] ?? "")" }.joined(separator: "|")
        guard now != signature else { return }
        signature = now

        if enabled {
            startListening()
            if quitObserver == nil {
                quitObserver = NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.removeAll() }
                }
            }
        } else {
            stopListening()
        }

        // Take away what is not wanted (a phone that is gone, a new name, the setting off), including
        // what an earlier run left if it did not get to clean up.
        for device in AudioDevices.all() {
            guard let uid = AudioDevices.uid(of: device), uid.hasPrefix(Self.uidPrefix) else { continue }
            if let name = wanted[uid], AudioDevices.name(of: device) == name { continue }
            // One that is the output right now keeps its old name until it is not.
            if wanted[uid] != nil, Self.phone(fromUID: uid) == selectedPhone { continue }
            AudioHardwareDestroyAggregateDevice(device)
        }
        problem = nil
        for (uid, name) in wanted where AudioDevices.device(forUID: uid) == nil { create(uid: uid, name: name) }
        created = wanted.reduce(into: [:]) { result, entry in
            if AudioDevices.device(forUID: entry.key) != nil, let phone = Self.phone(fromUID: entry.key) { result[phone] = entry.value }
        }
        if enabled { defaultChanged() }
    }

    func removeAll() {
        signature = "-"
        created = [:]
        stopListening()
        for device in AudioDevices.all() {
            if let uid = AudioDevices.uid(of: device), uid.hasPrefix(Self.uidPrefix) { AudioHardwareDestroyAggregateDevice(device) }
        }
    }

    private func create(uid: String, name: String) {
        guard let underlying = AudioDevices.builtInOutputUID() else { return }
        let description: [String: Any] = [
            kAudioAggregateDeviceNameKey: name,
            kAudioAggregateDeviceUIDKey: uid,
            kAudioAggregateDeviceMainSubDeviceKey: underlying,
            // Not private: that is what puts it in the list for everyone. One speaker under it, which plays
            // whatever is sent to it.
            kAudioAggregateDeviceIsStackedKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: underlying]],
        ]
        var device = AudioObjectID(kAudioObjectUnknown)
        let status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &device)
        if status != noErr {
            NSLog("Tandem: could not make the sound output %@ (%d)", name, status)
            problem = String(localized: "Could not make the sound output for \(name) (error \(Int(status)))")
        }
    }

    // MARK: Which output is chosen

    private func startListening() {
        guard listener == nil else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.defaultChanged() } }
        }
        listener = block
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main, block)
    }

    private func stopListening() {
        guard let block = listener else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main, block)
        listener = nil
        selectedPhone = nil
    }

    private func defaultChanged() {
        guard let device = AudioDevices.defaultOutput(), let uid = AudioDevices.uid(of: device) else { return }
        if let phone = Self.phone(fromUID: uid) {
            guard selectedPhone != phone else { return }
            selectedPhone = phone
            onSelect?(phone, uid)
        } else {
            lastOutsideOutput = uid
            guard selectedPhone != nil else { return }
            selectedPhone = nil
            onSelect?(nil, nil)
        }
    }

    /// Makes a phone's output the system output, like choosing it in the sound menu: the watcher then sees it and
    /// the sound starts. False when the output does not exist or the system would not switch.
    @discardableResult
    func select(phone: String) -> Bool {
        guard var device = AudioDevices.device(forUID: Self.uid(forPhone: phone)) else { return false }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        return AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &device) == noErr
    }

    /// Goes back to the output that was in use before a phone was chosen, for when the phone goes away
    /// or sound is stopped from Tandem.
    func restoreOutput() {
        let target = lastOutsideOutput.flatMap { AudioDevices.device(forUID: $0) }
            ?? AudioDevices.builtInOutputUID().flatMap { AudioDevices.device(forUID: $0) }
        guard var device = target else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &device)
    }
}
