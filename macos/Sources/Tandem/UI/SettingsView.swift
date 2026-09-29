import AppKit
import ServiceManagement
import SwiftUI
import TandemCore

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings()
                .tabItem { Label("General", systemImage: "gearshape") }
            AccessSettings()
                .tabItem { Label("Access", systemImage: "hand.raised") }
            HotspotSettings()
                .tabItem { Label("Hotspot", systemImage: "personalhotspot") }
            UpdateSettings()
                .tabItem { Label("Updates", systemImage: "arrow.down.circle") }
            AboutSettings()
                .tabItem { Label("About", systemImage: "info.circle") }
        }
        .frame(width: 560, height: 520)
    }
}

private struct GeneralSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var name = ""
    @LocalState private var startAtLogin = SMAppService.mainApp.status == .enabled
    @AppStorage("showInDock") private var showInDock = true
    @AppStorage("copyCodes") private var copyCodes = true
    @LocalState private var folder = DownloadFolder.url
    @LocalState private var confirmReset = false
    @LocalState private var language = LanguageSetting.current
    @LocalState private var needsRestart = false

    var body: some View {
        Form {
            Section {
                TextField("Name of this Mac", text: $name)
                    .onSubmit { model.rename(to: name) }
                Text("This is how your other devices see it.").font(.caption).foregroundStyle(.secondary)
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
                    HStack {
                        Text("Restart Tandem to change the language.").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Restart now") { model.relaunch() }.buttonStyle(.glassProminent).controlSize(.small)
                    }
                    .transition(.opacity)
                }
            }
            Section {
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
                Toggle("Copy codes from text messages to the clipboard", isOn: $copyCodes)
            }
            Section("Received files") {
                HStack {
                    Text(folder.path).lineLimit(1).truncationMode(.middle).foregroundStyle(.secondary)
                    Spacer()
                    Button("Choose…") {
                        let panel = NSOpenPanel()
                        panel.canChooseDirectories = true
                        panel.canChooseFiles = false
                        panel.canCreateDirectories = true
                        if panel.runModal() == .OK, let url = panel.url {
                            DownloadFolder.url = url
                            folder = url
                        }
                    }
                }
            }
            Section {
                Button("Reset Tandem…", role: .destructive) { confirmReset = true }
                Text("Forgets this Mac's identity and all pairings. You can pair again afterwards.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .onAppear { name = model.myName }
        .confirmationDialog("Reset Tandem?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Reset and restart", role: .destructive) { model.resetEverything() }
            Button("Cancel", role: .cancel) {}
        }
    }
}

private struct HotspotSettings: View {
    @AppStorage(HotspotCoordinator.enabledKey) private var auto = false
    @AppStorage(HotspotCoordinator.delayKey) private var delay = 8.0
    @AppStorage("hotspotSSID") private var ssid = ""
    @LocalState private var password = HotspotCredentials.password() ?? ""

    var body: some View {
        Form {
            Section {
                Toggle("Use my phone's hotspot when this Mac has no connection", isOn: $auto)
                HStack {
                    Text("Wait before asking")
                    Slider(value: $delay, in: 3 ... 30, step: 1)
                    Text("\(Int(delay)) s").monospacedDigit().frame(width: 40, alignment: .trailing)
                }
                .disabled(!auto)
            }
            Section("Your phone's hotspot") {
                TextField("Network name", text: $ssid)
                SecureField("Password", text: $password)
                    .onChange(of: password) { _, value in HotspotCredentials.save(password: value) }
                Text("Tandem needs these once to join the hotspot. They are stored privately on this Mac and never leave it.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

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
                    HStack {
                        Text(updater.state == .upToDate ? "You are up to date" : "").foregroundStyle(.secondary)
                        Spacer()
                        Button("Check now") { Task { await updater.check() } }
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
                    }
                case let .downloading(_, progress):
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Downloading")
                        ProgressCapsule(fraction: progress).frame(height: 6)
                    }
                case .installing:
                    HStack(spacing: 10) { PillSpinner(size: 18); Text("Installing") }
                case let .failed(reason):
                    HStack {
                        Text(reason).foregroundStyle(Palette.urgent)
                        Spacer()
                        Button("Try again") { Task { await updater.check() } }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: updater.state)
    }
}

private struct AboutSettings: View {
    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Capsule().fill(Palette.indigo.gradient).frame(width: 26, height: 64).rotationEffect(.degrees(30))
                Capsule().fill(Palette.rose.gradient).frame(width: 26, height: 64).rotationEffect(.degrees(30)).offset(x: -30)
            }
            .frame(height: 90)
            Text("Tandem").font(.title.weight(.bold))
            Text("Version \(Bundle.main.appVersion)").foregroundStyle(.secondary)
            Text("Your phone and computers as one. Open source under AGPL-3.0.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Link("github.com/Marukiee/Tandem", destination: URL(string: "https://github.com/Marukiee/Tandem")!)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
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
