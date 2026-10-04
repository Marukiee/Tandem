import AppKit
import Carbon.HIToolbox

/// A key combination that works in every app. It goes through Carbon's hot key registry, which
/// needs no permission at all, unlike watching the keyboard.
@MainActor
final class GlobalHotKey {
    private var reference: EventHotKeyRef?
    private let id: UInt32
    private static var next: UInt32 = 1
    private static var actions: [UInt32: () -> Void] = [:]
    private static var handlerInstalled = false

    /// `modifiers` are Carbon's: `cmdKey`, `shiftKey`, `optionKey`, `controlKey`.
    init?(keyCode: UInt32, modifiers: UInt32, action: @escaping () -> Void) {
        Self.installHandler()
        id = Self.next
        Self.next += 1
        let hotKeyID = EventHotKeyID(signature: OSType(0x544E4458), id: id) // 'TNDX'
        var ref: EventHotKeyRef?
        let status = RegisterEventHotKey(keyCode, modifiers, hotKeyID, GetApplicationEventTarget(), 0, &ref)
        guard status == noErr, let ref else { return nil }
        reference = ref
        Self.actions[id] = action
    }

    func unregister() {
        if let reference { UnregisterEventHotKey(reference) }
        reference = nil
        Self.actions[id] = nil
    }

    private static func installHandler() {
        guard !handlerInstalled else { return }
        handlerInstalled = true
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
            var hotKeyID = EventHotKeyID()
            GetEventParameter(
                event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                nil, MemoryLayout<EventHotKeyID>.size, nil, &hotKeyID
            )
            let id = hotKeyID.id
            DispatchQueue.main.async { MainActor.assumeIsolated { GlobalHotKey.actions[id]?() } }
            return noErr
        }, 1, &spec, nil, nil)
    }
}

/// The shortcut that starts "Insert from phone", as the person set it. Kept in the preferences so it
/// survives a restart and goes along with an exported backup.
struct InsertShortcut: Equatable {
    var keyCode: UInt32
    /// Carbon modifier bits.
    var modifiers: UInt32
    /// What the key is called on the keyboard it was recorded on, for showing it.
    var label: String

    static let enabledKey = "insertShortcutEnabled"
    private static let codeKey = "insertShortcutKey"
    private static let modsKey = "insertShortcutMods"
    private static let labelKey = "insertShortcutLabel"

    /// Control, Option and Command with P, because almost nothing else uses that.
    static let standard = InsertShortcut(
        keyCode: UInt32(kVK_ANSI_P),
        modifiers: UInt32(controlKey | optionKey | cmdKey),
        label: "P"
    )

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    static var current: InsertShortcut {
        get {
            let defaults = UserDefaults.standard
            guard defaults.object(forKey: codeKey) != nil else { return .standard }
            return InsertShortcut(
                keyCode: UInt32(defaults.integer(forKey: codeKey)),
                modifiers: UInt32(defaults.integer(forKey: modsKey)),
                label: defaults.string(forKey: labelKey) ?? "?"
            )
        }
        set {
            let defaults = UserDefaults.standard
            defaults.set(Int(newValue.keyCode), forKey: codeKey)
            defaults.set(Int(newValue.modifiers), forKey: modsKey)
            defaults.set(newValue.label, forKey: labelKey)
        }
    }

    static func reset() {
        let defaults = UserDefaults.standard
        for key in [codeKey, modsKey, labelKey] { defaults.removeObject(forKey: key) }
    }

    /// The symbols a person knows from the menus, in the order the system writes them.
    var keycaps: [String] {
        var caps: [String] = []
        if modifiers & UInt32(controlKey) != 0 { caps.append("\u{2303}") }
        if modifiers & UInt32(optionKey) != 0 { caps.append("\u{2325}") }
        if modifiers & UInt32(shiftKey) != 0 { caps.append("\u{21E7}") }
        if modifiers & UInt32(cmdKey) != 0 { caps.append("\u{2318}") }
        caps.append(label)
        return caps
    }

    var text: String { keycaps.joined() }

    /// Turns the modifiers of an event into Carbon's.
    static func carbon(_ flags: NSEvent.ModifierFlags) -> UInt32 {
        var bits: UInt32 = 0
        if flags.contains(.control) { bits |= UInt32(controlKey) }
        if flags.contains(.option) { bits |= UInt32(optionKey) }
        if flags.contains(.shift) { bits |= UInt32(shiftKey) }
        if flags.contains(.command) { bits |= UInt32(cmdKey) }
        return bits
    }

    /// A name for keys that have no letter on them.
    static func label(for event: NSEvent) -> String {
        switch Int(event.keyCode) {
        case kVK_Space: return "Space"
        case kVK_Return: return "\u{21A9}"
        case kVK_Tab: return "\u{21E5}"
        case kVK_Delete: return "\u{232B}"
        case kVK_LeftArrow: return "\u{2190}"
        case kVK_RightArrow: return "\u{2192}"
        case kVK_UpArrow: return "\u{2191}"
        case kVK_DownArrow: return "\u{2193}"
        default:
            guard let text = event.charactersIgnoringModifiers, let scalar = text.unicodeScalars.first else { return "?" }
            // The function keys come as private use characters, F1 first.
            if (0xF704...0xF726).contains(scalar.value) { return "F\(scalar.value - 0xF704 + 1)" }
            return text.uppercased()
        }
    }
}

/// Keeps the hot key registered for as long as it is switched on, and follows the settings.
@MainActor
final class InsertShortcutCenter {
    static let shared = InsertShortcutCenter()
    private var hotKey: GlobalHotKey?
    private var paused = false

    /// Registers what the settings say, or nothing when it is off.
    func apply() {
        hotKey?.unregister()
        hotKey = nil
        guard InsertShortcut.enabled, !paused, InsertFromPhone.shared.canInsert else { return }
        let shortcut = InsertShortcut.current
        hotKey = GlobalHotKey(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers) {
            InsertFromPhone.shared.toggle()
        }
    }

    /// While a new combination is being recorded the old one must not fire.
    func pause(_ on: Bool) {
        paused = on
        apply()
    }
}
