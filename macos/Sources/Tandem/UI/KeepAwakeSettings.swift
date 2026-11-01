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
