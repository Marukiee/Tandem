import AppKit
import SwiftUI

/// Development helper: `TANDEM_DEBUG_INPUT=<steps>` plays a few steps against a window of the app, so what only shows when the
/// app is used (a click, a double click, the scroll wheel, a key, a narrow or full screen window) can be tried without anyone at the
/// mouse and without a picture of the screen. The steps are separated by `;` and run one after another:
///
/// - `main`: from here on the steps go to the main window; until then to the debug window, the one that is not the main window.
/// - `wait:<seconds>`
/// - `click:<x>,<y>` and `double:<x>,<y>`: the mouse, at that point of the window counted from its top left corner.
/// - `wheel:<x>,<y>,<dy>`: the scroll wheel at that point, by that many points (negative scrolls down the page).
/// - `key:<code>`: a key by its code (53 is Escape).
/// - `width:<points>` and `fullscreen`: resizes the window, or takes it in or out of full screen.
/// - `report`: writes where the window and its three buttons are.
/// - `snap`: the pictures of the windows, as SIGUSR1 does, kept under a number of their own.
///
/// What every step did is written to `input.txt` in the debug folder. Events go through the window as real ones do, so they land on
/// whatever a person's click at that place would land on.
@MainActor
enum DebugInput {
    private static var useMain = false
    private static var log: [String] = []
    private static var snaps = 0

    static func run(_ script: String, into directory: URL) {
        let steps = script.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }
        Task { @MainActor in
            for step in steps {
                await play(step, into: directory)
                try? await Task.sleep(for: .milliseconds(350))
            }
            note("done")
        }
    }

    static func note(_ line: String) {
        log.append(line)
        DebugSupport.write(log.joined(separator: "\n") + "\n", named: "input.txt")
    }

    private static var window: NSWindow? {
        // The main window has the name of the app; a debug window has another title, which a page may change (Settings does).
        let titled = NSApp.windows.filter { $0.isVisible && $0.styleMask.contains(.titled) && $0.frame.width > 300 }
        return titled.first { ($0.title == AppIdentity.displayName) == useMain }
    }

    private static func play(_ step: String, into directory: URL) async {
        let parts = step.split(separator: ":", maxSplits: 1).map(String.init)
        let numbers = (parts.count > 1 ? parts[1] : "").split(separator: ",").compactMap { Double($0) }
        switch parts.first {
        case "main":
            useMain = true
        case "wait":
            try? await Task.sleep(for: .milliseconds(Int((numbers.first ?? 1) * 1000)))
        case "click", "double":
            guard numbers.count == 2, let window else { return note("\(step): no window") }
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            let point = NSPoint(x: numbers[0], y: window.frame.height - numbers[1])
            note("\(step): on \(hit(point, in: window))")
            for count in 1 ... (parts[0] == "double" ? 2 : 1) {
                for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
                    let event = NSEvent.mouseEvent(
                        with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                        windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: count, pressure: type == .leftMouseDown ? 1 : 0
                    )
                    if let event { window.sendEvent(event) }
                    try? await Task.sleep(for: .milliseconds(40))
                }
            }
        case "wheel":
            guard numbers.count == 3, let window else { return note("\(step): no window") }
            let point = NSPoint(x: numbers[0], y: window.frame.height - numbers[1])
            guard let view = window.contentView?.superview?.hitTest(point),
                  let wheel = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: Int32(numbers[2]), wheel2: 0, wheel3: 0),
                  let event = NSEvent(cgEvent: wheel)
            else { return note("\(step): nothing there") }
            let before = offsets(above: view)
            // To the view under the point, as the window does with a wheel event of its own.
            view.scrollWheel(with: event)
            try? await Task.sleep(for: .milliseconds(500))
            note("\(step): on \(hit(point, in: window)); scroll views from the inside out, before \(before), after \(offsets(above: view))")
        case "key":
            guard let code = numbers.first, let window else { return note("\(step): no window") }
            let event = NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber,
                context: nil, characters: "", charactersIgnoringModifiers: "", isARepeat: false, keyCode: UInt16(code)
            )
            if let event { NSApp.sendEvent(event) }
            note(step)
        case "width":
            guard let width = numbers.first, let window else { return note("\(step): no window") }
            var frame = window.frame
            frame.size.width = width
            window.setFrame(frame, display: true, animate: false)
            note("\(step): the window is \(Int(window.frame.width)) wide")
        case "fullscreen":
            guard let window else { return note("\(step): no window") }
            window.toggleFullScreen(nil)
            note(step)
        case "report":
            guard let window else { return note("\(step): no window") }
            let height = window.frame.height
            let buttons = [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].map { type -> String in
                guard let button = window.standardWindowButton(type), let above = button.superview else { return "none" }
                let frame = above.convert(button.frame, to: nil)
                return "x \(Int(frame.minX)) to \(Int(frame.maxX)), y \(Int(height - frame.maxY)) to \(Int(height - frame.minY))\(button.isHidden || above.isHidden ? " hidden" : "")"
            }
            let bar = Int(height - window.contentLayoutRect.maxY)
            note("report: window \(Int(window.frame.width)) by \(Int(height)), full screen \(window.styleMask.contains(.fullScreen)), strip along the top \(bar); "
                + "buttons from the top left: close \(buttons[0]); minimise \(buttons[1]); zoom \(buttons[2])")
        case "snap":
            snaps += 1
            let folder = directory.appendingPathComponent("snap-\(snaps)", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            DebugSupport.snapshot(into: folder)
            note("snap \(snaps)")
        default:
            note("\(step): not a step")
        }
    }

    private static func hit(_ point: NSPoint, in window: NSWindow) -> String {
        guard let view = window.contentView?.superview?.hitTest(point) else { return "nothing" }
        return String(String(describing: type(of: view)).prefix(60))
    }

    /// How far each scroll view around a view has scrolled, from the innermost outwards.
    private static func offsets(above view: NSView) -> [Int] {
        var found: [Int] = []
        var current: NSView? = view
        while let here = current {
            if let scroll = here as? NSScrollView { found.append(Int(scroll.documentVisibleRect.minY)) }
            current = here.superview
        }
        return found
    }
}
