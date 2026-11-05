import LocalAuthentication
import SwiftUI
import TandemCore

/// Keeping this Mac awake to reach it from elsewhere, in General. The same switch is the cup in the panel.
struct KeepAwakeSettings: View {
    private var awake: KeepAwake { KeepAwake.shared }

    var body: some View {
        Section("Keep awake") {
            DescribedToggle(
                "Keep this Mac awake",
                subtitle: "So your other devices can reach it while you are away. Turns itself off when the battery drops below \(KeepAwake.batteryFloor)%.",
                isOn: Binding(get: { awake.on }, set: { awake.set($0) })
            )
            DescribedToggle(
                "Also with the lid closed",
                subtitle: "macOS asks for your password once. With the lid closed Tandem makes a screen of its own to show.",
                isOn: Binding(get: { awake.lidClosed }, set: { awake.lidClosed = $0 })
            )
            .disabled(awake.asking)
        }
    }
}

/// The cup in the header of the panel: one press keeps this Mac awake, another lets it sleep again.
struct KeepAwakeButton: View {
    private var awake: KeepAwake { KeepAwake.shared }

    var body: some View {
        RoundIconButton(symbol: awake.on ? "cup.and.heat.waves.fill" : "cup.and.saucer", tint: awake.on ? Palette.indigo : nil, size: 30) {
            awake.toggle()
            FloatingToast.show(
                awake.on ? String(localized: "This Mac stays awake") : String(localized: "This Mac may sleep again"),
                symbol: awake.on ? "cup.and.heat.waves.fill" : "cup.and.saucer"
            )
        }
        .help(awake.on ? "Let this Mac sleep again" : "Keep this Mac awake")
        .disabled(awake.asking)
    }
}

/// Keep awake of another computer, on its page: on while it shows the switch on there.
struct RemoteKeepAwakeButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let awake = device.status.awake == true
        GlassActionButton(title: awake ? "Kept awake" : "Keep awake", symbol: awake ? "cup.and.heat.waves.fill" : "cup.and.saucer", prominent: awake) {
            // An older Tandem there has no such switch, which is said when it is pressed.
            guard device.status.awake != nil else {
                return model.showToast(String(localized: "Update Tandem on \(device.name) to keep it awake from here"))
            }
            Task { try? await model.tandem?.keepAwake(target: device.id, on: !awake) }
        }
        .disabled(!device.online)
        .help(awake ? "Let this computer sleep again" : "Keep this computer awake, also with the lid closed")
    }
}

/// Locks another device. Grey while it cannot, and a press says what is missing there; for a phone that opens the setting on it.
struct RemoteLockButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let locked = device.status.locked == true
        GlassActionButton(title: locked ? "Locked" : "Lock", symbol: locked ? "lock.fill" : "lock") {
            switch device.status.lock {
            case .some(true):
                Task { try? await model.tandem?.lock(target: device.id) }
            case .some(false) where device.platform == .android:
                model.openSettings(on: device.id, page: "accessibility")
            case .some(false):
                model.showToast(String(localized: "\(device.name) cannot be locked from here"))
            case .none:
                model.showToast(String(localized: "Update Tandem on \(device.name) to lock it from here"))
            }
        }
        .opacity(device.status.lock == true ? 1 : 0.5)
        .disabled(!device.online)
        .help("Lock the screen of this device")
    }
}

/// How a phone sounds, and one press to the next: sound, vibrate, do not disturb. Without access to the last one it is skipped, and said why.
struct SoundModeButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    private var look: (title: LocalizedStringKey, symbol: String) {
        switch device.status.sound {
        case .vibrate: ("Vibrate", "iphone.gen3.radiowaves.left.and.right")
        case .silent: ("Silent", "bell.slash")
        case .doNotDisturb: ("Do not disturb", "moon.fill")
        case .sound: ("Sound on", "bell")
        case nil: ("Sound mode", "bell")
        }
    }

    var body: some View {
        let mode = device.status.sound
        GlassActionButton(title: look.title, symbol: look.symbol, prominent: mode != nil && mode != .sound) { press() }
            .disabled(!device.online)
            .help("Switch this phone between sound, vibrate and do not disturb")
    }

    private func press() {
        guard let mode = device.status.sound else {
            return model.showToast(String(localized: "Update Tandem on \(device.name) to switch its sound from here"))
        }
        let canDnd = device.status.soundDnd == true
        if mode == .doNotDisturb, !canDnd { return model.openSettings(on: device.id, page: "dnd-access") }
        let next: TandemSoundMode = switch mode {
        case .sound: .vibrate
        case .doNotDisturb: .sound
        case .vibrate, .silent: canDnd ? .doNotDisturb : .sound
        }
        if mode != .sound, next == .sound, !canDnd {
            model.showToast(String(localized: "Do not disturb needs access: allow Tandem in the settings of \(device.name)"))
        }
        Task { try? await model.tandem?.setSoundMode(target: device.id, mode: next) }
    }
}

/// A computer that is locked and lets this Mac unlock it, once Touch ID (or this Mac's password) says the person here is its person. There
/// only while that computer says both.
struct RemoteUnlockButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        GlassActionButton(title: "Unlock", symbol: "lock.open", prominent: true) {
            let context = LAContext()
            guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return }
            let id = device.id
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: String(localized: "unlock \(device.name)")) { ok, _ in
                guard ok else { return }
                Task { @MainActor in try? await model.tandem?.unlock(target: id) }
            }
        }
        .help("Unlock this computer with Touch ID")
    }
}
