import AppKit
import Carbon.HIToolbox
import Observation
import SwiftUI
import TandemCore

/// The things Tandem does with one press, for the four tiles of the menu bar panel and for shortcuts that work in every app. Which four
/// tiles there are, and which shortcut starts what, is chosen in Settings, General.
enum PanelAction: String, CaseIterable, Identifiable, Codable {
    case takePhoto, scanDocument, insertFromPhone, sendFiles, sendClipboard, clipboardHistory, findPhone, phoneOutput

    var id: String { rawValue }

    /// The name in Settings, which may be long.
    var title: String {
        switch self {
        case .takePhoto: String(localized: "Take a photo with your phone")
        case .scanDocument: String(localized: "Scan a document with your phone")
        case .insertFromPhone: String(localized: "Insert from phone")
        case .sendFiles: String(localized: "Send files")
        case .sendClipboard: String(localized: "Send the clipboard")
        case .clipboardHistory: String(localized: "Clipboard history")
        case .findPhone: String(localized: "Find your phone")
        case .phoneOutput: String(localized: "Phone as output")
        }
    }

    /// The name on a tile: one short line, with the shortcut under it.
    var tileTitle: String {
        switch self {
        case .takePhoto: String(localized: "Photo")
        case .scanDocument: String(localized: "Scan")
        case .insertFromPhone: String(localized: "Insert")
        case .sendFiles: String(localized: "Send")
        case .sendClipboard: String(localized: "Paste there")
        case .clipboardHistory: String(localized: "Clipboard")
        case .findPhone: String(localized: "Find phone")
        case .phoneOutput: String(localized: "Speaker")
        }
    }

    var symbol: String {
        switch self {
        case .takePhoto: "camera.fill"
        case .scanDocument: "doc.viewfinder.fill"
        case .insertFromPhone: "iphone.and.arrow.forward"
        case .sendFiles: "paperplane.fill"
        case .sendClipboard: "doc.on.clipboard"
        case .clipboardHistory: "list.clipboard.fill"
        case .findPhone: "bell.and.waves.left.and.right.fill"
        case .phoneOutput: "speaker.wave.2.fill"
        }
    }

    /// The four tiles until the person picks others.
    static let standardTiles: [PanelAction] = [.takePhoto, .scanDocument, .sendFiles, .clipboardHistory]
    private static let tilesKey = "panelTiles"

    static var tiles: [PanelAction] {
        get {
            let stored = (UserDefaults.standard.array(forKey: tilesKey) as? [String])?.compactMap(PanelAction.init(rawValue:)) ?? []
            return stored.count == 4 ? stored : standardTiles
        }
        set { UserDefaults.standard.set(newValue.map(\.rawValue), forKey: tilesKey) }
    }

    /// Whether this can be done right now: what needs a phone waits for one to be connected. It still says why when it is pressed.
    @MainActor var ready: Bool {
        let model = EngineModel.shared
        switch self {
        case .takePhoto, .scanDocument, .insertFromPhone, .findPhone, .phoneOutput: return model.devices.contains { $0.platform == .android && $0.online }
        case .sendFiles, .sendClipboard: return model.devices.contains(where: \.online)
        case .clipboardHistory: return true
        }
    }

    @MainActor func perform() {
        let model = EngineModel.shared
        switch self {
        case .takePhoto: InsertFromPhone.shared.begin(kind: .photo)
        case .scanDocument: InsertFromPhone.shared.begin(kind: .document)
        case .insertFromPhone: InsertFromPhone.shared.toggle()
        case .clipboardHistory: ClipboardPanelController.shared.toggle()
        case .sendFiles:
            guard let device = Self.target(phoneOnly: false) else { return Self.nobody() }
            let panel = NSOpenPanel()
            panel.canChooseFiles = true
            panel.canChooseDirectories = true
            panel.allowsMultipleSelection = true
            panel.message = String(localized: "Send to \(device.name)")
            NSApp.activate(ignoringOtherApps: true)
            if panel.runModal() == .OK {
                model.send(urls: panel.urls, to: [device.id])
                Self.lastTarget = device.id
            }
        case .sendClipboard:
            guard let device = Self.target(phoneOnly: false) else { return Self.nobody() }
            model.sendClipboard(to: [device.id])
            Self.lastTarget = device.id
        case .findPhone:
            guard let phone = Self.target(phoneOnly: true) else { return Self.nobody() }
            model.ring(phone.id, on: !FindOthers.shared.ringing.contains(phone.id))
        case .phoneOutput:
            guard let phone = Self.target(phoneOnly: true) else { return Self.nobody() }
            model.toggleSpeaker(for: phone.id)
        }
    }

