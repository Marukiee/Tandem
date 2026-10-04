import AppKit
import SwiftUI
import TandemCore

// MARK: Menu bar panel

/// The action in the menu bar panel: three buttons, one for each thing the phone can be asked for.
/// Only shown when an Android phone is paired.
struct MenuInsertFromPhone: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let phones = model.devices.filter { $0.platform == .android }
        if !phones.isEmpty {
            let ready = phones.contains { $0.online }
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "camera.viewfinder")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.indigo)
                    Text("Insert from phone")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 6)
                GlassEffectContainer(spacing: 6) {
                    HStack(spacing: 6) {
                        ForEach(InsertKind.allCases) { kind in
                            Button {
                                InsertFromPhone.shared.begin(kind: kind)
                            } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: kind.symbol)
                                        .font(.system(size: 16, weight: .medium))
                                        .frame(height: 20)
                                    Text(verbatim: kind.title)
                                        .font(.caption2.weight(.medium))
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 7)
                            }
                            .buttonStyle(.glass)
                            .buttonBorderShape(.roundedRectangle(radius: 16))
                            .hoverSwell(1.04)
                            .help(kind.instruction)
                        }
                    }
                }
                .opacity(ready ? 1 : 0.55)
            }
            .padding(.horizontal, 4)
        }
    }
}

// MARK: Settings

/// The shortcut for "Insert from phone", as a section of the General tab.
struct InsertSettingsSection: View {
    @LocalState private var enabled = InsertShortcut.enabled
    @LocalState private var shortcut = InsertShortcut.current
    @LocalState private var recording = false
    @LocalState private var monitor: Any?

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Shortcut to insert from your phone")
                    Text("Works in every app. Press it again to close the panel.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .onChange(of: enabled) { _, on in
                InsertShortcut.enabled = on
                InsertShortcutCenter.shared.apply()
            }
            if enabled {
                LabeledContent("Shortcut") {
                    HStack(spacing: 8) {
                        recorder
                        if shortcut != .standard {
                            Button("Reset") {
                                InsertShortcut.reset()
                                shortcut = InsertShortcut.current
                                InsertShortcutCenter.shared.apply()
                            }
                            .controlSize(.small)
                        }
                    }
                }
            }
        } header: {
            Text("Insert from phone")
        } footer: {
            Text("Take a photo, scan a document or choose a picture on your phone, and it lands where your cursor is. Pasting for you needs the Accessibility permission; without it the picture waits on the clipboard.")
        }
        .animation(.tandem, value: enabled)
        .onDisappear(perform: stopRecording)
    }

    private var recorder: some View {
        Button {
            recording ? stopRecording() : startRecording()
        } label: {
            Group {
                if recording {
                    Text("Type a shortcut")
                        .foregroundStyle(.secondary)
                } else {
                    HStack(spacing: 3) {
                        ForEach(Array(shortcut.keycaps.enumerated()), id: \.offset) { _, cap in
                            Text(verbatim: cap)
                                .font(.system(size: 12, weight: .semibold, design: .rounded))
                                .frame(minWidth: 20, minHeight: 20)
                                .padding(.horizontal, 2)
                                .background(Color.primary.opacity(0.08), in: .rect(cornerRadius: 6, style: .continuous))
                        }
                    }
                }
            }
            .frame(minWidth: 120, minHeight: 24)
            .padding(.horizontal, 8)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .strokeBorder(recording ? Palette.indigo : Color.primary.opacity(0.12), lineWidth: recording ? 1.5 : 1)
            )
        }
        .buttonStyle(.bouncy)
        .animation(.tandemFade, value: recording)
    }

    private func startRecording() {
        recording = true
        InsertShortcutCenter.shared.pause(true)
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode == 53 { // Escape leaves the old one as it was
                stopRecording()
                return nil
            }
            let mods = InsertShortcut.carbon(event.modifierFlags)
            // Without Command, Control or Option the shortcut would eat ordinary typing in every app.
            let strong = event.modifierFlags.intersection([.command, .control, .option]).isEmpty == false
            guard strong else { NSSound.beep(); return nil }
            shortcut = InsertShortcut(keyCode: UInt32(event.keyCode), modifiers: mods, label: InsertShortcut.label(for: event))
            InsertShortcut.current = shortcut
            stopRecording()
            return nil
        }
    }

    private func stopRecording() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        if recording {
            recording = false
            InsertShortcutCenter.shared.pause(false)
        }
    }
}
