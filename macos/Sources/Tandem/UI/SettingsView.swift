import AppKit
import CoreBluetooth
import ServiceManagement
import SwiftUI
import TandemCore

/// The sections of the Settings window, in the order System Settings would list them.
enum SettingsSection: String, CaseIterable, Identifiable {
    case general, devices, access, hotspot, updates, about

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .general: "General"
        case .devices: "Devices"
        case .access: "Access"
        case .hotspot: "Hotspot"
        case .updates: "Updates"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .devices: "laptopcomputer.and.iphone"
        case .access: "hand.raised"
        case .hotspot: "personalhotspot"
        case .updates: "arrow.down.circle"
        case .about: "info.circle"
        }
    }

    /// The window is as tall as the tab needs, like the system's own Settings windows.
    var height: CGFloat {
        switch self {
        case .general: 600
        case .devices: 460
        case .access: 460
        case .hotspot: 470
        case .updates: 330
        case .about: 360
        }
    }
}

/// The Settings window as macOS draws it for every app: tabs with icons across the top of a
/// window that resizes to the tab, and grouped forms underneath. Nothing here is custom
/// chrome, so it gets the system's glass, spacing and keyboard behaviour for free.
struct SettingsView: View {
    @LocalState private var selection: SettingsSection = DebugSupport.initialSettingsSection() ?? .general

    var body: some View {
        TabView(selection: $selection) {
            ForEach(SettingsSection.allCases) { section in
                detail(for: section)
                    .tabItem { Label(section.title, systemImage: section.symbol) }
                    .tag(section)
            }
        }
        .scenePadding()
        .frame(width: 560, height: selection.height)
    }

    @ViewBuilder
    private func detail(for section: SettingsSection) -> some View {
        switch section {
        case .general: GeneralSettings()
        case .devices: DevicesSettings()
        case .access: AccessSettings()
        case .hotspot: HotspotSettings()
        case .updates: UpdateSettings()
        case .about: AboutSettings()
        }
    }
}

/// A toggle with a line of explanation under it, as grouped forms show them.
private struct DescribedToggle: View {
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
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

// MARK: General

private struct GeneralSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var name = ""
    @FocusState private var nameFocused: Bool
    @LocalState private var startAtLogin = SMAppService.mainApp.status == .enabled
    @AppStorage("showInDock") private var showInDock = true
    @AppStorage("copyCodes") private var copyCodes = true
    @AppStorage("bleMessages") private var bleMessages = true
    @AppStorage(ReceivedImages.copyKey) private var copyImages = true
    @AppStorage(ReceivedImages.pasteKey) private var pasteImages = false
    @LocalState private var folder = DownloadFolder.url
    @LocalState private var folderIsDefault = DownloadFolder.isDefault
    @LocalState private var confirmReset = false
    @LocalState private var language = LanguageSetting.current
    @LocalState private var needsRestart = false

