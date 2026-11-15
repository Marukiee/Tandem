import AppKit
import CoreBluetooth
import ServiceManagement
import SwiftUI
import TandemCore

/// The subjects of the Settings window, the same seven as on Linux and Windows. What belongs together is together, by subject and
/// not by the part of the app that made it: what this Mac does, what the phone brings here, what is shared, what is shown and
/// steered, and what is heard.
enum SettingsSubject: String, CaseIterable, Identifiable {
    case thisMac, phone, sharing, control, sound, works, about

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .thisMac: "This Mac"
        case .phone: "Phone"
        case .sharing: "Sharing"
        case .control: "Screen and control"
        case .sound: "Sound and music"
        case .works: "What works"
        case .about: "Updates and about"
        }
    }

    var symbol: String {
        switch self {
        case .thisMac: "laptopcomputer"
        case .phone: "iphone"
        case .sharing: "paperplane"
        case .control: "display"
        case .sound: "speaker.wave.2"
        case .works: "checklist"
        case .about: "info.circle"
        }
    }

    /// What the subject holds, from the top of its page down.
    var parts: [SettingsSection] {
        switch self {
        case .thisMac: [.general, .shortcuts, .devices]
        case .phone: [.phone, .hotspot, .health]
        case .sharing: [.received, .files, .quickShare, .clipboard]
        case .control: [.screen, .pointer]
        case .sound: [.sound]
        case .works: [.features, .access]
        case .about: [.updates, .about]
        }
    }
}

/// The parts of Settings. Each was a tab of its own once and is a part of a subject now; the names stay, because the search of the
/// Mac, a row on the page of a device and a debug run still ask for a part by its name.
enum SettingsSection: String, CaseIterable, Identifiable {
    case general, devices, features, shortcuts, files, clipboard, screen, pointer, quickShare, access, hotspot, health, updates, about
    /// What was in General and belongs to another subject.
    case phone, received, sound

    var id: String { rawValue }

    var subject: SettingsSubject {
        SettingsSubject.allCases.first { $0.parts.contains(self) } ?? .thisMac
    }

    /// The first part of a subject is the subject itself and has no heading; neither has a part whose own first line names it.
    var hasHeading: Bool {
        switch self {
        case .general, .phone, .received, .sound, .features, .updates, .about: false
        default: true
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .general: "General"
        case .devices: "Devices"
        case .features: "What works"
        case .shortcuts: "Shortcuts"
        case .files: "Files"
        case .clipboard: "Clipboard"
        case .screen: "Remote control"
        case .pointer: "Mouse and keyboard"
        case .quickShare: "Quick Share"
        case .access: "Access"
        case .hotspot: "Hotspot"
        case .health: "Health"
        case .updates: "Updates"
        case .about: "About"
        case .phone: "Phone"
        case .received: "Received files"
        case .sound: "Sound and music"
        }
    }

    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .devices: "laptopcomputer.and.iphone"
        case .features: "checklist"
        case .shortcuts: "command"
        case .files: "folder"
        case .clipboard: "doc.on.clipboard"
        case .screen: "display"
        case .pointer: "cursorarrow.motionlines"
        case .quickShare: "arrow.up.arrow.down.circle"
        case .access: "hand.raised"
        case .hotspot: "personalhotspot"
        case .health: "bed.double"
        case .updates: "arrow.down.circle"
        case .about: "info.circle"
        case .phone: "iphone"
        case .received: "tray.and.arrow.down"
        case .sound: "speaker.wave.2"
        }
    }
}

