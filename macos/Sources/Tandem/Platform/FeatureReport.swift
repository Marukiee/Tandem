import AVFoundation
import AppKit
import CoreBluetooth
import TandemCore
import UserNotifications

/// What this Mac does for the other devices and whether it is ready for it (see `Feature` in the core). The others grey out what will
/// not work, say why, and can ask this Mac to open the page of System Settings where it is turned on (`open`).
@MainActor
enum FeatureReport {
    private static var running = false
    /// What the music apps last answered about being controlled. An app that is not open cannot be asked, so its last answer stays.
    private static var automation: [String: OSStatus] = [:]
    private static var bluetooth: CBCentralManager?

    /// Looks again every few seconds while Tandem runs: permissions are changed in System Settings, outside the app. The core sends it
    /// on only when something changed.
    static func start() {
        guard !running else { return }
        running = true
        Task { @MainActor in
            while true {
                if let engine = EngineModel.shared.tandem {
                    let features = await current()
                    await engine.setFeatures(features: features)
                    SetupTour.remindIfDue(features)
                    SetupTour.showIfNew()
                }
                try? await Task.sleep(for: .seconds(4))
            }
        }
    }

    static func current() async -> [TandemFeature] {
        let accessibility = AXIsProcessTrusted()
        let screenAllowed = CGPreflightScreenCaptureAccess()
        let notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        let microphone = AVCaptureDevice.authorizationStatus(for: .audio)
        await refreshAutomation()

        var list: [TandemFeature] = []
        func add(_ id: String, _ ready: Bool, _ name: String, _ why: String, _ page: String?) {
            list.append(TandemFeature(id: id, ready: ready, name: name, why: ready ? "" : why, page: ready ? nil : page))
        }
        add("network", LocalNetwork.allowed != false, String(localized: "Find your devices on the network"),
            String(localized: "Local Network is off for Tandem on this Mac. Turn it off and on again."), "localnetwork")
        add("control", accessibility, String(localized: "Control this Mac"),
            String(localized: "Accessibility is off for Tandem on this Mac"), "accessibility")
        if !screenAllowed {
            add("screen", false, String(localized: "See this Mac's screen"),
                String(localized: "Screen Recording is off for Tandem on this Mac"), "screen")
        } else if ScreenHost.shared.needsRestart {
            add("screen", false, String(localized: "See this Mac's screen"),
                String(localized: "Tandem has to restart on this Mac to use Screen Recording"), "restart")
        } else {
            add("screen", true, String(localized: "See this Mac's screen"), "", nil)
        }
        add("pointer", accessibility, String(localized: "Universal mouse"),
            String(localized: "Accessibility is off for Tandem on this Mac"), "accessibility")
        let blocked = MacMedia.sources.first { automation[$0.bundleID].map { [-1743, -1744].contains($0) } ?? false }
        let mediaWhy: String = if let blocked {
            automation[blocked.bundleID] == -1744
                ? String(localized: "Tandem has not been allowed to control \(blocked.name) yet")
                : String(localized: "Tandem is not allowed to control \(blocked.name)")
        } else { "" }
        add("media.control", blocked == nil, String(localized: "Media buttons for Spotify and Music"), mediaWhy, "automation")
        add("notifications", [.authorized, .provisional].contains(notifications), String(localized: "Notifications on this Mac"),
            notifications == .notDetermined ? String(localized: "Notifications have not been allowed yet") : String(localized: "Notifications are off for Tandem on this Mac"),
            "notifications")
        // Codes from the texts of a phone, copied here: the switch, and notifications to say so (the window may not be open).
        let copyCodes = UserDefaults.standard.object(forKey: "copyCodes") as? Bool ?? true
        if !copyCodes {
            add("codes", false, String(localized: "Codes from texts copied here"), String(localized: "Copy codes is off in Tandem on this Mac"), switchPage + "codes")
        } else {
            add("codes", [.authorized, .provisional].contains(notifications), String(localized: "Codes from texts copied here"),
                String(localized: "Notifications are off for Tandem on this Mac, so a copied code is not shown"), "notifications")
        }
        add("microphone", microphone == .authorized, String(localized: "Find the side by sound"),
            microphone == .notDetermined ? String(localized: "The microphone has not been allowed yet") : String(localized: "The microphone is off for Tandem on this Mac"),
            "microphone")
        let bluetoothAllowed = CBCentralManager.authorization == .allowedAlways
        add("bluetooth", bluetoothAllowed, String(localized: "Bluetooth link"),
            String(localized: "Bluetooth is off for Tandem on this Mac"), "bluetooth")
        add("ring", FindSounds.playable, String(localized: "Play a sound to be found"),
            String(localized: "This Mac has no sound output right now"), "sound")
        // Only listed when it works: Location is a choice, and a missing one would stand on the other devices as a lasting notice. Share
        // Wi-Fi says what it needs when it is pressed.
        if WifiShare.shared.locationAllowed { add("wifi.share", true, String(localized: "Share this Mac's Wi-Fi"), "", nil) }
        // Focus: the switch, and the shortcut that sets and reads it (macOS lets no app do either by itself).
        let focus = FocusShare.shared
        add("focus", focus.share && focus.installed, String(localized: "Focus on all your devices"),
            focus.share ? String(localized: "The Tandem Focus shortcut has not been added on this Mac") : String(localized: "Share Focus is off in Tandem on this Mac"),
            focus.share ? "focus" : switchPage + "focus")
        return list
    }

