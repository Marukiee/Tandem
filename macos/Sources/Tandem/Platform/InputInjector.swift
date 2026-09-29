import AppKit
import ApplicationServices
import TandemCore

/// Turns input from a phone (trackpad, keyboard, media keys) into real events.
///
/// Needs the Accessibility permission. The first event asks for it; until it is
/// granted the events are dropped.
///
/// A phone can go away in the middle of a gesture, so everything that is held down
/// (a mouse button during a drag, a key) is remembered and let go again when the
/// phone disconnects or goes quiet. Otherwise a lost connection could leave a button
/// pressed and the Mac dragging things around by itself.
@MainActor
final class InputInjector {
    /// Called when input arrives but the permission is missing, at most every 30 seconds.
    var onPermissionNeeded: (() -> Void)?

    private let source = CGEventSource(stateID: .hidSystemState)
    private var lastPermissionPrompt = Date.distantPast

    /// Which buttons are held: 0 left, 1 right, 2 middle.
    private var heldButtons: Set<UInt8> = []
    /// Keys that are down, with the flags they went down with.
    private var heldKeys: [CGKeyCode: CGEventFlags] = [:]
    /// The click count of the press in progress, so a drag after a double tap selects by word.
    private var pressClickState: Int64 = 1
    private var lastClick: (time: Date, location: CGPoint, button: UInt8, count: Int64)?

    private var lastSource: String?
    private var watchdog: Task<Void, Never>?
    /// Long enough for a deliberate press and hold, short enough to matter when the
    /// connection died without an event.
    private static let silenceLimit: Duration = .seconds(45)

    /// Key modifiers as sent by the phone: shift 1, control 2, option 4, command 8.
    private func flags(from mods: UInt8) -> CGEventFlags {
        var flags: CGEventFlags = []
        if mods & 1 != 0 { flags.insert(.maskShift) }
        if mods & 2 != 0 { flags.insert(.maskControl) }
        if mods & 4 != 0 { flags.insert(.maskAlternate) }
        if mods & 8 != 0 { flags.insert(.maskCommand) }
        return flags
    }

    var isTrusted: Bool { AXIsProcessTrusted() }

    func requestPermissionIfNeeded() -> Bool {
        if AXIsProcessTrusted() { return true }
        if Date().timeIntervalSince(lastPermissionPrompt) > 30 {
            lastPermissionPrompt = Date()
            let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
            _ = AXIsProcessTrustedWithOptions(options)
            onPermissionNeeded?()
        }
        return false
    }

    func handle(_ input: TandemInput, from device: String) {
        guard requestPermissionIfNeeded() else { return }
        lastSource = device
        switch input {
        case let .pointer(dx, dy): movePointer(dx: Double(dx), dy: Double(dy))
        case let .scroll(dx, dy): scroll(dx: Int32(dx), dy: Int32(dy))
        case let .button(button, down): setButton(button, down: down)
        case let .click(button, count): click(button, count: Int64(max(1, count)))
        case let .key(code, down, mods): key(code: CGKeyCode(code), down: down, flags: flags(from: mods))
        case let .text(text): type(text)
        case let .media(key): media(key)
        }
        armWatchdog()
    }

    /// The phone that was sending is gone: let go of whatever it was holding.
    func sourceDisconnected(_ device: String) {
        guard device == lastSource else { return }
        releaseAll()
        lastSource = nil
    }

    func releaseAll() {
        watchdog?.cancel()
        guard AXIsProcessTrusted() else {
            heldButtons.removeAll()
            heldKeys.removeAll()
            return
        }
        let location = currentLocation()
        for button in heldButtons { post(mouse: button, down: false, at: location, clickState: pressClickState) }
        heldButtons.removeAll()
        for (code, flags) in heldKeys { postKey(code: code, down: false, flags: flags) }
        heldKeys.removeAll()
    }

    private func armWatchdog() {
        watchdog?.cancel()
        guard !heldButtons.isEmpty || !heldKeys.isEmpty else { return }
        watchdog = Task { [weak self] in
            try? await Task.sleep(for: Self.silenceLimit)
            guard !Task.isCancelled else { return }
            self?.releaseAll()
        }
    }

    // MARK: Pointer

    private func currentLocation() -> CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    /// A little acceleration: small movements stay precise, fast swipes cover the screen.
    private func accelerated(_ delta: Double) -> Double {
        let magnitude = abs(delta)
        let gain = 1.2 + min(magnitude / 24, 2.4)
        return delta * gain
    }

