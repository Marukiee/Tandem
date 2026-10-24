import AppKit
import Foundation
import UniformTypeIdentifiers

/// Export and import of the app's preferences as a JSON file.
///
/// Only preferences travel. The identity key, the pairings and the hotspot password
/// live in files inside the data folder and are never part of a backup, so a backup
/// can be mailed or synced without giving anything away. The language stays out too,
/// because it belongs to the system's own per-app language setting.
enum SettingsBackup {
    /// What is not a choice of the person but something the app keeps for itself (which update was dismissed, the last kind asked for,
    /// what was read), and the keys of the system and SwiftUI. Everything else the app stores is a setting and goes along, also settings
    /// added later: a fixed list of keys went out of date with every new setting.
    private static let kept: Set<String> = [
        "updateDismissedVersion", "updateLastChecked", "updateNotifiedVersion", "insertLastDevice", "insertLastKind", "usualFoldersOffered",
    ]
    private static let keptPrefixes = ["NS", "Apple", "com.apple", "com_apple", "messagesSeen.", "WebKit"]

    private static func isSetting(_ key: String) -> Bool {
        !kept.contains(key) && !keptPrefixes.contains { key.hasPrefix($0) }
    }

    private static var domain: String { Bundle.main.bundleIdentifier ?? "nl.markmaaktmedia.Tandem" }

    private static let format = 2

    static func exportData(from defaults: UserDefaults = .standard) throws -> Data {
        var settings: [String: Any] = [:]
        for (key, value) in defaults.persistentDomain(forName: domain) ?? [:] where isSetting(key) {
            // Only what a JSON file can hold: dates and raw data are no settings here.
            if JSONSerialization.isValidJSONObject([key: value]) { settings[key] = value }
        }
        let document: [String: Any] = ["app": "Tandem", "format": format, "settings": settings]
        return try JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys])
    }

    /// Applies a backup and returns how many preferences it set. A key of the system or one the app keeps for itself is not written,
    /// whatever the file says, and a value only replaces one of the same kind.
    @discardableResult
    static func apply(_ data: Data, to defaults: UserDefaults = .standard) throws -> Int {
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              document["app"] as? String == "Tandem",
              let settings = document["settings"] as? [String: Any]
        else { throw BackupError.notABackup }

        var applied = 0
        for (key, value) in settings where isSetting(key) {
            if let current = defaults.object(forKey: key), !sameKind(current, value) { continue }
            // A folder that does not exist on this Mac would make every download fail.
            if key == "downloadFolderPath", let path = value as? String, !FileManager.default.fileExists(atPath: path) { continue }
            if key == "deviceName", (value as? String)?.trimmingCharacters(in: .whitespaces).isEmpty == true { continue }
            defaults.set(value, forKey: key)
            applied += 1
        }
        return applied
    }

    private static func sameKind(_ a: Any, _ b: Any) -> Bool {
        switch (a, b) {
        case (is String, is String), (is [Any], is [Any]), (is [String: Any], is [String: Any]): true
        case let (x as NSNumber, y as NSNumber): (x === kCFBooleanTrue || x === kCFBooleanFalse) == (y === kCFBooleanTrue || y === kCFBooleanFalse)
        default: false
        }
    }

    enum BackupError: LocalizedError {
        case notABackup

        var errorDescription: String? {
            String(localized: "That file is not a Tandem settings backup.")
        }
    }

    // MARK: Panels

    @MainActor
    static func export(model: EngineModel) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "Tandem settings.json"
        panel.canCreateDirectories = true
        panel.message = String(localized: "Your settings are saved without your identity, pairings or hotspot password.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try exportData().write(to: url, options: .atomic)
            model.showToast(String(localized: "Settings exported"))
        } catch {
            model.showToast(error.localizedDescription)
        }
    }

    @MainActor
    static func importFile(model: EngineModel) {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let count = try apply(Data(contentsOf: url))
            applySideEffects(model: model)
            model.showToast(String(localized: "Settings imported (\(count))"))
        } catch {
            model.showToast(error.localizedDescription)
        }
    }

    /// Settings views read the defaults live, but a few preferences also act on
    /// something that is already running.
    @MainActor
    private static func applySideEffects(model: EngineModel) {
        let showInDock = UserDefaults.standard.object(forKey: "showInDock") as? Bool ?? true
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        if let name = EngineModel.savedName { model.rename(to: name) }
        ClipboardHistory.shared.reloadSettings()
    }
}
