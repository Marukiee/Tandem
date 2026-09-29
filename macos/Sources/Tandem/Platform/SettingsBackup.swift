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
    enum Kind { case bool, string, number }

    /// Every preference the app reads, with the type it must have. Import accepts
    /// nothing else, so a file cannot write arbitrary defaults.
    static let schema: [(key: String, kind: Kind)] = [
        ("deviceName", .string),
        ("showInDock", .bool),
        ("copyCodes", .bool),
        ("downloadFolderPath", .string),
        ("autoHotspot", .bool),
        ("hotspotDelay", .number),
        ("hotspotSSID", .string),
        ("autoCheckUpdates", .bool),
    ]

    private static let format = 1

    static func exportData(from defaults: UserDefaults = .standard) throws -> Data {
        var settings: [String: Any] = [:]
        for (key, kind) in schema {
            guard let value = defaults.object(forKey: key), valid(value, kind) else { continue }
            settings[key] = value
        }
        let document: [String: Any] = ["app": "Tandem", "format": format, "settings": settings]
        return try JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys])
    }

    /// Applies a backup and returns how many preferences it set.
    @discardableResult
    static func apply(_ data: Data, to defaults: UserDefaults = .standard) throws -> Int {
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              document["app"] as? String == "Tandem",
              let settings = document["settings"] as? [String: Any]
        else { throw BackupError.notABackup }

        var applied = 0
        for (key, kind) in schema {
            guard let value = settings[key], valid(value, kind) else { continue }
            // A folder that does not exist on this Mac would make every download fail.
            if key == "downloadFolderPath", let path = value as? String, !FileManager.default.fileExists(atPath: path) { continue }
            if key == "deviceName", (value as? String)?.trimmingCharacters(in: .whitespaces).isEmpty == true { continue }
            defaults.set(value, forKey: key)
            applied += 1
        }
        return applied
    }

    private static func valid(_ value: Any, _ kind: Kind) -> Bool {
        switch kind {
        case .bool: value is Bool
        case .string: value is String
        case .number: value is NSNumber && !(value is Bool)
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
    }
}
