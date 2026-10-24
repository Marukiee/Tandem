import AppKit
import AudioToolbox
import CoreAudio
import TandemCore

/// Checks by itself, with sweeps too high to hear, on which side of this Mac a device stands, each time that matters: an extra screen
/// starts, a device was moved, a computer next to this screen connects or this Mac wakes, the pointer goes over to a device that was not
/// checked for a while, and every few minutes (the setting, 2 by default) while it is there. When it is checked is up to `side_check.rs`
/// in the core. A side that changed moves the extra screen and the edge of the shared mouse, with one quiet toast; nothing ever asks or
/// waits for an answer.
@MainActor
final class SideWatch {
    static let shared = SideWatch()
    /// The setting "Check the side by itself", on unless the person turned it off.
    static let enabledKey = "sideCheckByItself"
    /// How often, in minutes, a device next to this screen is checked while it is there.
    static let everyKey = "sideCheckEveryMinutes"
    static let defaultEvery = 2

    private var enabled: Bool { UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true }
    private let schedule = TandemSideSchedule()
    private var observers: [NSObjectProtocol] = []
    private var tick: Task<Void, Never>?
    /// Sides found that wait until the pointer is off that device, by device.
    private var waiting: [String: String] = [:]
    /// The side last put in use per device, chosen or found, also when it is not kept (the person wants to be asked each time): without
    /// it every check of such a device would look like a change and say so again.
    private var applied: [String: String] = [:]
    private var settling: Task<Void, Never>?

    private init() {
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.woke() } })
        // Every minute each device that counts is offered to the schedule, which lets one go as often as the setting says.
        tick = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                guard let self else { return }
                let every = UserDefaults.standard.object(forKey: Self.everyKey) as? Int ?? Self.defaultEvery
                self.schedule.setEveryMinutes(minutes: UInt32(max(every, 0)))
                for device in EngineModel.shared.devices { self.consider(device.id, .inUse) }
            }
        }
    }

    // MARK: What happened

    func connected(_ id: String) {
        // A moment for the device to settle in after it came back, as it does after a wake too.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            consider(id, .connected)
        }
    }

    func moved(_ id: String) { consider(id, .moved) }

    func pointerWentOver(_ id: String) { consider(id, .pointerOver) }

    /// The shared mouse was turned on here: every computer left or right of this screen is checked, as if it had been moved.
    func mouseTurnedOn() {
        Task { @MainActor in
            // The tap and the layout are up a moment later.
            try? await Task.sleep(for: .seconds(1))
            for device in EngineModel.shared.devices { consider(device.id, .moved) }
        }
    }

    /// A phone turned on taking the mouse of a computer (its switch or its tile): it is checked, as if it had been moved.
    func pointerAllowed(_ id: String) { consider(id, .moved) }

    /// An extra screen for `peer` is on. When its side is not known yet it is found by ear first, and the question comes only when that
    /// does not work (see `findsFirst`).
    func screenStarted(_ peer: String) {
        guard let device = EngineModel.shared.device(peer) else { return }
        guard DeviceSides.side(of: peer) == nil, findsFirst(device) else { return consider(peer, .screenStarted) }
        Task { @MainActor in
            var found: String?
            if AudioRoute.speakersCanPlay(), schedule.start(device: peer, reason: .screenStarted) { found = await check(device) }
            if found == nil, DeviceSides.side(of: peer) == nil, ScreenHost.shared.isExtending(to: peer) { SidePrompt.shared.ask(device) }
        }
    }

    /// Whether a second screen whose side is not known waits for a check by ear before the question: not when the person turned the
    /// checks off or wants to be asked each time, nor for a device that cannot listen.
    func findsFirst(_ device: TandemDevice) -> Bool {
        enabled && device.caps.contains("side.listen") && !DeviceSides.asksEachTime(device.id)
    }

    /// The person chose or found the side of `id`.
    func chosen(_ side: String, for id: String) {
        applied[id] = side
    }

    private func woke() {
        Task { @MainActor in
            // The network comes back a few seconds after the screen; devices that are not back by then are checked when they connect.
            try? await Task.sleep(for: .seconds(5))
            for device in EngineModel.shared.devices { consider(device.id, .woke) }
        }
    }

    // MARK: Checking

    /// The side matters for a device with an extra screen of this Mac, or one left or right of it for the shared mouse. One put above or
    /// below was put there by hand and stays.
    private func counts(_ device: TandemDevice) -> Bool {
        guard device.online, device.caps.contains("side.listen") else { return false }
        if ScreenHost.shared.isExtending(to: device.id) { return true }
        let share = PointerShare.shared
        guard share.enabled, let edge = share.layout[device.id]?.edge else { return false }
        return edge == "left" || edge == "right"
    }

    private func consider(_ id: String, _ reason: TandemSideReason) {
        // The sound first: a Mac that is muted or plays into headphones is not a device that did not hear, so it does not count as a miss.
        guard enabled, let device = EngineModel.shared.device(id), counts(device), AudioRoute.speakersCanPlay(),
              schedule.start(device: id, reason: reason)
        else { return }
        Task { @MainActor in _ = await check(device) }
    }

    /// One check, and a second when the first says another side than the one in use: a sweep that the room happened to swallow on one
    /// side must not move a screen the wrong way. The side found, or nothing.
    private func check(_ device: TandemDevice) async -> String? {
        let edge = PointerShare.shared.layout[device.id].map(\.edge).flatMap { ["left", "right"].contains($0) ? $0 : nil }
        let known = edge ?? applied[device.id] ?? DeviceSides.side(of: device.id)
        var found = await SideFinder.check(device)
        if let known, let side = Self.name(found), side != known {
            try? await Task.sleep(for: .milliseconds(600))
            if await SideFinder.check(device) != found { found = .unsure }
        }
        schedule.done(device: device.id, side: found)
        guard let side = Self.name(found) else { return nil }
        if side != known { move(device, to: side) }
        return side
    }

    private static func name(_ side: TandemSide) -> String? {
        switch side {
        case .left: "left"
        case .right: "right"
        default: nil
        }
    }

    // MARK: Moving

    private func move(_ device: TandemDevice, to side: String) {
        waiting[device.id] = side
        settle()
        guard !waiting.isEmpty, settling == nil else { return }
        settling = Task { @MainActor [weak self] in
            // The pointer comes back from a device or an extra screen sooner or later; after a few minutes the side may well have changed again.
            var seconds = 0
            while seconds < 240, self?.waiting.isEmpty == false {
                try? await Task.sleep(for: .seconds(1))
                seconds += 1
                self?.settle()
            }
            self?.waiting = [:]
            self?.settling = nil
        }
    }

    /// Moves what waits, except for a device the pointer is on: its screen would jump away under it, and a computer that has the pointer
    /// gives it back by the edge it came in by.
    private func settle() {
        for (id, side) in waiting where !pointerIsOn(id) {
            waiting[id] = nil
            applied[id] = side
            DeviceSides.moved(to: side, for: id)
            let name = EngineModel.shared.device(id)?.name ?? String(localized: "The device")
            FloatingToast.show(
                side == "left" ? String(localized: "\(name) is now on the left") : String(localized: "\(name) is now on the right"),
                symbol: side == "left" ? "rectangle.lefthalf.inset.filled.arrow.left" : "rectangle.righthalf.inset.filled.arrow.right"
            )
        }
    }

    private func pointerIsOn(_ id: String) -> Bool {
        if PointerShare.shared.remote?.device == id { return true }
        guard ScreenHost.shared.isExtending(to: id), let at = CGEvent(source: nil)?.location else { return false }
        var display: CGDirectDisplayID = 0
        var count: UInt32 = 0
        guard CGGetDisplaysWithPoint(at, 1, &display, &count) == .success, count > 0 else { return false }
        return VirtualDisplay.isTandem(display)
    }
}

