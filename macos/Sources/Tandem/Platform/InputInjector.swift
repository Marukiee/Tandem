import AppKit
import ApplicationServices
import TandemCore

/// Turns input from a phone (trackpad, keyboard, media keys) into real events.
///
/// Needs the Accessibility permission. The first event asks for it; until it is
/// granted the events are dropped.
@MainActor
final class InputInjector {
    private var buttonDown = false
    private var lastPermissionPrompt = Date.distantPast

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
        }
        return false
    }

    func handle(_ input: TandemInput) {
        guard requestPermissionIfNeeded() else { return }
        switch input {
        case let .pointer(dx, dy): movePointer(dx: Double(dx), dy: Double(dy))
        case let .scroll(dx, dy): scroll(dx: Int32(dx), dy: Int32(dy))
        case let .button(button, down): setButton(button, down: down)
        case let .click(button, count): click(button, count: Int64(max(1, count)))
        case let .key(code, down, mods): key(code: CGKeyCode(code), down: down, flags: flags(from: mods))
        case let .text(text): type(text)
        case let .media(key): media(key)
        }
    }

    // MARK: Pointer

    /// A little acceleration: small movements stay precise, fast swipes cover the screen.
    private func accelerated(_ delta: Double) -> Double {
        let magnitude = abs(delta)
        let gain = 1.2 + min(magnitude / 24, 2.4)
        return delta * gain
    }

    private func movePointer(dx: Double, dy: Double) {
        guard let current = CGEvent(source: nil)?.location else { return }
        let target = clamp(CGPoint(x: current.x + accelerated(dx), y: current.y + accelerated(dy)))
        let type: CGEventType = buttonDown ? .leftMouseDragged : .mouseMoved
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: target, mouseButton: .left) else { return }
        event.setDoubleValueField(.mouseEventDeltaX, value: dx)
        event.setDoubleValueField(.mouseEventDeltaY, value: dy)
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

    private func setButton(_ button: UInt8, down: Bool) {
        guard let location = CGEvent(source: nil)?.location else { return }
        let isRight = button == 1
        let type: CGEventType = isRight ? (down ? .rightMouseDown : .rightMouseUp) : (down ? .leftMouseDown : .leftMouseUp)
        let cgButton: CGMouseButton = isRight ? .right : .left
        CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: location, mouseButton: cgButton)?
            .post(tap: .cghidEventTap)
        if !isRight { buttonDown = down }
    }

    private func click(_ button: UInt8, count: Int64) {
        guard let location = CGEvent(source: nil)?.location else { return }
        let isRight = button == 1
        let cgButton: CGMouseButton = isRight ? .right : .left
        for state in 1 ... count {
            for down in [true, false] {
                let type: CGEventType = isRight ? (down ? .rightMouseDown : .rightMouseUp) : (down ? .leftMouseDown : .leftMouseUp)
                let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: location, mouseButton: cgButton)
                event?.setIntegerValueField(.mouseEventClickState, value: state)
                event?.post(tap: .cghidEventTap)
            }
        }
    }

    private func scroll(dx: Int32, dy: Int32) {
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?
            .post(tap: .cghidEventTap)
    }

    // MARK: Keyboard

    private func key(code: CGKeyCode, down: Bool, flags: CGEventFlags) {
        guard let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down) else { return }
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
                guard let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down) else { continue }
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
