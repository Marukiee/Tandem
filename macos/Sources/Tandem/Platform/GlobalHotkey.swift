import AppKit
import Carbon.HIToolbox

/// A key and the modifiers held with it, as stored in the preferences and handed to the system.
struct Shortcut: Codable, Equatable {
    var keyCode: UInt32
    /// Carbon modifiers: command 256, shift 512, option 2048, control 4096.
    var modifiers: UInt32
    /// The key as it is printed on the cap, for showing.
    var key: String

    /// Control and Option with V. Almost nothing uses it, so it takes nothing away from an app, and it sits next to
    /// the paste shortcut a hand already knows.
    static var standard: Shortcut {
        Shortcut(keyCode: KeyboardLayout.keyCode(forCharacter: "v") ?? 9, modifiers: UInt32(controlKey | optionKey), key: "V")
    }

    static func stored() -> Shortcut {
        guard let data = UserDefaults.standard.data(forKey: ClipboardHistory.Keys.hotkey),
              let shortcut = try? JSONDecoder().decode(Shortcut.self, from: data) else { return .standard }
        return shortcut
    }

    func store() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: ClipboardHistory.Keys.hotkey) }
    }

    /// "⌃⌥V", in the order the system menus write it.
    var display: String {
        var text = ""
        if modifiers & UInt32(controlKey) != 0 { text += "⌃" }
        if modifiers & UInt32(optionKey) != 0 { text += "⌥" }
        if modifiers & UInt32(shiftKey) != 0 { text += "⇧" }
        if modifiers & UInt32(cmdKey) != 0 { text += "⌘" }
        return text + key
    }

    static func carbonModifiers(from flags: NSEvent.ModifierFlags) -> UInt32 {
        var result: UInt32 = 0
        if flags.contains(.command) { result |= UInt32(cmdKey) }
        if flags.contains(.option) { result |= UInt32(optionKey) }
        if flags.contains(.control) { result |= UInt32(controlKey) }
        if flags.contains(.shift) { result |= UInt32(shiftKey) }
        return result
    }

    /// The cap of a key that is not a letter or a digit.
    static func name(forKeyCode code: UInt16) -> String? {
        switch Int(code) {
        case kVK_Space: "Space"
        case kVK_Return: "↩"
        case kVK_Tab: "⇥"
        case kVK_Delete: "⌫"
        case kVK_LeftArrow: "←"
        case kVK_RightArrow: "→"
        case kVK_UpArrow: "↑"
        case kVK_DownArrow: "↓"
        case kVK_F1: "F1"
        case kVK_F2: "F2"
        case kVK_F3: "F3"
        case kVK_F4: "F4"
        case kVK_F5: "F5"
        case kVK_F6: "F6"
        case kVK_F7: "F7"
        case kVK_F8: "F8"
        case kVK_F9: "F9"
        case kVK_F10: "F10"
        case kVK_F11: "F11"
        case kVK_F12: "F12"
        default: nil
        }
    }
}

/// One shortcut that works in every app, through the system's hot key service. That needs no permission, unlike
/// listening to every key press.
@MainActor
final class GlobalHotkey {
    private var reference: EventHotKeyRef?
    private static var handlerInstalled = false
    private static weak var active: GlobalHotkey?
    var onPress: (() -> Void)?

    func register(_ shortcut: Shortcut) {
        unregister()
        Self.installHandlerOnce()
        Self.active = self
        let id = EventHotKeyID(signature: OSType(0x544E_4448), id: 1)
        RegisterEventHotKey(shortcut.keyCode, shortcut.modifiers, id, GetApplicationEventTarget(), 0, &reference)
    }

    func unregister() {
        if let reference { UnregisterEventHotKey(reference) }
        reference = nil
    }

    private static func installHandlerOnce() {
        guard !handlerInstalled else { return }
        handlerInstalled = true
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { GlobalHotkey.active?.onPress?() } }
            return noErr
        }, 1, &spec, nil, nil)
    }
}

/// Which key makes a character on the layout in use, so a synthesized Command V is a V on any keyboard.
enum KeyboardLayout {
    static func keyCode(forCharacter wanted: Character) -> UInt32? {
        guard let source = TISCopyCurrentASCIICapableKeyboardLayoutInputSource()?.takeRetainedValue(),
              let property = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData)
        else { return nil }
        let data = Unmanaged<CFData>.fromOpaque(property).takeUnretainedValue()
        guard let bytes = CFDataGetBytePtr(data) else { return nil }
        let layout = UnsafeRawPointer(bytes).assumingMemoryBound(to: UCKeyboardLayout.self)
        for code in 0 ..< 128 {
            var dead: UInt32 = 0
            var length = 0
            var characters = [UniChar](repeating: 0, count: 4)
            let status = UCKeyTranslate(
                layout, UInt16(code), UInt16(kUCKeyActionDown), 0, UInt32(LMGetKbdType()),
                OptionBits(kUCKeyTranslateNoDeadKeysBit), &dead, characters.count, &length, &characters
            )
            guard status == noErr, length == 1 else { continue }
            if String(utf16CodeUnits: characters, count: 1).lowercased() == String(wanted).lowercased() { return UInt32(code) }
        }
        return nil
    }
}