/// Where this Mac's sound goes and whether its microphone is free, for a check that nobody asked for.
enum AudioRoute {
    private static let headphones: UInt32 = 0x6864_706E // 'hdpn'
    private static let internalSpeaker: UInt32 = 0x6973_706B // 'ispk'

    /// Whether the quiet sweeps would come out of speakers in this room: not when the sound is off or nearly off, goes to headphones,
    /// Bluetooth or AirPlay, or is sent to a phone (as its speaker, or with a screen), where the listener could hear its own speaker.
    @MainActor
    static func speakersCanPlay() -> Bool {
        if EngineModel.shared.speaker.device != nil { return false }
        guard let output = defaultDevice(kAudioHardwarePropertyDefaultOutputDevice) else { return false }
        let transport: UInt32 = value(output, kAudioDevicePropertyTransportType, kAudioObjectPropertyScopeGlobal) ?? kAudioDeviceTransportTypeUnknown
        let elsewhere = [
            kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE, kAudioDeviceTransportTypeAirPlay,
            kAudioDeviceTransportTypeVirtual, kAudioDeviceTransportTypeAggregate, kAudioDeviceTransportTypeAutoAggregate,
            kAudioDeviceTransportTypeUnknown,
        ]
        if elsewhere.contains(transport) { return false }
        // The jack of a Mac is the same built-in device with another source, or a device of its own whose source says headphones.
        if let source: UInt32 = value(output, kAudioDevicePropertyDataSource, kAudioObjectPropertyScopeOutput),
           source == headphones || (transport == kAudioDeviceTransportTypeBuiltIn && source != internalSpeaker) {
            return false
        }
        if let muted: UInt32 = value(output, kAudioDevicePropertyMute, kAudioObjectPropertyScopeOutput), muted != 0 { return false }
        if let volume: Float32 = value(output, kAudioHardwareServiceDeviceProperty_VirtualMainVolume, kAudioObjectPropertyScopeOutput), volume < 0.05 {
            return false
        }
        return true
    }

    /// Something else records with the microphone (a call, a dictation): a check by itself leaves it alone.
    static func microphoneInUse() -> Bool {
        guard let input = defaultDevice(kAudioHardwarePropertyDefaultInputDevice) else { return true }
        let running: UInt32 = value(input, kAudioDevicePropertyDeviceIsRunningSomewhere, kAudioObjectPropertyScopeGlobal) ?? 0
        return running != 0
    }

    private static func defaultDevice(_ selector: AudioObjectPropertySelector) -> AudioObjectID? {
        let id: AudioObjectID? = value(AudioObjectID(kAudioObjectSystemObject), selector, kAudioObjectPropertyScopeGlobal)
        return id.flatMap { $0 == kAudioObjectUnknown ? nil : $0 }
    }

    private static func value<T: BitwiseCopyable>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope) -> T? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectHasProperty(object, &address) else { return nil }
        var size = UInt32(MemoryLayout<T>.size)
        let result = UnsafeMutablePointer<T>.allocate(capacity: 1)
        defer { result.deallocate() }
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, result) == noErr else { return nil }
        return result.pointee
    }
}
