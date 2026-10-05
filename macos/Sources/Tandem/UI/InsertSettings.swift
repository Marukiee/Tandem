import AppKit
import SwiftUI
import TandemCore

// MARK: Menu bar panel

/// The action in the menu bar panel: three tiles, one for each thing the phone can be asked for.
/// Only shown when an Android phone is paired.
struct MenuInsertFromPhone: View {
    @Environment(EngineModel.self) private var model
    /// The tile under the pointer: its explanation takes the place of the title above the tiles.
    @LocalState private var hovered: InsertKind?

    var body: some View {
        // Only phones that say they can answer: until an app version does, there is nothing to show.
        let phones = model.devices.filter { $0.platform == .android && $0.caps.contains("capture") }
        if !phones.isEmpty || InsertHUDText.flat {
            let ready = phones.contains { $0.online }
            VStack(alignment: .leading, spacing: 8) {
                Group {
                    if let hovered {
                        Text(verbatim: hovered.instruction).foregroundStyle(Palette.indigo)
                    } else {
                        Text("Insert from phone").foregroundStyle(.secondary)
                    }
                }
                .font(.caption.weight(.semibold))
                .padding(.leading, 10)
                .animation(.tandemFade, value: hovered)
                HStack(spacing: 8) {
                    ForEach(InsertKind.allCases.filter { $0 != .picture }) { kind in
                        MenuKindTile(kind: kind, onHover: { inside in
                            if inside { hovered = kind } else if hovered == kind { hovered = nil }
                        }) { InsertFromPhone.shared.begin(kind: kind) }
                    }
                }
                .opacity(ready ? 1 : 0.55)
            }
        }
    }
}

/// The symbol at the left, the name next to it, on the same plain tint as the rows around it. Under the pointer the
/// tint deepens and the symbol fills: colour only, no bounce.
private struct MenuKindTile: View {
    let kind: InsertKind
    var onHover: (Bool) -> Void
    let action: () -> Void
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(hovering ? Palette.indigo : Palette.indigo.opacity(0.15))
                    Image(systemName: kind.symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(hovering ? Color.white : Palette.indigo)
                }
                .frame(width: 32, height: 32)
                Text(verbatim: kind.title)
                    .font(.system(size: 12.5, weight: .semibold))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(shape.fill(Color.primary.opacity(hovering ? 0.11 : 0.06)))
            .contentShape(shape)
        }
        .buttonStyle(RoundPressStyle())
        .onHover { inside in
            hovering = inside
            onHover(inside)
        }
        .animation(.tandemFade, value: hovering)
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