    /// Asks the music apps that are open whether Tandem may press their buttons, without a question to the person.
    private static func refreshAutomation() async {
        for source in MacMedia.sources where !NSRunningApplication.runningApplications(withBundleIdentifier: source.bundleID).isEmpty {
            let status = await Task.detached { permission(for: source.bundleID, ask: false) }.value
            // -600: it quit in between.
            if status != -600 { automation[source.bundleID] = status }
        }
    }

    /// 0 allowed, -1743 not allowed, -1744 not asked yet, -600 not open.
    nonisolated private static func permission(for bundleID: String, ask: Bool) -> OSStatus {
        var target = AEAddressDesc()
        let id = Array(bundleID.utf8)
        let made = id.withUnsafeBytes { AECreateDesc(typeApplicationBundleID, $0.baseAddress, $0.count, &target) }
        guard made == noErr else { return OSStatus(made) }
        defer { AEDisposeDesc(&target) }
        return AEDeterminePermissionToAutomateTarget(&target, typeWildCard, typeWildCard, ask)
    }

    /// Opens what turns `page` on: the question of macOS when it was never asked, otherwise its page in System Settings.
    /// A page that is a switch of Tandem itself and not a setting of macOS: another device turns it on from afar instead of opening a page
    /// here. A permission of macOS can only be given here, by the person.
    static let switchPage = "switch:"

    static func open(_ page: String) {
        switch page {
        case "accessibility":
            ScreenHost.shared.requestAccessibility()
            settings("com.apple.preference.security?Privacy_Accessibility")
        case "screen":
            ScreenHost.shared.requestScreenRecording()
            settings("com.apple.preference.security?Privacy_ScreenCapture")
        case "restart":
            ScreenHost.shared.relaunch()
        case "localnetwork":
            LocalNetwork.openSettings()
        case "automation":
            let unasked = MacMedia.sources.filter { automation[$0.bundleID] == -1744 }
            if unasked.isEmpty {
                settings("com.apple.preference.security?Privacy_Automation")
            } else {
                Task.detached {
                    for source in unasked { _ = permission(for: source.bundleID, ask: true) }
                }
            }
        case "notifications":
            Task {
                if await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .notDetermined {
                    _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                } else {
                    settings("com.apple.Notifications-Settings.extension?id=\(Bundle.main.bundleIdentifier ?? "")")
                }
            }
        case "microphone":
            if AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
                AVCaptureDevice.requestAccess(for: .audio) { _ in }
            } else {
                settings("com.apple.preference.security?Privacy_Microphone")
            }
        case "bluetooth":
            if CBCentralManager.authorization == .notDetermined {
                bluetooth = CBCentralManager(delegate: nil, queue: nil)
            } else {
                settings("com.apple.preference.security?Privacy_Bluetooth")
            }
        case "sound":
            settings("com.apple.Sound-Settings.extension")
        case "focus":
            FocusShare.shared.addOrSignIn()
        case switchPage + "focus":
            UserDefaults.standard.set(true, forKey: FocusShare.shareKey)
        case switchPage + "codes":
            UserDefaults.standard.set(true, forKey: "copyCodes")
        default:
            settings("com.apple.preference.security")
        }
    }

    /// After an update with another signature (the move to the Developer ID) System Settings still shows Tandem switched on, but that
    /// switch belongs to the old signature and this app is not let in. Only removing it helps: the question that follows then adds a
    /// switch for this app. Only done when the permission is missing, so a working one is never touched.
    static func forgetStale(_ service: String) {
        guard let id = Bundle.main.bundleIdentifier else { return }
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/tccutil")
        task.arguments = ["reset", service, id]
        task.standardOutput = FileHandle.nullDevice
        task.standardError = FileHandle.nullDevice
        try? task.run()
        task.waitUntilExit()
    }

    private static func settings(_ pane: String) {
        if let url = URL(string: "x-apple.systempreferences:\(pane)") { NSWorkspace.shared.open(url) }
    }
}