    var body: some View {
        Form {
            Section {
                TextField("Name of this Mac", text: $name)
                    .focused($nameFocused)
                    .onSubmit(commitName)
                    .onChange(of: name) { _, value in model.previewName(value) }
                    .onChange(of: nameFocused) { _, focused in if !focused { commitName() } }
            } header: {
                Text("This Mac")
            } footer: {
                Text("This is how your other devices see it.")
            }

            Section {
                Picker("Language", selection: $language) {
                    Text("Same as the system").tag("system")
                    Text("English").tag("en")
                    Text("Nederlands").tag("nl")
                }
                .onChange(of: language) { _, value in
                    LanguageSetting.apply(value)
                    needsRestart = true
                }
                if needsRestart {
                    LabeledContent {
                        Button("Restart now") { model.relaunch() }
                            .buttonStyle(.glassProminent)
                            .tint(Palette.indigo)
                            .controlSize(.small)
                    } label: {
                        Text("Restart Tandem to change the language.").foregroundStyle(.secondary)
                    }
                    .transition(.opacity)
                }
            }

            Section("Behaviour") {
                Toggle("Start at login", isOn: $startAtLogin)
                    .onChange(of: startAtLogin) { _, on in
                        do {
                            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                        } catch {
                            startAtLogin = SMAppService.mainApp.status == .enabled
                        }
                    }
                Toggle("Show in the Dock", isOn: $showInDock)
                    .onChange(of: showInDock) { _, on in
                        NSApp.setActivationPolicy(on ? .regular : .accessory)
                    }
                DescribedToggle(
                    "Copy codes from text messages to the clipboard",
                    subtitle: "You also get a notification when a code was copied.",
                    isOn: $copyCodes
                )
                DescribedToggle(
                    "Copy pictures from your phone to the clipboard",
                    subtitle: "A picture you send from your phone is ready to paste.",
                    isOn: $copyImages
                )
                DescribedToggle(
                    "Paste them straight away",
                    subtitle: "Where the cursor is when the picture arrives. Needs the Accessibility permission.",
                    isOn: $pasteImages
                )
                .disabled(!copyImages)
                DescribedToggle(
                    "Show and control music",
                    subtitle: "Music from your phone shows here with buttons, and what Spotify and Music play here shows on your phone.",
                    isOn: Binding(get: { model.mediaShare }, set: { model.mediaShare = $0 })
                )
                DescribedToggle(
                    "Bluetooth without a network",
                    subtitle: "Clipboard and notifications from your phone still arrive over Bluetooth when this Mac has no connection.",
                    isOn: $bleMessages
                )
            }

            Section {
                LabeledContent("Folder") {
                    Text(folder.path)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([folder]) }
                    if !folderIsDefault {
                        Button("Use Downloads") {
                            DownloadFolder.reset()
                            refreshFolder()
                        }
                    }
                    Button("Choose…", action: chooseFolder)
                }
            } header: {
                Text("Received files")
            } footer: {
                Text("Files that arrive go straight into this folder. By default that is your Downloads folder.")
            }

            Section {
                LabeledContent("Export settings") {
                    Button("Export…") { SettingsBackup.export(model: model) }
                }
                LabeledContent("Import settings") {
                    Button("Import…") {
                        SettingsBackup.importFile(model: model)
                        refreshFolder()
                    }
                }
            } header: {
                Text("Backup")
            } footer: {
                Text("Only preferences are saved. Your identity, your pairings and the hotspot password stay on this Mac.")
            }

            Section {
                LabeledContent("Forgets this Mac's identity and all pairings. You can pair again afterwards.") {
                    Button("Reset Tandem…", role: .destructive) { confirmReset = true }
                }
            }
        }
        .formStyle(.grouped)
        .onAppear { name = model.myName }
        .onDisappear(perform: commitName)
        // Closing the window does not always end the focus, and the name must not be lost.
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didResignKeyNotification)) { _ in commitName() }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.willCloseNotification)) { _ in commitName() }
        .onChange(of: model.myName) { _, value in
            // A name that changed elsewhere, such as through an import.
            if !nameFocused, value != name { name = value }
        }
        .confirmationDialog("Reset Tandem?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Reset and restart", role: .destructive) { model.resetEverything() }
            Button("Cancel", role: .cancel) {}
        }
        .animation(.tandem, value: needsRestart)
        .animation(.tandem, value: folderIsDefault)
    }

    private func commitName() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            // An empty name is not a name: fall back to the last good one.
            name = EngineModel.savedName ?? model.myName
        } else if trimmed != EngineModel.savedName {
            model.rename(to: trimmed)
        }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = folder
        if panel.runModal() == .OK, let url = panel.url {
            DownloadFolder.url = url
            refreshFolder()
        }
    }

    private func refreshFolder() {
        folder = DownloadFolder.url
        folderIsDefault = DownloadFolder.isDefault
    }
}

// MARK: Devices

