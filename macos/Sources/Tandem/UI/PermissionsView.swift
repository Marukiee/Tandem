import AVFoundation
import AppKit
import CoreBluetooth
import ServiceManagement
import SwiftUI
import TandemCore
import UserNotifications

/// One row on the Access page: what Tandem asks for, why, whether it has it, and a
/// way to try it without needing a second device.
struct AccessItem: Identifiable {
    enum Status { case allowed, notAsked, denied, unknown }

    let id: String
    let symbol: String
    let title: LocalizedStringKey
    let why: LocalizedStringKey
    var status: Status
    var detail: String?
}

@MainActor
@Observable
final class AccessChecker {
    var items: [AccessItem] = []
    var testResult: String?
    @ObservationIgnored private var bluetooth: CBCentralManager?

    func refresh() async {
        let notifications = await notificationStatus()
        items = [
            AccessItem(
                id: "notifications", symbol: "bell.badge",
                title: "Notifications",
                why: "To show notifications from your phone, incoming calls and files that arrived.",
                status: notifications
            ),
            AccessItem(
                id: "network", symbol: "wifi",
                title: "Local network",
                why: "To find your other devices on your Wi-Fi. macOS asks the first time Tandem looks for them.",
                status: .unknown
            ),
            AccessItem(
                id: "accessibility", symbol: "cursorarrow.motionlines",
                title: "Accessibility",
                why: "Only for using your phone as a trackpad, keyboard or media remote, and for controlling this Mac from another device.",
                status: AXIsProcessTrusted() ? .allowed : .notAsked
            ),
            AccessItem(
                id: "screen", symbol: "rectangle.dashed.badge.record",
                title: "Screen Recording",
                why: "Only to let another device of yours see this Mac's screen, when you allow it. Turn it on, then restart Tandem.",
                status: CGPreflightScreenCaptureAccess() ? .allowed : .notAsked,
                detail: ScreenHost.shared.needsRestart ? String(localized: "Restart Tandem so your other devices can see that this Mac is ready.") : nil
            ),
            AccessItem(
                id: "bluetooth", symbol: "dot.radiowaves.left.and.right",
                title: "Bluetooth",
                why: "Only to ask your phone for its hotspot when this Mac has no connection at all.",
                status: bluetoothStatus()
            ),
            AccessItem(
                id: "camera", symbol: "camera",
                title: "Camera",
                why: "Only to scan a pairing code from another device.",
                status: cameraStatus()
            ),
            AccessItem(
                id: "login", symbol: "power",
                title: "Start at login",
                why: "So your devices find this Mac after a restart. Optional.",
                status: SMAppService.mainApp.status == .enabled ? .allowed : .notAsked
            ),
        ]
    }

    private func notificationStatus() async -> AccessItem.Status {
        guard Bundle.main.bundleIdentifier != nil else { return .unknown }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return .allowed
        case .denied: return .denied
        case .notDetermined: return .notAsked
        @unknown default: return .unknown
        }
    }

    private func bluetoothStatus() -> AccessItem.Status {
        switch CBCentralManager.authorization {
        case .allowedAlways: .allowed
        case .denied, .restricted: .denied
        case .notDetermined: .notAsked
        @unknown default: .unknown
        }
    }

    private func cameraStatus() -> AccessItem.Status {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .allowed
        case .denied, .restricted: .denied
        case .notDetermined: .notAsked
        @unknown default: .unknown
        }
    }

    func request(_ id: String) {
        switch id {
        case "notifications":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        case "accessibility":
            _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        case "screen":
            ScreenHost.shared.requestScreenRecording()
        case "bluetooth":
            bluetooth = CBCentralManager(delegate: nil, queue: nil)
        case "camera":
            AVCaptureDevice.requestAccess(for: .video) { _ in }
        case "login":
            try? SMAppService.mainApp.register()
        default:
            break
        }
    }

    func openSystemSettings(for id: String) {
        let panes: [String: String] = [
            "notifications": "com.apple.Notifications-Settings.extension",
            "network": "com.apple.preference.security?Privacy_LocalNetwork",
            "accessibility": "com.apple.preference.security?Privacy_Accessibility",
            "screen": "com.apple.preference.security?Privacy_ScreenCapture",
            "bluetooth": "com.apple.preference.security?Privacy_Bluetooth",
            "camera": "com.apple.preference.security?Privacy_Camera",
            "login": "com.apple.LoginItems-Settings.extension",
        ]
        let pane = panes[id] ?? "com.apple.preference.security"
        if let url = URL(string: "x-apple.systempreferences:\(pane)") { NSWorkspace.shared.open(url) }
    }

    /// Tries what can be tried on this Mac alone.
    func runTest(_ id: String, model: EngineModel) {
        switch id {
        case "notifications":
            Notifier.shared.post(id: "test", title: String(localized: "Test notification"), body: String(localized: "If you can read this, notifications work."))
            testResult = String(localized: "A test notification was sent. Look at the top right of your screen.")
        case "network":
            let online = model.devices.filter(\.online).count
            testResult = online > 0
                ? String(localized: "Working: \(online) device(s) reachable.")
                : String(localized: "No device is reachable right now. If macOS never asked for permission, turn it on in System Settings.")
        case "accessibility":
            testResult = AXIsProcessTrusted()
                ? String(localized: "Working: Tandem may control this Mac.")
                : String(localized: "Not allowed yet. Press Allow and turn Tandem on in the list.")
        default:
            testResult = nil
        }
    }
}