/// The Settings window like System Settings: a sidebar with the subjects and the subject next to it. Seven subjects instead of a
/// tab for every part of the app: a subject is one page with its parts under each other, each under its own name.
struct SettingsView: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var selection: SettingsSubject = DebugSupport.initialSettingsSubject() ?? .thisMac
    /// The part that was asked for by name, to bring into view on the page of its subject.
    @LocalState private var wanted: SettingsSection? = DebugSupport.initialSettingsSection()
    /// Raised each time a part is asked for, also the same one twice.
    @LocalState private var asked = 0

    var body: some View {
        NavigationSplitView {
            List(SettingsSubject.allCases, selection: $selection) { subject in
                Label(subject.title, systemImage: subject.symbol)
                    .tag(subject)
            }
            // Wide enough for the longest name in both languages, so none is cut off.
            .frame(minWidth: 214)
            .navigationSplitViewColumnWidth(214)
            // The subjects always fit, so there is nothing to fold away, and the round button for it only got in the way.
            .toolbar(removing: .sidebarToggle)
        } detail: {
            SubjectPage(subject: selection, wanted: wanted, asked: asked) { wanted = nil }
                .id(selection)
                .navigationTitle(Text(selection.title))
        }
        .frame(width: 780, height: DebugSupport.settingsHeight ?? 620)
        .onAppear(perform: take)
        .onReceive(NotificationCenter.default.publisher(for: .tandemSettingsSection)) { _ in take() }
    }

    /// Goes to the part that the search of the Mac, or a row that leads here, asked for.
    private func take() {
        guard let part = SettingsSection.requested else { return }
        SettingsSection.requested = nil
        selection = part.subject
        wanted = part
        asked += 1
        if DebugSupport.directory != nil { DebugSupport.write("settings section \(part.rawValue)\n", named: "spotlight-settings.txt") }
    }
}

/// One subject: its parts under each other on one page that scrolls as a whole. Each part is the grouped form it always was, as
/// tall as what is in it and without a scroll of its own.
private struct SubjectPage: View {
    let subject: SettingsSubject
    let wanted: SettingsSection?
    let asked: Int
    /// The part that was asked for is in view: it is not asked for any more, so coming back to this subject starts at its top.
    var arrived: () -> Void = {}

    @LocalState private var position = ScrollPosition(idType: SettingsSection.self)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // What the page holds, to see at a glance and to go to.
                if subject.parts.count > 1 {
                    FlowRow {
                        ForEach(subject.parts) { part in
                            PartChip(part: part) {
                                withAnimation(.tandem) {
                                    if part == subject.parts.first { position.scrollTo(edge: .top) } else { position.scrollTo(id: part, anchor: .top) }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 22)
                    .padding(.top, 14)
                }
                ForEach(subject.parts, id: \.self) { part in
                    VStack(alignment: .leading, spacing: 0) {
                        if part.hasHeading {
                            Label(part.title, systemImage: part.symbol)
                                .font(.title3.weight(.semibold))
                                .padding(.horizontal, 22)
                                .padding(.top, 16)
                                // Over the ground of the form under it, which reaches up a little.
                                .zIndex(1)
                        }
                        content(of: part)
                            // Asked for by name: what the part keeps folded away opens, so what was looked for is in sight.
                            .environment(\.settingsPartAsked, wanted == part)
                    }
                    .id(part)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .scrollTargetLayout()
        }
        .scrollPosition($position)
        .onChange(of: asked, initial: true) {
            guard let wanted, wanted.subject == subject else { return }
            // The first part is where the page starts anyway.
            guard wanted != subject.parts.first else { return arrived() }
            // Once the forms have their height.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                position.scrollTo(id: wanted, anchor: .top)
                arrived()
                if DebugSupport.directory != nil { DebugSupport.write("settings part \(wanted.rawValue)\n", named: "settings-part.txt") }
            }
        }
    }

    @ViewBuilder
    private func content(of part: SettingsSection) -> some View {
        switch part {
        case .general: GeneralSettings().asPart()
        case .devices: DevicesSettings().asPart()
        case .features: FeaturesSettings().asPart()
        case .shortcuts: ShortcutsSettings().asPart()
        case .files: FileSettings().asPart()
        case .clipboard: ClipboardSettings().asPart()
        case .screen: ScreenSettings().asPart()
        case .pointer: PointerSettings().asPart()
        case .quickShare: QuickShareSettings().asPart()
        case .access: AccessSettings().asPart()
        case .hotspot: HotspotSettings().asPart()
        case .health: HealthSettingsContent().padding(20)
        case .updates: UpdateSettings().asPart()
        case .about: AboutSettings()
        case .phone: PhoneSettings().asPart()
        case .received: ReceivedFilesSettings().asPart()
        case .sound: SoundSettings().asPart()
        }
    }
}

/// One of the parts of a page, in the row at its top: a press brings that part into view.
private struct PartChip: View {
    let part: SettingsSection
    let action: () -> Void
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) {
            Label(part.title, systemImage: part.symbol)
                .font(.callout.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Color.primary.opacity(hovering ? 0.1 : 0.06), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(.bouncy)
        .onHover { hovering = $0 }
        .animation(.hover(hovering), value: hovering)
    }
}

extension EnvironmentValues {
    /// This part of Settings was asked for by name, by the search of the Mac or a row that leads to it.
    @Entry var settingsPartAsked = false
}

/// The last row of a part of Settings, with behind it what is rarely touched: fine tuning, what holds for one device, how it
/// works. The row says what is there, and the rest is only laid out once it is opened, so the page stays short enough to scan.
/// It opens by itself when its part was asked for by name: what someone searched for may be behind it.
struct MoreSettings<Content: View>: View {
    /// What is behind the row, in a few words.
    let holds: LocalizedStringKey
    @ViewBuilder var content: Content
    @Environment(\.settingsPartAsked) private var asked
    @LocalState private var open = false

