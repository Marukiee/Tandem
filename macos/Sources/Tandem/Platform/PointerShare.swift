import AppKit
import ApplicationServices
import CoreGraphics
import Observation
import SwiftUI
import TandemCore

/// One mouse and keyboard over several computers (see docs/INPUT_SHARING.md). Two roles live here.
///
/// The main computer has the real mouse and keyboard. When the pointer runs into the edge where another computer sits, it goes
/// over: this Mac hides its own pointer, holds it still, swallows what the hands do and sends it to the other computer. When
/// the other computer says the pointer came back, or the release keys are pressed, the pointer returns where it left.
///
/// The controlled computer plays what it is sent, with the pointer coming in at the opposite side, and says when the
/// pointer runs into the edge it came in by.
@MainActor @Observable
final class PointerShare {
    static let shared = PointerShare()

    private static let enabledKey = "pointerShareEnabled"
    private static let allowKey = "pointerShareAllowControl"
    private static let neighboursKey = "pointerShareNeighbours"

    /// Whether this Mac may be the main computer: its pointer goes over the edge to the computers set in Settings.
    var enabled: Bool = UserDefaults.standard.bool(forKey: PointerShare.enabledKey) {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.enabledKey)
            if enabled { startTap() } else { stopTap() }
        }
    }

    private static let allowedKey = "pointerShareAllowedDevices"

    /// The computers that may use the pointer and keyboard of this Mac, by device id. Nobody until it is turned on here, per
    /// computer. (An earlier version had one switch for all of them: it still counts until the page has been opened.)
    var allowed: Set<String> = Set(UserDefaults.standard.stringArray(forKey: PointerShare.allowedKey) ?? []) {
        didSet {
            UserDefaults.standard.set(Array(allowed), forKey: Self.allowedKey)
            UserDefaults.standard.removeObject(forKey: Self.allowKey)
            if let controlledBy, !allowed.contains(controlledBy) { endControlled(tell: controlledBy) }
        }
    }

    private var legacyAllowAll: Bool {
        UserDefaults.standard.bool(forKey: Self.allowKey) && UserDefaults.standard.object(forKey: Self.allowedKey) == nil
    }

    func isAllowed(_ device: String) -> Bool { allowed.contains(device) || legacyAllowAll }

    /// Turns the old single switch into a choice per computer, so the page shows what is really the case.
    func materialize(_ devices: [String]) {
        if legacyAllowAll { allowed = Set(devices) }
    }

    /// The computers that sit next to this one, by device id: on which side.
    var neighbours: [String: String] = UserDefaults.standard.dictionary(forKey: PointerShare.neighboursKey) as? [String: String] ?? [:] {
        didSet { UserDefaults.standard.set(neighbours, forKey: Self.neighboursKey) }
    }

    /// The computer that has the pointer now, and the side of this screen it went out by.
    private(set) var remote: (device: String, edge: String)?
    /// The computer that is using this Mac now, and the side it came in by.
    private(set) var controlledBy: String?

    @ObservationIgnored private var tap: CFMachPort?
    @ObservationIgnored private var source: CFRunLoopSource?
    @ObservationIgnored private var savedLocation = CGPoint.zero
    @ObservationIgnored private var enteredBy = "left"
    @ObservationIgnored private var heldModifiers: Set<CGKeyCode> = []
    /// How far the pointer has gone over there, in the pixels this Mac sent, counted from the place it came in: negative once it
    /// went back past that place. Only a way out for a computer that never says the pointer came back (see `forward`).
    @ObservationIgnored private var travelled: Double = 0

    private var model: EngineModel { EngineModel.shared }

    func start() {
        if enabled { startTap() }
    }

    // MARK: The screen

    /// The area of all screens together, in the coordinates of the events (the top left is the origin).
    private var screens: CGRect {
        var ids = [CGDirectDisplayID](repeating: 0, count: 8)
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(8, &ids, &count) == .success, count > 0 else { return CGDisplayBounds(CGMainDisplayID()) }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }.reduce(CGRect.null) { $0.union($1) }
    }

    private func opposite(_ edge: String) -> String {
        switch edge {
        case "left": "right"
        case "right": "left"
        case "top": "bottom"
        default: "top"
        }
    }

    private func tandemEdge(_ edge: String) -> TandemEdge {
        switch edge {
        case "left": .left
        case "right": .right
        case "top": .top
        default: .bottom
        }
    }

    private func edgeName(_ edge: TandemEdge) -> String {
        switch edge {
        case .left: "left"
        case .right: "right"
        case .top: "top"
        case .bottom: "bottom"
        }
    }

    /// A place on `edge` of the screens, `along` of the way down that side.
    private func point(on edge: String, along: CGFloat) -> CGPoint {
        let area = screens
        let along = min(max(along, 0), 1)
        switch edge {
        case "left": return CGPoint(x: area.minX, y: area.minY + along * (area.height - 1))
        case "right": return CGPoint(x: area.maxX - 1, y: area.minY + along * (area.height - 1))
        case "top": return CGPoint(x: area.minX + along * (area.width - 1), y: area.minY)
        default: return CGPoint(x: area.minX + along * (area.width - 1), y: area.maxY - 1)
        }
    }

    // MARK: The event tap (the main computer)

    private func startTap() {
        guard tap == nil, AXIsProcessTrusted() else {
            if !AXIsProcessTrusted() { model.injector.onPermissionNeeded?() }
            return
        }
        let types: [CGEventType] = [
            .mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged, .rightMouseDown, .rightMouseUp, .rightMouseDragged,
            .otherMouseDown, .otherMouseUp, .otherMouseDragged, .scrollWheel, .keyDown, .keyUp, .flagsChanged,
        ]
        let mask = types.reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }
        let callback: CGEventTapCallBack = { _, type, event, _ in
            // The tap sits on the main run loop, so this is the main thread.
            let swallow = MainActor.assumeIsolated { PointerShare.shared.see(type, event) }
            return swallow ? nil : Unmanaged.passUnretained(event)
        }
        guard let created = CGEvent.tapCreate(tap: .cghidEventTap, place: .headInsertEventTap, options: .defaultTap, eventsOfInterest: mask, callback: callback, userInfo: nil) else { return }
        tap = created
        let runLoopSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, created, 0)
        source = runLoopSource
        CFRunLoopAddSource(CFRunLoopGetMain(), runLoopSource, .commonModes)
        CGEvent.tapEnable(tap: created, enable: true)
    }

    private func stopTap() {
        if remote != nil { comeBack(along: nil) }
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        if let source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
        tap = nil
        source = nil
    }

    /// What the hands did. True when the event is for the other computer and has to go no further here.
    fileprivate func see(_ type: CGEventType, _ event: CGEvent) -> Bool {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return false
        }
        guard enabled else { return false }
        guard let remote else { return watchEdges(type, event) }
        return forward(type, event, to: remote.device)
    }

    /// The pointer on this screen: does it run into an edge where a computer sits?
    private func watchEdges(_ type: CGEventType, _ event: CGEvent) -> Bool {
        guard type == .mouseMoved, !neighbours.isEmpty else { return false }
        let at = event.location
        let dx = event.getDoubleValueField(.mouseEventDeltaX)
        let dy = event.getDoubleValueField(.mouseEventDeltaY)
        let area = screens
        for (device, edge) in neighbours where model.device(device)?.online == true {
            let along: CGFloat
            switch edge {
            case "right" where at.x >= area.maxX - 1 && dx > 0: along = (at.y - area.minY) / max(area.height - 1, 1)
            case "left" where at.x <= area.minX && dx < 0: along = (at.y - area.minY) / max(area.height - 1, 1)
            case "top" where at.y <= area.minY && dy < 0: along = (at.x - area.minX) / max(area.width - 1, 1)
            case "bottom" where at.y >= area.maxY - 1 && dy > 0: along = (at.x - area.minX) / max(area.width - 1, 1)
            default: continue
            }
            goOver(to: device, edge: edge, along: along)
            return true
        }
        return false
    }

    private func goOver(to device: String, edge: String, along: CGFloat) {
        guard let engine = model.tandem else { return }
        savedLocation = CGEvent(source: nil)?.location ?? .zero
        remote = (device, edge)
        heldModifiers = []
        travelled = 0
        CGAssociateMouseAndMouseCursorPosition(0)
        CGDisplayHideCursor(CGMainDisplayID())
        Task { try? await engine.sendPointerShare(target: device, msg: .enter(edge: tandemEdge(edge), along: Float(along))) }
        FloatingToast.show(String(localized: "The pointer is on \(model.device(device)?.name ?? "another computer"). Press Control, Option and Command with Escape to bring it back."), symbol: "cursorarrow.motionlines")
    }

    /// The pointer returns to this Mac, at the place it left by or, when the other computer says where it came from, there.
    private func comeBack(along: CGFloat?) {
        guard let remote else { return }
        let target = along.map { point(on: remote.edge, along: $0) } ?? savedLocation
        self.remote = nil
        CGWarpMouseCursorPosition(target)
        CGAssociateMouseAndMouseCursorPosition(1)
        CGDisplayShowCursor(CGMainDisplayID())
    }

    private static let escape: CGKeyCode = 53
    /// How far back past the place it came in the pointer goes before this Mac takes it back by itself, in pixels.
    private static let wayOut = 400.0

    private func forward(_ type: CGEventType, _ event: CGEvent, to device: String) -> Bool {
        guard let engine = model.tandem else { comeBack(along: nil); return false }
        func send(_ input: TandemInput) { Task { try? await engine.sendInput(target: device, input: input) } }
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let dx = event.getIntegerValueField(.mouseEventDeltaX)
            let dy = event.getIntegerValueField(.mouseEventDeltaY)
            if dx != 0 || dy != 0 { send(.pointer(dx: Int16(clamping: dx), dy: Int16(clamping: dy))) }
            // The other computer says when the pointer runs into the edge it came in by. One that cannot (it does not move the
            // pointer, or never answers) would keep it for good, so going back well past where it came in brings it home anyway.
            if let edge = remote?.edge {
                switch edge {
                case "right": travelled += Double(dx)
                case "left": travelled -= Double(dx)
                case "bottom": travelled += Double(dy)
                default: travelled -= Double(dy)
                }
                if travelled < -Self.wayOut {
                    Task { try? await engine.sendPointerShare(target: device, msg: .release) }
                    comeBack(along: nil)
                    return true
                }
            }
        case .leftMouseDown: send(.button(button: 0, down: true))
        case .leftMouseUp: send(.button(button: 0, down: false))
        case .rightMouseDown: send(.button(button: 1, down: true))
        case .rightMouseUp: send(.button(button: 1, down: false))
        case .otherMouseDown: send(.button(button: 2, down: true))
        case .otherMouseUp: send(.button(button: 2, down: false))
        case .scrollWheel:
            let dy = event.getIntegerValueField(.scrollWheelEventPointDeltaAxis1)
            let dx = event.getIntegerValueField(.scrollWheelEventPointDeltaAxis2)
            if dx != 0 || dy != 0 { send(.scroll(dx: Int16(clamping: dx), dy: Int16(clamping: dy))) }
        case .keyDown, .keyUp:
            let code = CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode))
            let down = type == .keyDown
            let flags = event.flags
            // The way back: the three modifiers with Escape.
            if down, code == Self.escape, flags.contains(.maskControl), flags.contains(.maskAlternate), flags.contains(.maskCommand) {
                Task { try? await engine.sendPointerShare(target: device, msg: .release) }
                comeBack(along: nil)
                return true
            }
            send(.key(code: UInt16(code), down: down, mods: mods(from: flags)))
        case .flagsChanged:
            let code = CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode))
            let down = isDown(modifier: code, flags: event.flags)
            send(.key(code: UInt16(code), down: down, mods: mods(from: event.flags)))
        default:
            break
        }
        return true
    }

    private func mods(from flags: CGEventFlags) -> UInt8 {
        var out: UInt8 = 0
        if flags.contains(.maskShift) { out |= 1 }
        if flags.contains(.maskControl) { out |= 2 }
        if flags.contains(.maskAlternate) { out |= 4 }
        if flags.contains(.maskCommand) { out |= 8 }
        return out
    }

    /// A modifier key reports only that the flags changed; whether it went down is whether its flag is set now.
    private func isDown(modifier code: CGKeyCode, flags: CGEventFlags) -> Bool {
        switch code {
        case 54, 55: flags.contains(.maskCommand)
        case 56, 60: flags.contains(.maskShift)
        case 58, 61: flags.contains(.maskAlternate)
        case 59, 62: flags.contains(.maskControl)
        case 57: flags.contains(.maskAlphaShift)
        default: false
        }
    }

    // MARK: What the other computer says

    /// A message about the pointer from another computer.
    func received(_ message: TandemPointerShare, from device: String) {
        switch message {
        case let .enter(edge, along):
            guard isAllowed(device), controlledBy == nil || controlledBy == device, model.tandem != nil else {
                // Not allowed: the pointer is handed straight back.
                Task { try? await model.tandem?.sendPointerShare(target: device, msg: .leave(along: along)) }
                return
            }
            controlledBy = device
            enteredBy = opposite(edgeName(edge))
            CGWarpMouseCursorPosition(point(on: enteredBy, along: CGFloat(along)))
        case let .leave(along):
            if remote?.device == device { comeBack(along: CGFloat(along)) }
        case .release:
            if remote?.device == device { comeBack(along: nil) }
            if controlledBy == device { controlledBy = nil }
        }
    }

    /// Input that came from the computer that has the pointer now. True when it was dealt with here.
    func controlled(_ input: TandemInput, from device: String) -> Bool {
        guard controlledBy == device else { return false }
        model.injector.handleShared(input, from: device)
        if case let .pointer(dx, dy) = input, let at = CGEvent(source: nil)?.location {
            let area = screens
            let leaving: Bool
            switch enteredBy {
            case "left": leaving = at.x <= area.minX && dx < 0
            case "right": leaving = at.x >= area.maxX - 1 && dx > 0
            case "top": leaving = at.y <= area.minY && dy < 0
            default: leaving = at.y >= area.maxY - 1 && dy > 0
            }
            if leaving {
                let along: CGFloat = (enteredBy == "left" || enteredBy == "right")
                    ? (at.y - area.minY) / max(area.height - 1, 1)
                    : (at.x - area.minX) / max(area.width - 1, 1)
                endControlled(tell: device, along: Float(min(max(along, 0), 1)))
            }
        }
        return true
    }

    private func endControlled(tell device: String, along: Float = 0) {
        controlledBy = nil
        Task { try? await model.tandem?.sendPointerShare(target: device, msg: .leave(along: along)) }
    }

    func deviceGone(_ id: String) {
        if remote?.device == id { comeBack(along: nil) }
        if controlledBy == id { controlledBy = nil }
    }
}
