import SwiftUI

/// Reaching this Mac while it is locked, in the settings of the screen. Off until Screen Sharing is on and the password of this Mac is
/// checked; what is missing is said when the switch is turned on, not before.
struct LockedAccessSection: View {
    @State private var on = LockedAccess.hasPassword
    @State private var sharing = LockedAccess.screenSharingOn
    @State private var asking = false
    @State private var password = ""
    @State private var checking = false
    @State private var problem: String?
    /// The password was right and Remote Management still said no, which only its own settings change.
    @State private var managementRefused = false
    @State private var failure = LockedAccess.lastFailure
    @State private var unlockFromAfar = LockedAccess.unlockFromAfar

    var body: some View {
        Section {
            DescribedToggle(
                "Reach this Mac while it is locked",
                subtitle: "Your phone sees the lock screen and you type your password there. Uses Screen Sharing of macOS, for devices that may always look and always control below.",
                isOn: Binding(get: { on || asking }, set: { turn($0) })
            )
            if asking {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Screen Sharing asks for the password of this Mac before it shows the lock screen. It stays in your Keychain, and is never sent to another device.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack {
                        SecureField("Password of \(LockedAccess.user)", text: $password)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit(save)
                        Button(checking ? "Checking" : "Save", action: save)
                            .buttonStyle(.borderedProminent)
                            .tint(Palette.indigo)
                            .disabled(password.isEmpty || checking)
                    }
                    if LockedAccess.remoteManagementOn {
                        // Remote Management lets in only who it allows, an administrator too, and its switches sit two sheets deep.
                        RemoteManagementSteps()
                    }
                    if let problem {
                        Label(problem, systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(Palette.urgent)
                    }
                }
            } else if on, let failure {
                // The last try from a phone did not get in, most often a password that was changed since.
                HStack {
                    Label(failure, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(Palette.urgent)
                    Spacer(minLength: 8)
                    Button("Enter again") { asking = true }
                }
            } else if on, !sharing {
                HStack {
                    Label("Screen Sharing is off, so devices cannot reach this Mac while it is locked.", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(Palette.urgent)
                    Spacer(minLength: 8)
                    Button("Open") { LockedAccess.openSharingSettings() }
                }
            }
            // Said when it is used, not before: without the password above there is nothing to unlock with.
            DescribedToggle(
                "Unlock with a fingerprint on your phone",
                subtitle: "Your phone asks for your fingerprint once per session, and this Mac types its password on the lock screen. The phone checks the fingerprint; this Mac takes its word.",
                isOn: Binding(
                    get: { on && unlockFromAfar },
                    set: { wanted in
                        guard on else {
                            FloatingToast.show(String(localized: "Turn on reaching this Mac while it is locked first"), symbol: "lock.display")
                            return
                        }
                        LockedAccess.unlockFromAfar = wanted
                        unlockFromAfar = wanted
                    }
                )
            )
            .opacity(on ? 1 : 0.5)
        }
        .animation(.tandem, value: asking)
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in refresh() }
    }

    private func turn(_ wanted: Bool) {
        if !wanted {
            LockedAccess.forget()
            on = false
            asking = false
            password = ""
            problem = nil
            managementRefused = false
            return
        }
        refresh()
        guard sharing else {
            FloatingToast.show(String(localized: "Turn on Screen Sharing first, in System Settings under General, Sharing"), symbol: "rectangle.on.rectangle")
            LockedAccess.openSharingSettings()
            return
        }
        asking = true
    }

    private func save() {
        guard !password.isEmpty, !checking else { return }
        checking = true
        problem = nil
        managementRefused = false
        let entered = password
        Task {
            do {
                try await LockedAccess.save(password: entered)
                password = ""
                asking = false
                on = true
                failure = nil
                // A Mac that had nothing to offer before (no Screen Recording) has a screen now, which the Hello only says after a restart.
                if ScreenHost.shared.needsRestart {
                    FloatingToast.show(String(localized: "Restart Tandem so your other devices can see that this Mac is ready."), symbol: "arrow.clockwise")
                }
            } catch ScreenSharingClient.Failure.wrongPassword {
                managementRefused = LockedAccess.remoteManagementOn
                problem = managementRefused
                    ? String(localized: "Remote Management turned it down. Follow the steps above, then save again.")
                    : String(localized: "That is not the password of this Mac.")
            } catch ScreenSharingClient.Failure.unreachable {
                problem = String(localized: "Screen Sharing does not answer. Turn it on in System Settings under General, Sharing.")
            } catch {
                problem = String(localized: "Screen Sharing did not let Tandem in: \(String(describing: error))")
            }
            checking = false
        }
    }

    private func refresh() {
        sharing = LockedAccess.screenSharingOn
        on = LockedAccess.hasPassword
        failure = LockedAccess.lastFailure
    }
}

/// Where Remote Management keeps who may observe and control, in the words System Settings uses.
private struct RemoteManagementSteps: View {
    private let steps: [LocalizedStringKey] = [
        "Click the i next to Remote Management.",
        "At Allow access for, choose All users, or add yourself with the +.",
        "Click Options… and check Observe and Control.",
        "Click OK and Done, then save here.",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Remote Management is on and decides who comes in.")
                    .font(.caption.weight(.semibold))
                Spacer(minLength: 8)
                Button("Open Remote Management") { LockedAccess.openRemoteManagementSettings() }
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("\(index + 1).")
                        .monospacedDigit()
                    Text(step)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }
}
