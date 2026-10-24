import AVFoundation
import AppKit
import CoreAudio
import Observation
import TandemCore

/// The sounds of the system that this Mac can play to be found: the alert sounds of System Settings, Sound, and the ringtones that macOS
/// keeps for calls. The person picks one in Settings.
enum FindSounds {
    struct Sound: Identifiable, Hashable {
        let path: String
        let name: String
        let ringtone: Bool
        var id: String { path }
    }

    static let key = "findSound"
    private static let ringtones = "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones"

    static func all() -> [Sound] {
        let home = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Sounds").path
        let alerts = ["/System/Library/Sounds", "/Library/Sounds", home].flatMap { sounds(in: $0, ringtone: false) }
        return alerts + sounds(in: ringtones, ringtone: true)
    }

    private static func sounds(in folder: String, ringtone: Bool) -> [Sound] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder)) ?? []
        var seen = Set<String>()
        return names
            .filter { ["aiff", "aif", "caf", "wav", "m4a", "m4r", "mp3"].contains(($0 as NSString).pathExtension.lowercased()) }
            .sorted()
            .compactMap { file in
                // Several ringtones also come as a longer version of the same tune, with the same name.
                let name = ((file as NSString).deletingPathExtension).replacingOccurrences(of: "-EncoreInfinitum", with: "")
                guard seen.insert(name).inserted else { return nil }
                return Sound(path: (folder as NSString).appendingPathComponent(file), name: name, ringtone: ringtone)
            }
    }

    /// The one that was picked, or a ringtone that carries across a room, or an alert sound when this Mac has no ringtones.
    static var chosen: String {
        let picked = UserDefaults.standard.string(forKey: key) ?? ""
        if !picked.isEmpty, FileManager.default.fileExists(atPath: picked) { return picked }
        let radar = (ringtones as NSString).appendingPathComponent("Radar.m4r")
        return FileManager.default.fileExists(atPath: radar) ? radar : "/System/Library/Sounds/Sosumi.aiff"
    }

    static var playable: Bool { FileManager.default.fileExists(atPath: chosen) && AudioDevices.defaultOutput() != nil }

    @MainActor private static var preview: AVAudioPlayer?
    @MainActor private static var previewStop: Task<Void, Never>?

    /// Plays a sound once at the volume the Mac is at, for a few seconds at most, so a long ringtone can be heard without waiting it out.
    @MainActor static func preview(_ path: String) {
        stopPreview()
        guard let player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path)) else { return }
        preview = player
        player.play()
        previewStop = Task { @MainActor in
            try? await Task.sleep(for: .seconds(min(player.duration, 4)))
            if !Task.isCancelled { stopPreview() }
        }
    }

    @MainActor static func stopPreview() {
        previewStop?.cancel()
        preview?.stop()
        preview = nil
    }
}

/// The volume of the default output, raised while this Mac rings so it is heard across a room, and put back afterwards.
enum OutputLoudness {
    struct Before {
        let device: AudioObjectID
        var volumes: [AudioObjectPropertyElement: Float32] = [:]
        var muted: [AudioObjectPropertyElement: UInt32] = [:]
        /// What this Mac set, so a change the person made in the meantime is not undone.
        var set: Float32 = 0
    }

