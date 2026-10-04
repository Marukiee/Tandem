import AppKit
import SwiftUI

/// Development helper. With `TANDEM_DEBUG_DIR` set, the app writes its pairing link
/// there and saves its own windows as PNG whenever it receives SIGUSR1. That is how
/// the interface gets checked without needing screen-recording permission.
///
/// A few more variables choose what is on screen, so a snapshot can reach places a
/// person would click to: `TANDEM_DEBUG_PAGE=shared`, `TANDEM_DEBUG_SETTINGS=<section>`
/// and `TANDEM_DEBUG_PANEL=1` (the menu bar panel in an ordinary window).
/// `TANDEM_DEBUG_NO_WINDOW=1` closes the main window a few seconds after the start, which is
/// how the app runs most of the day and the state to measure its cost in. SIGUSR2 closes it
/// at any moment, like the red button.
@MainActor
enum DebugSupport {
    private static var signalSource: DispatchSourceSignal?
    private static var closeSource: DispatchSourceSignal?
    private static var panelWindow: NSWindow?

    static var directory: URL? {
        ProcessInfo.processInfo.environment["TANDEM_DEBUG_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
    }

    private static func variable(_ name: String) -> String? {
        guard directory != nil else { return nil }
        return ProcessInfo.processInfo.environment[name]
    }

    static func install() {
        guard let directory else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        signal(SIGUSR1, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
        source.setEventHandler { snapshot(into: directory) }
        source.resume()
        signalSource = source
        signal(SIGUSR2, SIG_IGN)
        let closer = DispatchSource.makeSignalSource(signal: SIGUSR2, queue: .main)
        closer.setEventHandler { closeWindows() }
        closer.resume()
        closeSource = closer

        if variable("TANDEM_DEBUG_SETTINGS") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }
        }
        if variable("TANDEM_DEBUG_PANEL") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { showPanelWindow() }
        }
        if variable("TANDEM_DEBUG_NO_WINDOW") != nil {
            // The app as it runs most of the day, in the menu bar with no window, for measuring what it costs.
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { closeWindows() }
        }
    }

    private static func closeWindows() {
        for window in NSApp.windows where window.isVisible && window.styleMask.contains(.titled) { window.close() }
    }

    static func initialSelection(devices: [String]) -> SidebarSelection? {
        variable("TANDEM_DEBUG_PAGE") == "shared" ? .shared : nil
    }

    static func initialSettingsSection() -> SettingsSection? {
        variable("TANDEM_DEBUG_SETTINGS").flatMap { SettingsSection(rawValue: $0) }
    }

    private static func showPanelWindow() {
        let host = NSHostingView(rootView: MenuBarPanel().environment(EngineModel.shared))
        let window = NSWindow(
            contentRect: NSRect(x: 80, y: 80, width: 344, height: 10),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Menu bar panel (debug)"
        window.contentView = host
        window.setContentSize(host.fittingSize)
        window.isReleasedWhenClosed = false
        window.makeKeyAndOrderFront(nil)
        panelWindow = window
    }

    static func write(_ text: String, named name: String) {
        guard let directory else { return }
        try? text.write(to: directory.appendingPathComponent(name), atomically: true, encoding: .utf8)
    }

    static func snapshot(into directory: URL) {
        for (index, window) in NSApp.windows.enumerated() where window.isVisible && window.contentView != nil {
            guard let view = window.contentView?.superview ?? window.contentView,
                  let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)
            else { continue }
            view.cacheDisplay(in: view.bounds, to: rep)
            if let png = rep.representation(using: .png, properties: [:]) {
                try? png.write(to: directory.appendingPathComponent("window-\(index).png"))
            }
        }
    }
}