    private func movePointer(dx: Double, dy: Double) {
        let current = currentLocation()
        let target = clamp(CGPoint(x: current.x + accelerated(dx), y: current.y + accelerated(dy)))

        // With a button held the pointer drags, and the event has to say which button,
        // or the app underneath would see a plain move and drop the selection.
        let type: CGEventType
        let button: CGMouseButton
        if heldButtons.contains(0) {
            (type, button) = (.leftMouseDragged, .left)
        } else if heldButtons.contains(1) {
            (type, button) = (.rightMouseDragged, .right)
        } else if heldButtons.contains(2) {
            (type, button) = (.otherMouseDragged, .center)
        } else {
            (type, button) = (.mouseMoved, .left)
        }
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: target, mouseButton: button) else { return }
        event.setDoubleValueField(.mouseEventDeltaX, value: dx)
        event.setDoubleValueField(.mouseEventDeltaY, value: dy)
        if type != .mouseMoved {
            event.setIntegerValueField(.mouseEventClickState, value: pressClickState)
        }
        event.post(tap: .cghidEventTap)
    }

    private func clamp(_ point: CGPoint) -> CGPoint {
        var bounds = CGRect.null
        var count: UInt32 = 0
        var ids = [CGDirectDisplayID](repeating: 0, count: 8)
        if CGGetActiveDisplayList(8, &ids, &count) == .success {
            for id in ids.prefix(Int(count)) { bounds = bounds.union(CGDisplayBounds(id)) }
        }
        guard !bounds.isNull else { return point }
        return CGPoint(
            x: min(max(point.x, bounds.minX), bounds.maxX - 1),
            y: min(max(point.y, bounds.minY), bounds.maxY - 1)
        )
    }

    private func mouseTypes(_ button: UInt8) -> (down: CGEventType, up: CGEventType, button: CGMouseButton) {
        switch button {
        case 1: (.rightMouseDown, .rightMouseUp, .right)
        case 2: (.otherMouseDown, .otherMouseUp, .center)
        default: (.leftMouseDown, .leftMouseUp, .left)
        }
    }

    private func post(mouse button: UInt8, down: Bool, at location: CGPoint, clickState: Int64) {
        let types = mouseTypes(button)
        guard let event = CGEvent(
            mouseEventSource: source,
            mouseType: down ? types.down : types.up,
            mouseCursorPosition: location,
            mouseButton: types.button
        ) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: clickState)
        event.post(tap: .cghidEventTap)
    }

    /// Two presses close together in time and place count as a double click, which is
    /// what makes a double tap followed by a drag select whole words.
    private func nextClickState(_ button: UInt8, at location: CGPoint) -> Int64 {
        guard let last = lastClick, last.button == button,
              Date().timeIntervalSince(last.time) < NSEvent.doubleClickInterval,
              hypot(last.location.x - location.x, last.location.y - location.y) < 8
        else { return 1 }
        return last.count + 1
    }

    private func setButton(_ button: UInt8, down: Bool) {
        let location = currentLocation()
        if down {
            guard !heldButtons.contains(button) else { return }
            pressClickState = nextClickState(button, at: location)
            post(mouse: button, down: true, at: location, clickState: pressClickState)
            heldButtons.insert(button)
        } else {
            guard heldButtons.contains(button) else { return }
            post(mouse: button, down: false, at: location, clickState: pressClickState)
            heldButtons.remove(button)
            lastClick = (Date(), location, button, pressClickState)
        }
    }

    private func click(_ button: UInt8, count: Int64) {
        let location = currentLocation()
        for state in 1 ... count {
            post(mouse: button, down: true, at: location, clickState: state)
            post(mouse: button, down: false, at: location, clickState: state)
        }
        lastClick = (Date(), location, button, count)
    }

    private func scroll(dx: Int32, dy: Int32) {
        CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?
            .post(tap: .cghidEventTap)
    }

    // MARK: Keyboard

    private func key(code: CGKeyCode, down: Bool, flags: CGEventFlags) {
        var flags = flags
        // Real arrow keys always carry the function flag. The system shortcuts for
        // Mission Control, App Exposé and switching spaces only match with it.
        if (123 ... 126).contains(code) { flags.insert(.maskSecondaryFn) }
        postKey(code: code, down: down, flags: flags)
        if down { heldKeys[code] = flags } else { heldKeys[code] = nil }
    }

    private func postKey(code: CGKeyCode, down: Bool, flags: CGEventFlags) {
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else { return }
        event.flags = flags
        event.post(tap: .cghidEventTap)
    }

    private func type(_ text: String) {
        // Unicode goes in as a string on a dummy key event, which works for any layout.
        let units = Array(text.utf16)
        var index = 0
        while index < units.count {
            let chunk = Array(units[index ..< min(index + 16, units.count)])
            for down in [true, false] {
                guard let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                event.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                event.post(tap: .cghidEventTap)
            }
            index += chunk.count
        }
    }

    private func media(_ key: TandemMediaKey) {
        let code: Int
        switch key {
        case .volumeUp: code = 0
        case .volumeDown: code = 1
        case .mute: code = 7
        case .playPause: code = 16
        case .next: code = 17
        case .previous: code = 18
        }
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = (code << 16) | ((down ? 0xA : 0xB) << 8)
            NSEvent.otherEvent(
                with: .systemDefined,
                location: .zero,
                modifierFlags: flags,
                timestamp: 0,
                windowNumber: 0,
                context: nil,
                subtype: 8,
                data1: data1,
                data2: -1
            )?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