    var body: some View {
        Section {
            Button {
                withAnimation(.tandem) { open.toggle() }
            } label: {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("More")
                        Text(holds).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(open ? .isSelected : [])
            .onChange(of: asked, initial: true) { if asked { open = true } }
        }
        if open { content }
    }
}

extension View {
    /// A grouped form as a part of a longer page: as tall as what is in it, and the page does the scrolling.
    fileprivate func asPart() -> some View {
        scrollDisabled(true).fixedSize(horizontal: false, vertical: true)
    }
}

/// A toggle with a line of explanation under it, as grouped forms show them.
struct DescribedToggle: View {
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

// MARK: This Mac

/// The first part of This Mac: its name, its language, how it starts and stays awake, how it reaches the others, and its backup.
private struct GeneralSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var name = ""
    @FocusState private var nameFocused: Bool
    @LocalState private var startAtLogin = SMAppService.mainApp.status == .enabled
    @AppStorage("showInDock") private var showInDock = true
    @AppStorage("bleMessages") private var bleMessages = true
    @AppStorage(TailscaleAuto.enabledKey) private var autoTailscale = true
    @LocalState private var confirmReset = false
    @LocalState private var backupChoice: BackupChoice?
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
                            .buttonStyle(.borderedProminent).tint(Palette.indigo)
                    } label: {
                        Text("Restart Tandem to change the language.").foregroundStyle(.secondary)
                    }
                    .transition(.opacity)
                }
            }

            Section("Starting") {
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
            }

            KeepAwakeSettings()

            // The rest is set once, if at all.
            MoreSettings(holds: "Connection, Focus, Spotlight, backup, starting over") {
            Section("Connection") {
                DescribedToggle(
                    "Turn on Tailscale when needed",
                    subtitle: "Only when a device cannot be reached on this network.",
                    isOn: $autoTailscale
                )
                DescribedToggle(
                    "Bluetooth without a network",
                    subtitle: "Clipboard and notifications still arrive when this Mac is offline.",
                    isOn: $bleMessages
                )
            }

            FocusSettingsSection()

            SpotlightSettingsSection()

            Section {
                LabeledContent("Back up all devices") {
                    Button("Export…") { SettingsBackup.export(model: model) }
                }
                LabeledContent("Restore from a backup") {
                    Button("Import…") { backupChoice = SettingsBackup.open(model: model) }
                }
            } header: {
                Text("Backup")
            } footer: {
                Text("One file with the settings of this Mac and your devices. Identities, pairings and passwords are never in it.")
            }
            .sheet(item: $backupChoice) { choice in
                RestorePicker(choice: choice) { device in
                    SettingsBackup.restore(device, model: model)
                }
            }

            Section {
                LabeledContent("Forgets this Mac's identity and all pairings. You can pair again afterwards.") {
                    Button("Reset Tandem…", role: .destructive) { confirmReset = true }
                }
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
}

// MARK: Phone

/// The first part of Phone: what the phone brings to this Mac by itself.
private struct PhoneSettings: View {
    @AppStorage("copyCodes") private var copyCodes = true
    @AppStorage("batteryAlerts") private var batteryAlerts = true
    @AppStorage(ReceivedImages.copyKey) private var copyImages = true
    @AppStorage(ReceivedImages.pasteKey) private var pasteImages = false

    var body: some View {
        Form {
            Section {
                DescribedToggle(
                    "Copy codes from text messages to the clipboard",
                    subtitle: "Without this, the notification has a Copy code button.",
                    isOn: $copyCodes
                )
                DescribedToggle(
                    "Battery alerts",
                    subtitle: "When another device is almost empty or fully charged.",
                    isOn: $batteryAlerts
                )
                Toggle("Copy pictures from your phone to the clipboard", isOn: $copyImages)
                DescribedToggle(
                    "Paste them straight away",
                    subtitle: "Where the cursor is. Uses the Accessibility permission.",
                    isOn: $pasteImages
                )
                .disabled(!copyImages)
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: Sharing

/// The first part of Sharing: where files that arrive go.
private struct ReceivedFilesSettings: View {
    @LocalState private var folder = DownloadFolder.url
    @LocalState private var folderIsDefault = DownloadFolder.isDefault

    var body: some View {
        Form {
            Section("Received files") {
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
            }
        }
        .formStyle(.grouped)
        .onAppear(perform: refreshFolder)
        .animation(.tandem, value: folderIsDefault)
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

// MARK: Sound and music

/// Everything that is heard: the sound of other devices here, Tandem's own sound devices, the sound that finds this Mac, and music.
private struct SoundSettings: View {
    @Environment(EngineModel.self) private var model
    @AppStorage(CallPause.enabledKey) private var pauseDuringCalls = true
    @AppStorage("audioMuteLocal") private var audioMuteLocal = true

    /// What Tandem can read of the music apps right now, so it is clear when a phone shows nothing and why.
    private var mediaSeen: String {
        let players = model.macMedia.players
        if players.isEmpty { return String(localized: "Nothing from Spotify or Music yet") }
        return players
            .map { $0.playing ? String(localized: "\($0.app) (playing)") : String(localized: "\($0.app) (paused)") }
            .joined(separator: ", ")
    }

    var body: some View {
        Form {
            Section("Music") {
                DescribedToggle(
                    "Show and control music",
                    subtitle: "Your phone's music here, and this Mac's music on your phone. Spotify covers are fetched from Spotify.",
                    isOn: Binding(get: { model.mediaShare }, set: { model.mediaShare = $0 })
                )
                if model.mediaShare {
                    LabeledContent("Seen on this Mac") {
                        Text(mediaSeen).foregroundStyle(.secondary)
                    }
                    DescribedToggle(
                        "Media keys for your phone's music",
                        subtitle: "It also shows in Control Center while your phone plays.",
                        isOn: Binding(get: { model.systemNowPlaying }, set: { model.systemNowPlaying = $0 })
                    )
                }
                DescribedToggle(
                    "Pause music during calls",
                    subtitle: "Here and on your phone. It plays again after the call.",
                    isOn: $pauseDuringCalls
                )
            }

            PhoneSoundSettings()

            Section {
                DescribedToggle(
                    "Mute this Mac while another device is its speaker",
                    subtitle: "Off plays the sound on both, from the next time you start it.",
                    isOn: $audioMuteLocal
                )
            }

            TandemAudioSettings()

            FindSoundSettings()
        }
        .formStyle(.grouped)
        .animation(.tandem, value: model.mediaShare)
    }
}

// MARK: Devices

private struct DevicesSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var removing: TandemDevice?
    /// The devices whose settings are open. Closed, a device is one row: with a handful of devices the page was four rows for
    /// each of them.
    @LocalState private var opened: Set<String> = []

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
                    // Not a LabeledContent: that sets the state level with the first line of the name, and with two lines
                    // beside it the state belongs in the middle.
                    Button {
                        withAnimation(.tandem) { if opened.contains(device.id) { opened.remove(device.id) } else { opened.insert(device.id) } }
                    } label: {
                        HStack(spacing: 10) {
                            DeviceGlyph(platform: device.platform, online: device.online, size: 30, deviceID: device.id)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(device.name).font(.body.weight(.medium))
                                Text(device.platform.label).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 12)
                            Text(model.reach(of: device).text)
                                .foregroundStyle(model.reach(of: device).color)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.tertiary)
                                .rotationEffect(.degrees(opened.contains(device.id) ? 90 : 0))
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(opened.contains(device.id) ? .isSelected : [])
                    if opened.contains(device.id) {
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
                    if NotificationClicks.shared.hasChoices {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Clicks on notifications")
                                Text("You chose what happens for apps that are not on this Mac")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 12)
                            Button("Ask again") { withAnimation(.tandem) { NotificationClicks.shared.forgetChoices() } }
                        }
                    }
                    DescribedToggle(
                        "Accept files automatically",
                        subtitle: "Turn off to be asked before something arrives",
                        isOn: Binding(get: { device.autoAccept }, set: { model.setSettings(device, autoAccept: $0) })
                    )
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Remove this device")
                            Text(device.vouchedByRemoved ? "It was added by a device that has since been removed" : "It leaves the circle everywhere")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 12)
                        Button("Remove…", role: .destructive) { removing = device }
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
    /// The switch only shows on when everything it needs is in place, so it never looks on
    /// while nothing can happen.
    private var ready: Bool { credentialsSet && bluetoothAllowed }

    var body: some View {
        Form {
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Guided setup")
                        Text("Join your phone's hotspot once and Tandem takes over.").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 12)
                    Button("Set up…") { showSetup = true }
                }
            }
            Section {
                // Looks the same whether it is set up or not: turning it on before the setup is done opens the setup, which turns it
                // on at its end.
                Toggle("Use my phone's hotspot when this Mac has no connection", isOn: Binding(
                    get: { auto && ready },
                    set: { on in if on && !ready { showSetup = true } else { auto = on } }
                ))
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
            }

            Section {
                TextField("Network name", text: $ssid)
                SecureField("Password", text: $password)
                    .onChange(of: password) { _, value in HotspotCredentials.save(password: value) }
            } header: {
                Text("Your phone's hotspot")
            } footer: {
                Text("Kept on this Mac only.")
            }
        }
        .formStyle(.grouped)
        // The permission only changes in System Settings, so it is read again when the person comes back from there.
        // A timer that asked the system every second kept the app and the permission service awake for nothing.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            bluetooth = CBManager.authorization
        }
        .sheet(isPresented: $showSetup, onDismiss: {
            bluetooth = CBManager.authorization
            password = HotspotCredentials.password() ?? ""
        }) { HotspotSetupSheet() }
    }
}

// MARK: Updates

private struct UpdateSettings: View {
    @LocalState private var updater = Updater.shared
    private let entries = Changelog.load()

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
                            .buttonStyle(.borderedProminent).tint(Palette.indigo)
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
            // What is new in each version sits with the update, not under About.
            if !entries.isEmpty {
                Section("What's new") {
                    ForEach(entries) { ChangeCard(entry: $0, installed: $0.version == Bundle.main.appVersion) }
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: updater.state)
    }
}

// MARK: About

private struct AboutSettings: View {
    var body: some View {
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

            }
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .padding(24)
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
