import AppKit
import Observation

/// What a click on a notification from a phone does. The app on this Mac opens when there is one (WhatsApp, Telegram,
/// Slack and so on). Otherwise the phone is asked to open it, but only after the person said so, and they can say it
/// once for good.
@MainActor @Observable
final class NotificationClicks {
    static let shared = NotificationClicks()

    enum Choice: String {
        case phone
        case nothing
    }

    private static let defaultsKey = "notificationClickChoices"
    @ObservationIgnored private var asking = false
    /// Counts the changes to the choices, so a view that shows whether there are any follows them.
    private var version = 0

    /// Android packages and the Mac apps that are the same service.
    private static let known: [String: [String]] = [
        "com.whatsapp": ["net.whatsapp.WhatsApp", "desktop.WhatsApp"],
        "com.whatsapp.w4b": ["net.whatsapp.WhatsApp", "desktop.WhatsApp"],
        "org.telegram.messenger": ["ru.keepcoder.Telegram", "org.telegram.desktop"],
        "org.telegram.messenger.web": ["ru.keepcoder.Telegram", "org.telegram.desktop"],
        "org.thoughtcrime.securesms": ["org.whispersystems.signal-desktop"],
        "com.discord": ["com.hnc.Discord"],
        "com.Slack": ["com.tinyspeck.slackmacgap"],
        "com.spotify.music": ["com.spotify.client"],
        "com.microsoft.teams": ["com.microsoft.teams2", "com.microsoft.teams"],
        "us.zoom.videomeetings": ["us.zoom.xos"],
        "com.microsoft.office.outlook": ["com.microsoft.Outlook"],
        "com.skype.raider": ["com.skype.skype"],
        "com.viber.voip": ["com.viber.osx"],
        "jp.naver.line.android": ["jp.naver.line.mac"],
        "com.tencent.mm": ["com.tencent.xinWeChat"],
        "com.todoist": ["com.todoist.mac.Todoist"],
        "com.notion.id": ["notion.id"],
        "com.evernote": ["com.evernote.Evernote"],
        "com.dropbox.android": ["com.getdropbox.dropbox"],
        "com.android.chrome": ["com.google.Chrome"],
        "org.mozilla.firefox": ["org.mozilla.firefox"],
        "com.brave.browser": ["com.brave.Browser"],
        "com.microsoft.emmx": ["com.microsoft.edgemac"],
    ]

    // MARK: Remembered choices

    /// What was chosen for each app, by Android package.
    @ObservationIgnored private(set) var choices: [String: Choice] {
        get {
            let stored = UserDefaults.standard.dictionary(forKey: Self.defaultsKey) as? [String: String] ?? [:]
            return stored.compactMapValues(Choice.init(rawValue:))
        }
        set {
            UserDefaults.standard.set(newValue.mapValues(\.rawValue), forKey: Self.defaultsKey)
            version += 1
        }
    }

    var hasChoices: Bool {
        _ = version
        return !choices.isEmpty
    }

    func forgetChoices() {
        UserDefaults.standard.removeObject(forKey: Self.defaultsKey)
        version += 1
    }

    // MARK: A click

    /// Opens the app here, or asks, or does what was chosen before. `openOnPhone` is what asks the phone.
    func click(appId: String, appName: String, openOnPhone: @escaping () -> Void) {
        if let url = macApp(for: appId, name: appName) {
            NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
            return
        }
        switch choices[appId] {
        case .phone: openOnPhone()
        case .nothing: break
        case nil: ask(appId: appId, appName: appName, openOnPhone: openOnPhone)
        }
    }

    private func ask(appId: String, appName: String, openOnPhone: () -> Void) {
        guard !asking else { return }
        asking = true
        defer { asking = false }
        let alert = NSAlert()
        alert.messageText = String(localized: "Open \(appName) on your phone?")
        alert.informativeText = String(localized: "\(appName) is not on this Mac. Your phone can open it for you.")
        if let icon = PhoneAppIcons.shared.image(for: appId) { alert.icon = icon }
        alert.addButton(withTitle: String(localized: "Open on phone"))
        alert.addButton(withTitle: String(localized: "Not now"))
        alert.showsSuppressionButton = true
        alert.suppressionButton?.title = String(localized: "Remember my choice for \(appName)")
        NSApp.activate(ignoringOtherApps: true)
        let answer = alert.runModal()
        let phone = answer == .alertFirstButtonReturn
        if alert.suppressionButton?.state == .on {
            var next = choices
            next[appId] = phone ? .phone : .nothing
            choices = next
        }
        if phone { openOnPhone() }
    }

    // MARK: Apps on this Mac

    /// The app on this Mac that is the same as the one on the phone: one that is known, or one in the Applications
    /// folders with the same name. The apps that come with macOS are left out, since a phone's Clock is not this Mac's.
    func macApp(for appId: String, name: String) -> URL? {
        let workspace = NSWorkspace.shared
        for bundle in Self.known[appId] ?? [] {
            if let url = workspace.urlForApplication(withBundleIdentifier: bundle) { return url }
        }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, !clean.contains("/") else { return nil }
        let folders = [URL(fileURLWithPath: "/Applications"), FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications")]
        for folder in folders {
            let url = folder.appendingPathComponent(clean + ".app")
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return nil
    }
}
