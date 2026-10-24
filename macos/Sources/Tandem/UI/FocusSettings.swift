import SwiftUI

/// Focus the same on every device, as a section of the General tab. The rows look the same whether or not the shortcut is there:
/// someone may not want this at all. What is missing is said when the person turns sharing on, with the button that adds it.
struct FocusSettingsSection: View {
    @AppStorage(FocusShare.shareKey) private var share = true
    @AppStorage(FocusShare.followOnlyKey) private var followOnly = false
    @LocalState private var askToAdd = false
    @LocalState private var needsICloud = false

    var body: some View {
        Section {
            DescribedToggle(
                "Share Focus",
                subtitle: "Turn on Focus on one of your devices, and the others follow.",
                isOn: $share
            )
            .onChange(of: share) { _, on in
                FocusShare.shared.configure()
                guard on else { return }
                Task {
                    await FocusShare.shared.refresh()
                    if !FocusShare.shared.installed { askToAdd = true }
                }
            }
            Picker("Direction", selection: $followOnly) {
                Text("Both ways").tag(false)
                Text("Only follow").tag(true)
            }
            .disabled(!share)
            .onChange(of: followOnly) { _, _ in FocusShare.shared.configure() }
        } header: {
            Text("Focus")
        } footer: {
            Text("macOS lets apps neither turn on a Focus nor see one, so Tandem does both with a shortcut of its own, Tandem Focus, that you add with one click.")
        }
        .alert("Add the Tandem Focus shortcut", isPresented: $askToAdd) {
            Button("Add Shortcut") {
                Task { if await FocusShare.shared.add() == .needsICloud { needsICloud = true } }
            }
            Button("Not Now", role: .cancel) {}
        } message: {
            Text("Shortcuts asks you once to add it. After that this Mac follows your other devices and they follow this Mac.")
        }
        .alert("Sign in to iCloud", isPresented: $needsICloud) {
            Button("Open Apple Account") { FocusShare.openAppleAccount() }
            Button("Not Now", role: .cancel) {}
        } message: {
            Text("macOS only adds a shortcut that an app brings when this Mac is signed in to iCloud.")
        }
    }
}