struct AccessSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var checker = AccessChecker()

    var body: some View {
        Form {
            Section {
                Text("Tandem only asks for what a feature needs, and each one is optional. Here you can see what is on, turn things on, and try them without a second device.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Section {
                ForEach(checker.items) { item in
                    AccessRow(item: item, checker: checker)
                }
            }
            if let result = checker.testResult {
                Section {
                    Label(result, systemImage: "checkmark.seal").font(.callout)
                }
                .transition(.opacity)
            }
            Section("Status") {
                LabeledContent("This Mac", value: model.myName)
                LabeledContent("Devices online", value: "\(model.onlineCount)")
                LabeledContent("Received files go to", value: DownloadFolder.url.path)
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: checker.testResult)
        .task {
            // Permissions change outside the app, so look again while this page is open.
            while !Task.isCancelled {
                await checker.refresh()
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
    }
}

private struct AccessRow: View {
    @Environment(EngineModel.self) private var model
    let item: AccessItem
    let checker: AccessChecker

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: item.symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Palette.indigo)
                .frame(width: 30, height: 30)
                .background(Palette.indigo.opacity(0.12), in: .circle)

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Text(item.title).font(.callout.weight(.semibold))
                    StatusPill(status: item.status)
                }
                Text(item.why).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if let detail = item.detail {
                    HStack(spacing: 8) {
                        Text(detail).font(.caption).foregroundStyle(Palette.indigo).fixedSize(horizontal: false, vertical: true)
                        Button("Restart") { ScreenHost.shared.relaunch() }.buttonStyle(.glassProminent).controlSize(.small)
                    }
                }
                HStack(spacing: 8) {
                    if item.status != .allowed, item.status != .unknown, item.status != .denied {
                        Button("Allow") { checker.request(item.id) }.buttonStyle(.glassProminent).controlSize(.small)
                    }
                    if item.status == .denied || item.status == .unknown {
                        Button("Open System Settings") { checker.openSystemSettings(for: item.id) }
                            .buttonStyle(.glass).controlSize(.small)
                    }
                    if ["notifications", "network", "accessibility"].contains(item.id) {
                        Button("Test") { checker.runTest(item.id, model: model) }.buttonStyle(.glass).controlSize(.small)
                    }
                }
                .padding(.top, 2)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

private struct StatusPill: View {
    let status: AccessItem.Status

    var body: some View {
        let (text, tint): (LocalizedStringKey, Color) = switch status {
        case .allowed: ("Allowed", .green)
        case .notAsked: ("Not asked yet", .orange)
        case .denied: ("Denied", Palette.urgent)
        case .unknown: ("Asked by macOS", .secondary)
        }
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: .capsule)
            .animation(.tandemFade, value: status == .allowed)
    }
}