    private static func address(_ selector: AudioObjectPropertySelector, _ element: AudioObjectPropertyElement) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioDevicePropertyScopeOutput, mElement: element)
    }

    private static func read<T: BitwiseCopyable>(_ device: AudioObjectID, _ selector: AudioObjectPropertySelector, _ element: AudioObjectPropertyElement, _ zero: T) -> T? {
        var property = address(selector, element)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(device, &property), AudioObjectIsPropertySettable(device, &property, &settable) == noErr, settable.boolValue else { return nil }
        var value = zero
        var size = UInt32(MemoryLayout<T>.size)
        return AudioObjectGetPropertyData(device, &property, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func write<T: BitwiseCopyable>(_ device: AudioObjectID, _ selector: AudioObjectPropertySelector, _ element: AudioObjectPropertyElement, _ value: T) {
        var property = address(selector, element)
        var value = value
        AudioObjectSetPropertyData(device, &property, 0, nil, UInt32(MemoryLayout<T>.size), &value)
    }

    /// Unmutes the output and brings a low volume up to `level`. Gives what was there, to put back.
    static func raise(to level: Float32 = 0.75) -> Before? {
        guard let device = AudioDevices.defaultOutput() else { return nil }
        var before = Before(device: device, set: level)
        for element in OutputMute.elements {
            if let muted = read(device, kAudioDevicePropertyMute, element, UInt32(0)), muted != 0 {
                before.muted[element] = muted
                write(device, kAudioDevicePropertyMute, element, UInt32(0))
            }
            if let volume = read(device, kAudioDevicePropertyVolumeScalar, element, Float32(1)), volume < level {
                before.volumes[element] = volume
                write(device, kAudioDevicePropertyVolumeScalar, element, level)
            }
        }
        return before.volumes.isEmpty && before.muted.isEmpty ? nil : before
    }

    static func restore(_ before: Before) {
        for (element, volume) in before.volumes {
            let now = read(before.device, kAudioDevicePropertyVolumeScalar, element, Float32(1))
            if let now, abs(now - before.set) < 0.02 { write(before.device, kAudioDevicePropertyVolumeScalar, element, volume) }
        }
        for (element, muted) in before.muted {
            if read(before.device, kAudioDevicePropertyMute, element, UInt32(0)) == 0 { write(before.device, kAudioDevicePropertyMute, element, muted) }
        }
    }
}

/// This Mac, rung by another device: its sound over and over, loud, with a glass panel that says who is looking, until somebody stops it
/// here (the panel, any key or click), on the device that asked, or two minutes have gone by.
@MainActor
final class FindThisMac {
    static let shared = FindThisMac()
    static let duration: Duration = .seconds(120)

    private var player: AVAudioPlayer?
    private var loop: Task<Void, Never>?
    private var timeout: Task<Void, Never>?
    private var before: OutputLoudness.Before?
    private var monitors: [Any] = []
    private var question = 0

    var ringing: Bool { loop != nil }

    func ring(from: String, on: Bool) {
        if on { start(asker: EngineModel.shared.device(from)?.name ?? String(localized: "Another device")) } else { stop() }
    }

    private func start(asker: String) {
        let first = !ringing
        if first {
            FindSounds.stopPreview()
            guard let player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: FindSounds.chosen)) else {
                Task { await EngineModel.shared.tandem?.ringing(on: false) }
                return
            }
            self.player = player
            before = OutputLoudness.raise()
            player.volume = 1
            // Again and again with a short rest between, so a short alert sound reads as a ring and not as one long tone.
            loop = Task { @MainActor in
                while !Task.isCancelled {
                    player.currentTime = 0
                    player.play()
                    try? await Task.sleep(for: .seconds(max(player.duration, 0.4) + 0.35))
                }
            }
            listenForSomeone()
        }
        // Each new ask starts the two minutes again and says who asked last.
        timeout?.cancel()
        timeout = Task { @MainActor in
            try? await Task.sleep(for: Self.duration)
            if !Task.isCancelled { self.stop() }
        }
        GlassQuestion.shared.ask(
            symbol: "speaker.wave.3.fill",
            title: String(localized: "\(asker) is looking for this Mac"),
            body: String(localized: "This Mac plays a sound until you stop it. Any key or click stops it too."),
            answers: [.init(title: "Stop", prominent: true) { [weak self] in self?.stop() }],
            closeAfter: Self.duration
        )
        question = GlassQuestion.shared.asked
        Task { await EngineModel.shared.tandem?.ringing(on: true) }
    }

    func stop() {
        guard ringing else { return }
        loop?.cancel()
        loop = nil
        timeout?.cancel()
        timeout = nil
        player?.stop()
        player = nil
        if let before { OutputLoudness.restore(before) }
        before = nil
        monitors.forEach(NSEvent.removeMonitor)
        monitors = []
        if GlassQuestion.shared.asked == question { GlassQuestion.shared.close() }
        Task { await EngineModel.shared.tandem?.ringing(on: false) }
    }

    /// Somebody who found the Mac presses a key or clicks: that is the end of it. A moment passes first, so the click that was already
    /// on its way does not count. Keys of other apps are only seen with Accessibility allowed; clicks always.
    private func listenForSomeone() {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.2))
            guard ringing, monitors.isEmpty else { return }
            let mask: NSEvent.EventTypeMask = [.keyDown, .leftMouseDown, .rightMouseDown, .otherMouseDown]
            let found: () -> Void = { MainActor.assumeIsolated { FindThisMac.shared.stop() } }
            if let global = NSEvent.addGlobalMonitorForEvents(matching: mask, handler: { _ in found() }) { monitors.append(global) }
            if let local = NSEvent.addLocalMonitorForEvents(matching: mask, handler: { event in found(); return event }) { monitors.append(local) }
        }
    }
}

/// The name of the Wi-Fi this Mac is on, sent in its status so a device that looks for it later can say where it was. Read again every
/// few minutes at most: without Location access it comes from a command line tool.
@MainActor
enum WiFiName {
    private static var known: (name: String?, at: Date)?

    static func current() async -> String? {
        if let known, Date().timeIntervalSince(known.at) < 120 { return known.name }
        let name = await Task.detached { CurrentWiFi.ssid() }.value
        known = (name, Date())
        return name
    }
}

/// The other devices this Mac makes ring, as their own reports say (`Ringing`). A device that never reports (an older version) is let go
/// of after a while, as the Find phone button did before.
@MainActor
@Observable
final class FindOthers {
    static let shared = FindOthers()
    private(set) var ringing: Set<String> = []
    @ObservationIgnored private var fallbacks: [String: Task<Void, Never>] = [:]

    func ring(_ id: String, on: Bool) {
        if on { ringing.insert(id) } else { ringing.remove(id) }
        fallbacks[id]?.cancel()
        if on {
            fallbacks[id] = Task { @MainActor in
                try? await Task.sleep(for: FindThisMac.duration + .seconds(15))
                if !Task.isCancelled { self.ringing.remove(id) }
            }
        }
        Task { try? await EngineModel.shared.tandem?.ring(target: id, on: on) }
    }

    func reported(_ id: String, on: Bool) {
        if on { ringing.insert(id) } else {
            ringing.remove(id)
            fallbacks[id]?.cancel()
        }
    }
}