    /// Where a press without a chosen device goes: the device last sent to by a tile or shortcut, if it is there, otherwise the first
    /// phone that is, otherwise any device that is.
    private static let lastTargetKey = "panelLastTarget"
    private static var lastTarget: String? {
        get { UserDefaults.standard.string(forKey: lastTargetKey) }
        set { UserDefaults.standard.set(newValue, forKey: lastTargetKey) }
    }

    @MainActor private static func target(phoneOnly: Bool) -> TandemDevice? {
        let online = EngineModel.shared.devices.filter { $0.online && (!phoneOnly || $0.platform == .android) }
        return online.first { $0.id == lastTarget } ?? online.first { $0.platform == .android } ?? online.first
    }

    @MainActor private static func nobody() {
        EngineModel.shared.showToast(String(localized: "No device is connected right now"))
    }
}

/// The shortcuts of the actions that have no setting of their own elsewhere. Insert from phone and the clipboard history keep theirs
/// where they were (`InsertShortcut`, `ClipboardHistory.hotkey`); the list in Settings shows all of them together.
@MainActor
final class ActionShortcuts {
    static let shared = ActionShortcuts()
    private static let key = "actionShortcuts"
    private var hotKeys: [GlobalHotKey] = []
    private var paused = false

    /// Control and Option with a letter, as Control and Option with V opens the clipboard history: P for a photo, D for a document,
    /// S to send files. Almost nothing else uses these, so they take nothing away from an app.
    static let standard: [PanelAction: Shortcut] = [
        .takePhoto: controlOption("p", UInt32(kVK_ANSI_P)),
        .scanDocument: controlOption("d", UInt32(kVK_ANSI_D)),
        .sendFiles: controlOption("s", UInt32(kVK_ANSI_S)),
    ]

    private static func controlOption(_ character: Character, _ fallback: UInt32) -> Shortcut {
        Shortcut(keyCode: KeyboardLayout.keyCode(forCharacter: character) ?? fallback, modifiers: UInt32(controlKey | optionKey), key: character.uppercased())
    }

    /// Whether the shortcut of an action is kept here rather than in a setting of its own.
    static func owns(_ action: PanelAction) -> Bool { action != .insertFromPhone && action != .clipboardHistory }

    private var stored: [String: Shortcut?] {
        get {
            guard let data = UserDefaults.standard.data(forKey: Self.key),
                  let map = try? JSONDecoder().decode([String: Shortcut?].self, from: data) else { return [:] }
            return map
        }
        set { if let data = try? JSONEncoder().encode(newValue) { UserDefaults.standard.set(data, forKey: Self.key) } }
    }

    /// The shortcut of an action, nil for none. One the person cleared stays cleared; one never touched is the standard one.
    func shortcut(for action: PanelAction) -> Shortcut? {
        switch action {
        case .insertFromPhone:
            guard InsertShortcut.enabled else { return nil }
            let insert = InsertShortcut.current
            return Shortcut(keyCode: insert.keyCode, modifiers: insert.modifiers, key: insert.label)
        case .clipboardHistory:
            return ClipboardHistory.shared.hotkeyOn ? ClipboardHistory.shared.hotkey : nil
        default:
            if let chosen = stored[action.rawValue] { return chosen }
            return Self.standard[action]
        }
    }

    func set(_ shortcut: Shortcut?, for action: PanelAction) {
        switch action {
        case .insertFromPhone:
            InsertShortcut.enabled = shortcut != nil
            if let shortcut { InsertShortcut.current = InsertShortcut(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers, label: shortcut.key) }
            InsertShortcutCenter.shared.apply()
        case .clipboardHistory:
            ClipboardHistory.shared.hotkeyOn = shortcut != nil
            if let shortcut { ClipboardHistory.shared.hotkey = shortcut }
        default:
            var map = stored
            map[action.rawValue] = .some(shortcut)
            stored = map
            apply()
        }
    }

    /// The action that already has this combination, so one combination never starts two things.
    func owner(of shortcut: Shortcut) -> PanelAction? {
        PanelAction.allCases.first { self.shortcut(for: $0).map { $0.keyCode == shortcut.keyCode && $0.modifiers == shortcut.modifiers } ?? false }
    }

    /// Registers the shortcuts kept here. Called at the start and after every change.
    func apply() {
        hotKeys.forEach { $0.unregister() }
        hotKeys = []
        guard !paused else { return }
        for action in PanelAction.allCases where Self.owns(action) {
            guard let shortcut = shortcut(for: action),
                  let hotKey = GlobalHotKey(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers, action: { action.perform() }) else { continue }
            hotKeys.append(hotKey)
        }
    }

    /// While a combination is recorded, none of them may fire: the one being typed could be one of them.
    func pause(_ on: Bool) {
        paused = on
        apply()
        InsertShortcutCenter.shared.pause(on)
        if on { ClipboardPanelController.shared.suspendHotkey() } else { ClipboardPanelController.shared.registerHotkey() }
    }
}
