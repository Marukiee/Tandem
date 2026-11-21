import SwiftUI
import TandemCore

/// The settings of one device that are not on its page: its name, for every device of the circle, and what it may do on this Mac.
/// What is used often (the clipboard, its notifications, files without asking, where it stands) stays on the page itself.
struct DeviceSettingsSheet: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let device: TandemDevice

    @State private var name = ""
    @State private var denied: Set<TandemPermission> = []

    /// What a device may do on a Mac, in the order it is asked. Messages and calls are what a phone gives, not a Mac, and files
    /// without asking and the clipboard are on the page of the device.
    private static let shown: [(TandemPermission, String, LocalizedStringKey, LocalizedStringKey)] = [
        (.browseFiles, "folder", "Browse files on this Mac", "See and fetch what is in the folders you share"),
        (.deleteFiles, "trash", "Delete files on this Mac", "Remove files from the folders you share"),
        (.seeNotifications, "bell", "See this Mac's notifications", "Notifications from this Mac show up there"),
        (.control, "cursorarrow.rays", "Control this Mac", "The mouse, the keyboard and the screen"),
        (.lock, "lock", "Lock this Mac", "From its own screen or a button"),
        (.unlock, "lock.open", "Unlock this Mac", "Without typing the password here"),
        (.restart, "arrow.clockwise", "Restart this Mac", "When it asks, after a confirmation"),
        (.wifiPassword, "wifi", "Get the Wi-Fi password", "When it has no network and asks for this one"),
    ]

    private var canRename: Bool { model.canRename(device.id) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 40, deviceID: device.id)
                VStack(alignment: .leading, spacing: 1) {
                    Text(device.name).font(.title3.weight(.semibold)).lineLimit(1)
                    Text("Settings for this device").font(.callout).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Done") { save(); dismiss() }
                    .buttonStyle(.tandemGlassProminent)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(20)

            Form {
                Section {
                    TextField("Name", text: $name)
                        .disabled(!canRename)
                        .onSubmit(save)
                } header: {
                    Text("Name")
                } footer: {
                    if canRename {
                        Text("Every device in your circle shows this name.")
                    } else {
                        Text("Update Tandem on \(device.name) to name it from here.")
                    }
                }

                if device.isComputer || device.platform == .android {
                    Section {
                        Button {
                            dismiss()
                            AppScenes.showSettings(.pointer)
                        } label: {
                            LabeledContent {
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            } label: {
                                Label {
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text("Mouse and keyboard")
                                        Text("Which computers share them, and how the screens stand").font(.caption).foregroundStyle(.secondary)
                                    }
                                } icon: {
                                    Image(systemName: "cursorarrow.motionlines").foregroundStyle(Palette.indigo)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section {
                    ForEach(Self.shown, id: \.0) { permission, symbol, title, subtitle in
                        Toggle(isOn: Binding(
                            get: { !denied.contains(permission) },
                            set: { allowed in
                                if allowed { denied.remove(permission) } else { denied.insert(permission) }
                                model.setPermission(permission, allowed: allowed, for: device.id)
                            }
                        )) {
                            Label {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(title)
                                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: symbol).foregroundStyle(Palette.indigo)
                            }
                        }
                    }
                } header: {
                    Text("What \(device.name) may do on this Mac")
                }
            }
            .formStyle(.grouped)
        }
        .frame(width: 520, height: 560)
        .onAppear {
            name = device.name
            denied = Set(model.deniedPermissions(device.id))
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canRename, !trimmed.isEmpty, trimmed != device.name else { return }
        model.renameDevice(device.id, to: trimmed)
    }
}
