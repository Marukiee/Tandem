import AppKit
import ApplicationServices
import Carbon.HIToolbox
import SwiftUI

/// The Clipboard tab of Settings: whether a history is kept at all, how much, from which apps, and the shortcut.
struct ClipboardSettings: View {
    @LocalState private var confirmClear = false
    @LocalState private var trusted = AXIsProcessTrusted()

    var body: some View {
        @Bindable var history = ClipboardHistory.shared
        Form {
            Section {
                SettingToggle(
                    "Keep a history of what you copy",
                    subtitle: "Text, links and pictures, and what arrives from your other devices. It stays on this Mac.",
                    isOn: $history.enabled
                )
            } footer: {
                Text("Copies that an app marks as secret, such as passwords from a password manager, are never saved.")
            }

            Section("Opening it") {
                SettingToggle("Open with a shortcut", subtitle: "From any app.", isOn: $history.hotkeyOn)
                LabeledContent("Shortcut") {
                    HStack(spacing: 8) {
                        if history.hotkey != .standard {
                            Button("Reset") { history.hotkey = .standard }
                                .buttonStyle(.borderless)
                                .foregroundStyle(.secondary)
                        }
                        ShortcutRecorder(shortcut: $history.hotkey)
                    }
                }
                .disabled(!history.hotkeyOn)
                SettingToggle(
                    "Paste into the app you were in",
                    subtitle: "Return pastes straight away. Without this it only copies.",
                    isOn: $history.pastesDirectly
                )
                if history.pastesDirectly && !trusted {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Tandem may not press keys yet")
                            Text("Pasting needs the Accessibility permission.").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 12)
                        Button("Open Settings") {
                            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
                                NSWorkspace.shared.open(url)
                            }
                        }
                    }
                    .transition(.opacity)
                }
            }

            Section {
                Picker("Number of items", selection: $history.limit) {
                    ForEach([100, 250, 500, 1000], id: \.self) { Text("\($0)").tag($0) }
                }
                Picker("Keep them for", selection: $history.days) {
                    Text("A week").tag(7)
                    Text("30 days").tag(30)
                    Text("90 days").tag(90)
                    Text("A year").tag(365)
                }
                SettingToggle("Include pictures", subtitle: "They take the most room.", isOn: $history.recordsImages)
                // Not a LabeledContent: with two lines of text it puts the button level with the first one.
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Saved now")
                        Text(summary(history)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 12)
                    Button("Clear…", role: .destructive) { confirmClear = true }
                        .disabled(history.items.isEmpty)
                }
            } header: {
                Text("What is kept")
            } footer: {
                Text("Pinned items stay, whatever the number or the days.")
            }

            IgnoredAppsSection()
        }
        .formStyle(.grouped)
        .animation(.tandem, value: trusted)
        .animation(.tandem, value: history.pastesDirectly)
        // The permission only changes in System Settings, so it is read again when the person comes back.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            trusted = AXIsProcessTrusted()
        }
        .clearHistoryDialog(isPresented: $confirmClear)
    }

    private func summary(_ history: ClipboardHistory) -> String {
        let count = history.items.count
        let pinned = history.items.filter(\.pinned).count
        var text = String(localized: "\(count) items")
        if pinned > 0 { text += ", " + String(localized: "\(pinned) pinned") }
        if history.diskUsage > 0 { text += ", " + formatBytes(UInt64(history.diskUsage)) }
        return text
    }
}

/// A toggle with a line of explanation under it, as grouped forms show them.
private struct SettingToggle: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey?
    @Binding var isOn: Bool

    init(_ title: LocalizedStringKey, subtitle: LocalizedStringKey? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.subtitle = subtitle
        _isOn = isOn
    }

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

// MARK: Ignored apps

