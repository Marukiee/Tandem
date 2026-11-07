import SwiftUI
import TandemCore

/// The watch of a phone as a device of its own. It only ever talks to that phone, so everything here goes by way of it, and what needs
/// the phone is grey and says why while the phone is away.
struct WatchDetail: View {
    @Environment(EngineModel.self) private var model
    let phone: TandemDevice
    var onOpenPhone: () -> Void = {}

    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmUnpair = false

    private var watch: TandemWatchStatus? { phone.status.watch }
    private var reachable: Bool { phone.online && watch?.online == true }
    private var name: String { watch?.name ?? String(localized: "Watch") }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                actions
                settings
                removal
            }
            .pagePadding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .animation(.tandem, value: reachable)
        .alert("Rename watch", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") { model.renameWatch(phone.id, name: newName == String(localized: "Watch") ? "" : newName) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your phone and your other computers show it by this name too.")
        }
        .confirmationDialog("Unpair \(name)?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { model.unpairWatch(phone.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Pairing it again is done on your phone.")
        }
    }

    private var header: some View {
        Card(radius: Metrics.card, padding: 22, tint: reachable ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                Image(systemName: "applewatch")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(Palette.indigo)
                    .frame(width: 80, height: 80)
                    .background(Palette.indigo.opacity(0.14), in: Circle())
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: name).font(.title.weight(.bold)).lineLimit(1).contentTransition(.opacity)
                    Text("Wear OS \u{00B7} by way of \(phone.name)").font(.callout).foregroundStyle(.secondary).lineLimit(1)
                    HStack(spacing: 8) {
                        Chip(
                            symbol: reachable ? "checkmark.circle.fill" : "applewatch.slash",
                            text: reachable ? "Connected" : "Watch away",
                            tint: reachable ? .green : .secondary,
                            strong: reachable
                        )
                        if reachable, let level = watch?.level {
                            Chip(symbol: watch?.charging == true ? "bolt.fill" : "battery.75percent", text: "\(Int(level))%")
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var actions: some View {
        ActionGroup(title: "Watch") {
            GlassActionButton(
                title: watch?.ringing == true ? "Stop ringing" : "Find watch",
                symbol: watch?.ringing == true ? "bell.slash.fill" : "applewatch.radiowaves.left.and.right",
                prominent: watch?.ringing == true
            ) {
                model.ringWatch(phone.id, on: watch?.ringing != true)
            }
            .disabled(!reachable)
            .help(reachable ? "" : "The watch is not connected to its phone")
            GlassActionButton(title: "Open \(phone.name)", symbol: "iphone") { onOpenPhone() }
        }
    }

    private var settings: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(symbol: "pencil", title: "Name", subtitle: "\(name). Your phone and your other computers show it by this name too.") {
                    Button("Rename") {
                        newName = name
                        renaming = true
                    }
                    .buttonStyle(.tandemGlass)
                    .disabled(!phone.online)
                    .help(phone.online ? "" : "Its phone is not connected")
                }
                Divider().opacity(0.4).padding(.horizontal, 14)
                SettingRow(symbol: "bed.double", title: "Sleep and workouts", subtitle: "What your watch measured, as far as your phone gives it to this Mac") {
                    Button("Open") { AppScenes.showSettings(.health) }
                        .buttonStyle(.tandemGlass)
                }
            }
        }
    }

    /// At the very bottom of the page: it is done once, if ever, and should not sit between the things that are used.
    private var removal: some View {
        Card(radius: Metrics.card, padding: 6) {
            SettingRow(symbol: "trash", title: "Unpair this watch", subtitle: "Pairing it again is done on your phone") {
                Button("Unpair", role: .destructive) { confirmUnpair = true }
                    .buttonStyle(.tandemGlass)
                    .disabled(!phone.online)
                    .help(phone.online ? "" : "Its phone is not connected")
            }
        }
    }
}
