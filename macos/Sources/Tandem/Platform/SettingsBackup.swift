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

    private static let format = 3

    /// This Mac's own part of a backup: what it is, so a reset Mac finds itself again in the file, and its preferences.
    static func entry(myId: String, from defaults: UserDefaults = .standard) -> [String: Any] {
        var settings: [String: Any] = [:]
        for (key, value) in defaults.persistentDomain(forName: domain) ?? [:] where isSetting(key) {
            // Only what a JSON file can hold: dates and raw data are no settings here.
            if JSONSerialization.isValidJSONObject([key: value]) { settings[key] = value }
        }
        var entry: [String: Any] = [
            "id": myId, "name": EngineModel.savedName ?? Host.current().localizedName ?? "Mac", "kind": BackupDevice.macKind,
            "platform": "mac", "settings": settings,
        ]
        if let model = EngineModel.modelIdentifier() { entry["model"] = model }
        return entry
    }

    static func entryJSON(myId: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: entry(myId: myId), options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// One file with this Mac and the devices that answered: each keeps its own preferences, in its own words.
    static func exportData(myId: String, others: [String]) throws -> Data {
        var devices: [Any] = [entry(myId: myId)]
        for part in others {
            if let data = part.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               object["settings"] is [String: Any] {
                devices.append(object)
            }
        }
        let document: [String: Any] = [
            "app": "Tandem", "format": format, "made": ISO8601DateFormatter().string(from: Date()), "devices": devices,
        ]
        return try JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys])
    }

    /// The devices in a backup. A file of this version holds several; an older one from a phone, a Mac or a computer holds one.
    static func devices(in data: Data) throws -> [BackupDevice] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BackupError.notABackup }
        if let list = root["devices"] as? [[String: Any]], (root["app"] as? String)?.lowercased() == "tandem" {
            let devices = list.enumerated().compactMap { BackupDevice(index: $0.offset, json: $0.element) }
            if devices.isEmpty { throw BackupError.notABackup }
            return devices
        }
        if root["app"] as? String == "Tandem", let settings = root["settings"] as? [String: Any] {
            let name = settings[EngineModel.nameKey] as? String
            return [BackupDevice(index: 0, deviceId: nil, name: name ?? "Mac", kind: BackupDevice.macKind, platform: "mac", model: nil, settings: settings)]
        }
        if root["app"] as? String == "tandem", let settings = root["settings"] as? [String: Any] {
            let name = settings["device_name"] as? String
            return [BackupDevice(index: 0, deviceId: nil, name: name ?? "Android", kind: "android", platform: "android", model: nil, settings: settings)]
        }
        // A file of the computer app is its settings themselves, without a wrapper.
        if root["app"] == nil, root["closeToTray"] != nil {
            return [BackupDevice(index: 0, deviceId: nil, name: String(localized: "Computer"), kind: "desktop", platform: "linux", model: nil, settings: root)]
        }
        throw BackupError.notABackup
    }

    /// Which device of a backup is this Mac: the same id when it kept its identity, else the same model, else the same name. Only
    /// one of them, so two Macs of the same model leave the choice to the person.
    static func recognise(_ devices: [BackupDevice], myId: String, model: String?, name: String) -> Int? {
        let fitting = devices.filter { $0.kind == BackupDevice.macKind }
        if let same = fitting.first(where: { $0.deviceId == myId }) { return same.index }
        if let model {
            let same = fitting.filter { $0.model == model }
            if same.count == 1 { return same[0].index }
        }
        let named = fitting.filter { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        if named.count == 1 { return named[0].index }
        return fitting.count == 1 ? fitting[0].index : nil
    }

    /// Applies the preferences of a backup and returns how many it set. A key of the system or one the app keeps for itself is not
    /// written, whatever the file says, and a value only replaces one of the same kind.
    @discardableResult
    static func apply(settings: [String: Any], to defaults: UserDefaults = .standard) -> Int {
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

    /// Asks the devices that are connected for their settings, then saves them with this Mac's in one file.
    @MainActor
    static func export(model: EngineModel) {
        Task {
            let collected = await model.collectBackup()
            let panel = NSSavePanel()
            panel.allowedContentTypes = [.json]
            panel.nameFieldStringValue = "Tandem backup.json"
            panel.canCreateDirectories = true
            panel.message = String(localized: "The settings of this Mac and your connected devices, without identities, pairings or passwords.")
            guard panel.runModal() == .OK, let url = panel.url else { return }
            do {
                try exportData(myId: model.myId, others: collected.parts).write(to: url, options: .atomic)
                if collected.missing.isEmpty {
                    model.showToast(String(localized: "Backup saved"))
                } else {
                    let names = collected.missing.formatted(.list(type: .and))
                    model.showToast(String(localized: "Backup saved, without \(names)"))
                }
            } catch {
                model.showToast(error.localizedDescription)
            }
        }
    }

    /// Opens a backup and says which of its devices this Mac is, for the person to confirm or change.
    @MainActor
    static func open(model: EngineModel) -> BackupChoice? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        do {
            let devices = try devices(in: Data(contentsOf: url))
            let me = recognise(devices, myId: model.myId, model: EngineModel.modelIdentifier(), name: model.myName)
            return BackupChoice(devices: devices, recognised: me)
        } catch {
            model.showToast(error.localizedDescription)
            return nil
        }
    }

    @MainActor
    static func restore(_ device: BackupDevice, model: EngineModel) {
        let count = apply(settings: device.settings)
        applySideEffects(model: model)
        model.showToast(String(localized: "Settings of \(device.name) restored (\(count))"))
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

/// A device in a backup, with its preferences in the words of its own app.
struct BackupDevice: Identifiable {
    static let macKind = "mac"

    let index: Int
    let deviceId: String?
    let name: String
    /// Whose preferences these are: "mac", "android" or "desktop" (the app for Linux and Windows). Only the same kind fits.
    let kind: String
    let platform: String
    let model: String?
    let settings: [String: Any]

    var id: Int { index }
    var fits: Bool { kind == Self.macKind }

    init(index: Int, deviceId: String?, name: String, kind: String, platform: String, model: String?, settings: [String: Any]) {
        self.index = index
        self.deviceId = deviceId
        self.name = name
        self.kind = kind
        self.platform = platform
        self.model = model
        self.settings = settings
    }

    init?(index: Int, json: [String: Any]) {
        guard let settings = json["settings"] as? [String: Any], let kind = json["kind"] as? String else { return nil }
        self.init(
            index: index, deviceId: json["id"] as? String, name: (json["name"] as? String) ?? kind, kind: kind,
            platform: (json["platform"] as? String) ?? kind, model: json["model"] as? String, settings: settings
        )
    }

    var symbol: String {
        switch platform {
        case "android": "smartphone"
        case "mac": "laptopcomputer"
        case "windows", "linux": "desktopcomputer"
        default: "questionmark.circle"
        }
    }

    /// Why the preferences of this device do not fit a Mac.
    var reason: String {
        switch kind {
        case "android": String(localized: "These are the settings of a phone. Restore them in Tandem on a phone.")
        default: String(localized: "These are the settings of the app for Linux and Windows. Restore them there.")
        }
    }
}

/// An opened backup, waiting for the person to pick which device comes back on this Mac.
struct BackupChoice: Identifiable {
    let id = UUID()
    let devices: [BackupDevice]
    let recognised: Int?
}