private struct DevicesSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var removing: TandemDevice?

    var body: some View {
        Form {
            if model.devices.isEmpty {
                Section {
                    ContentUnavailableView(
                        "No devices yet",
                        systemImage: "iphone.slash",
                        description: Text("Pair your phone to send files and share the clipboard.")
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            ForEach(model.devices, id: \.id) { device in
                Section {
                    LabeledContent {
                        Text(device.connectionText)
                            .foregroundStyle(device.connectionColor)
                    } label: {
                        HStack(spacing: 10) {
                            DeviceGlyph(platform: device.platform, online: device.online, size: 30)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(device.name).font(.body.weight(.medium))
                                Text(device.platform.label).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    DescribedToggle(
                        "Sync clipboard",
                        subtitle: "Text and links you copy show up on both devices",
                        isOn: Binding(get: { device.clipboardEnabled }, set: { model.setSettings(device, clipboard: $0) })
                    )
                    DescribedToggle(
                        "Show its notifications",
                        subtitle: "Phone notifications appear here, and you can reply",
                        isOn: Binding(get: { device.notificationsEnabled }, set: { model.setSettings(device, notifications: $0) })
                    )
                    DescribedToggle(
                        "Accept files automatically",
                        subtitle: "Turn off to be asked before something arrives",
                        isOn: Binding(get: { device.autoAccept }, set: { model.setSettings(device, autoAccept: $0) })
                    )
                    LabeledContent {
                        Button("Remove…", role: .destructive) { removing = device }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Remove this device")
                            Text(device.vouchedByRemoved ? "It was added by a device that has since been removed" : "It leaves the circle everywhere")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: model.devices.map(\.id))
        .confirmationDialog(
            "Remove \(removing?.name ?? "")?",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible,
            presenting: removing
        ) { device in
            Button("Remove for good", role: .destructive) { model.remove(device.id) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("It leaves the circle on every device and cannot come back without a new pairing.")
        }
    }
}

// MARK: Hotspot

private struct HotspotSettings: View {
    @AppStorage(HotspotCoordinator.enabledKey) private var auto = false
    @AppStorage(HotspotCoordinator.delayKey) private var delay = 8.0
    @AppStorage("hotspotSSID") private var ssid = ""
    @LocalState private var password = HotspotCredentials.password() ?? ""
    @LocalState private var bluetooth = CBManager.authorization
    @LocalState private var showSetup = false

    private var credentialsSet: Bool { !ssid.isEmpty && !password.isEmpty }
    private var bluetoothAllowed: Bool { bluetooth == .allowedAlways }
    /// The switch stays grey until everything it needs is in place, so it never looks on
    /// while nothing can happen.
    private var ready: Bool { credentialsSet && bluetoothAllowed }

    var body: some View {
        Form {
            Section {
                LabeledContent {
                    Button("Set up…") { showSetup = true }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Guided setup")
                        Text("Join your phone's hotspot once and Tandem takes over.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                Toggle("Use my phone's hotspot when this Mac has no connection", isOn: Binding(
                    get: { auto && ready },
                    set: { auto = $0 }
                ))
                .disabled(!ready)
                LabeledContent("Wait before asking") {
                    HStack(spacing: 10) {
                        Slider(value: $delay, in: 3 ... 30, step: 1)
                            .frame(width: 180)
                        Text("\(Int(delay)) s")
                            .monospacedDigit()
                            .frame(width: 40, alignment: .trailing)
                    }
                }
                .disabled(!(auto && ready))
            } footer: {
                if !ready {
                    Text("Finish the two steps below to turn this on.")
                }
            }

            Section {
                Requirement(
                    done: bluetoothAllowed,
                    title: "Bluetooth",
                    detail: bluetoothDetail,
                    buttonTitle: bluetoothAllowed ? nil : (bluetooth == .notDetermined ? "Allow" : "Open Settings")
                ) {
                    if bluetooth == .notDetermined { BluetoothPrompt.ask() } else { BluetoothPrompt.openSettings() }
                }
                Requirement(
                    done: credentialsSet,
                    title: "Your phone's hotspot",
                    detail: credentialsSet ? "Network name and password are saved." : "Fill in the network name and password below.",
                    buttonTitle: nil
                ) {}
            } header: {
                Text("What it needs")
            }

            Section {
                TextField("Network name", text: $ssid)
                SecureField("Password", text: $password)
                    .onChange(of: password) { _, value in HotspotCredentials.save(password: value) }
            } header: {
                Text("Your phone's hotspot")
            } footer: {
                Text("Tandem needs these once to join the hotspot. They are stored privately on this Mac and never leave it.")
            }
        }
        .formStyle(.grouped)
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
            bluetooth = CBManager.authorization
        }
        .sheet(isPresented: $showSetup) { HotspotSetupSheet() }
    }

    private var bluetoothDetail: LocalizedStringKey {
        switch bluetooth {
        case .allowedAlways: "Tandem may use Bluetooth to ask your phone."
        case .notDetermined: "Tandem has not asked yet."
        default: "Bluetooth is off for Tandem in System Settings."
        }
    }
}

/// One line of a checklist: a tick when done, what is missing when not, and a button to fix it.
private struct Requirement: View {
    let done: Bool
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let buttonTitle: LocalizedStringKey?
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle.dashed")
                .font(.title3)
                .foregroundStyle(done ? Color.green : Color.secondary)
                .contentTransition(.symbolEffect(.replace))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if let buttonTitle {
                Button(buttonTitle, action: action)
            }
        }
        .animation(.tandem, value: done)
    }
}

// MARK: Updates

private struct UpdateSettings: View {
    @LocalState private var updater = Updater.shared

    var body: some View {
        Form {
            Section {
                LabeledContent("Installed version", value: updater.currentVersion)
                Toggle("Check for updates automatically", isOn: Binding(
                    get: { updater.autoCheck },
                    set: { updater.autoCheck = $0 }
                ))
            }
            Section {
                switch updater.state {
                case .idle, .upToDate:
                    LabeledContent {
                        Button("Check now") { Task { await updater.check() } }
                    } label: {
                        Text(updater.state == .upToDate ? "You are up to date" : "").foregroundStyle(.secondary)
                    }
                case .checking:
                    HStack(spacing: 10) { PillSpinner(size: 18); Text("Checking") }
                case let .available(release):
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Version \(release.version) is available").font(.headline)
                        if !release.notes.isEmpty {
                            ScrollView { Text(release.notes).font(.callout).frame(maxWidth: .infinity, alignment: .leading) }
                                .frame(maxHeight: 90)
                        }
                        Button("Update and restart") { Task { await updater.install() } }
                            .buttonStyle(.glassProminent)
                            .tint(Palette.indigo)
                    }
                case let .downloading(_, progress):
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Downloading")
                        ProgressCapsule(fraction: progress).frame(height: 6)
                    }
                case .installing:
                    HStack(spacing: 10) { PillSpinner(size: 18); Text("Installing") }
                case let .failed(reason):
                    LabeledContent {
                        Button("Try again") { Task { await updater.check() } }
                    } label: {
                        Text(reason).foregroundStyle(Palette.urgent)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: updater.state)
    }
}

// MARK: About

private struct AboutSettings: View {
    private let entries = Changelog.load()

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                PillMark(size: 104, style: .plate)
                    .padding(.top, 8)
                    .padding(.bottom, 14)
                Text(AppIdentity.displayName)
                    .font(.largeTitle.weight(.bold))
                Text("Version \(Bundle.main.appVersion)")
                    .foregroundStyle(.secondary)
                Text("Your phone and computers as one. Open source under AGPL-3.0.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
                    .padding(.top, 10)
                Link("github.com/Marukiee/Tandem", destination: URL(string: "https://github.com/Marukiee/Tandem")!)
                    .padding(.top, 10)

                if !entries.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("What's new").font(.title3.weight(.semibold))
                        ForEach(entries) { ChangeCard(entry: $0, installed: $0.version == Bundle.main.appVersion) }
                    }
                    .frame(maxWidth: 520, alignment: .leading)
                    .padding(.top, 28)
                }
            }
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .padding(24)
        }
    }
}

private struct ChangeCard: View {
    let entry: ChangeEntry
    let installed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(entry.version).font(.callout.weight(.semibold)).foregroundStyle(Palette.indigo)
                Text(entry.date).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if installed {
                    Text("Installed")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Palette.indigo.opacity(0.14), in: .capsule)
                }
            }
            Text(entry.title).font(.headline)
            group("New", entry.new)
            group("Better", entry.better)
            group("Fixed", entry.fixed)
        }
        .multilineTextAlignment(.leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 18))
    }

    @ViewBuilder
    private func group(_ title: LocalizedStringKey, _ items: [String]) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ForEach(items, id: \.self) { item in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("•").foregroundStyle(Palette.indigo)
                        Text(item).font(.callout)
                    }
                }
            }
        }
    }
}

/// The app's language. It follows the system unless a person picks one here; the
/// choice is stored the way macOS itself stores per-app languages, so it applies
/// to everything, including the Settings window and notifications.
enum LanguageSetting {
    static var current: String {
        (UserDefaults.standard.array(forKey: "AppleLanguages") as? [String])?.first.flatMap { code in
            ["en", "nl"].first { code.hasPrefix($0) }
        } ?? "system"
    }

    static func apply(_ choice: String) {
        if choice == "system" {
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        } else {
            UserDefaults.standard.set([choice], forKey: "AppleLanguages")
        }
    }
}