private struct IgnoredAppsSection: View {
    var body: some View {
        let history = ClipboardHistory.shared
        Section {
            if history.ignoredApps.isEmpty {
                Text("No apps are ignored.").foregroundStyle(.secondary)
            }
            ForEach(history.ignoredApps, id: \.self) { bundle in
                HStack(spacing: 10) {
                    Image(nsImage: AppIcons.icon(for: bundle))
                        .resizable()
                        .frame(width: 24, height: 24)
                    Text(history.name(ofApp: bundle))
                    Spacer(minLength: 8)
                    Button {
                        withAnimation(.tandem) { history.stopIgnoring(app: bundle) }
                    } label: {
                        Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .help("Stop ignoring this app")
                }
                .transition(.opacity)
            }
            HStack {
                Spacer()
                Menu("Add an app…") {
                    let running = NSWorkspace.shared.runningApplications
                        .filter { $0.activationPolicy == .regular && $0.bundleIdentifier != nil && $0.bundleIdentifier != Bundle.main.bundleIdentifier }
                        .filter { !history.ignoredApps.contains($0.bundleIdentifier ?? "") }
                        .sorted { ($0.localizedName ?? "") < ($1.localizedName ?? "") }
                    ForEach(running, id: \.processIdentifier) { app in
                        Button(app.localizedName ?? app.bundleIdentifier ?? "") {
                            if let bundle = app.bundleIdentifier { withAnimation(.tandem) { history.ignore(app: bundle) } }
                        }
                    }
                    Divider()
                    Button("Choose…") { chooseApp(history) }
                }
                .menuStyle(.button)
                .fixedSize()
            }
        } header: {
            Text("Ignored apps")
        } footer: {
            Text("What you copy in these apps is never saved.")
        }
        .animation(.tandem, value: history.ignoredApps)
    }

    private func chooseApp(_ history: ClipboardHistory) {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url, let bundle = Bundle(url: url)?.bundleIdentifier else { return }
        withAnimation(.tandem) { history.ignore(app: bundle) }
    }
}

// MARK: Clearing

extension View {
    /// Asks what to clear: everything but the pinned items, or really everything.
    func clearHistoryDialog(isPresented: Binding<Bool>) -> some View {
        confirmationDialog("Clear the clipboard history?", isPresented: isPresented, titleVisibility: .visible) {
            Button("Clear all but pinned", role: .destructive) {
                withAnimation(.tandem) { ClipboardHistory.shared.clear(keepingPinned: true) }
            }
            Button("Clear everything", role: .destructive) {
                withAnimation(.tandem) { ClipboardHistory.shared.clear(keepingPinned: false) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This cannot be undone.")
        }
    }
}

// MARK: Shortcut

/// A button that listens for the next key combination and takes it as the shortcut.
struct ShortcutRecorder: View {
    @Binding var shortcut: Shortcut
    @LocalState private var recording = false
    @LocalState private var monitor: Any?

    var body: some View {
        Button { recording ? stop() : start() } label: {
            Text(recording ? "Press a shortcut" : shortcut.display)
                .font(.system(.callout, design: .rounded).weight(.semibold))
                .foregroundStyle(recording ? Palette.indigo : Color.primary)
                .frame(minWidth: 120)
                .contentTransition(.interpolate)
        }
        .buttonStyle(.glass)
        .animation(.tandemFade, value: recording)
        .onDisappear { if recording { stop() } }
    }

    private func start() {
        recording = true
        ClipboardPanelController.shared.suspendHotkey()
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            // Escape gives up.
            if event.keyCode == 53 {
                stop()
                return nil
            }
            let modifiers = Shortcut.carbonModifiers(from: event.modifierFlags)
            // A shortcut that works everywhere needs a modifier that no typing uses.
            guard modifiers & UInt32(cmdKey | controlKey | optionKey) != 0 else {
                NSSound.beep()
                return nil
            }
            let key = Shortcut.name(forKeyCode: event.keyCode) ?? event.charactersIgnoringModifiers?.uppercased() ?? "?"
            shortcut = Shortcut(keyCode: UInt32(event.keyCode), modifiers: modifiers, key: key)
            stop()
            return nil
        }
    }

    private func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        recording = false
        ClipboardPanelController.shared.registerHotkey()
    }
}
