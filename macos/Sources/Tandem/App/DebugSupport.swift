import AppKit
import SwiftUI
import TandemCore

/// Development helper. With `TANDEM_DEBUG_DIR` set, the app writes its pairing link
/// there and saves its own windows as PNG whenever it receives SIGUSR1. That is how
/// the interface gets checked without needing screen-recording permission.
///
/// A few more variables choose what is on screen, so a snapshot can reach places a
/// person would click to: `TANDEM_DEBUG_PAGE=shared` or `files` or `files:<device name>`, `TANDEM_DEBUG_SETTINGS=<section>`
/// and `TANDEM_DEBUG_PANEL=1` (the menu bar panel in an ordinary window).
/// `TANDEM_DEBUG_CLIPBOARD=seed` fills the clipboard history with samples and `=panel` also opens the quick panel,
/// which then stays open when it loses the focus; `TANDEM_DEBUG_PAGE=clipboard` opens the page of the history.
/// `TANDEM_DEBUG_LIVE=<file>` opens a window of the phone's screen or camera fed from a recorded stream and writes
/// `live-window.png`, `live-frame.png` and `live-report.txt` (see `LiveDebug`).
/// `TANDEM_DEBUG_LIVE_SELFTEST=1` runs the checks of the live video logic and writes `selftest.txt` (see `LiveSelfTest`).
/// `TANDEM_DEBUG_NO_WINDOW=1` closes the main window a few seconds after the start, which is
/// how the app runs most of the day and the state to measure its cost in. SIGUSR2 closes it
/// at any moment, like the red button.
@MainActor
enum DebugSupport {
    private static var signalSource: DispatchSourceSignal?
    private static var closeSource: DispatchSourceSignal?
    private static var panelWindow: NSWindow?
    private static var settingsWindow: NSWindow?

    /// Glass draws nothing but its own shape in a snapshot. With this set, the live windows use a plain material
    /// instead, so the text on it can be read in the PNG.
    static var flatGlass: Bool { variable("TANDEM_DEBUG_FLAT_GLASS") != nil }

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

        // `TANDEM_DEBUG_SCREEN=encode`: runs the screen encoder against a drawn picture and decodes the result, then quits.
        if variable("TANDEM_DEBUG_SCREEN") == "encode" { ScreenDebug.run(into: directory) }

        if let mode = variable("TANDEM_DEBUG_CLIPBOARD") {
            seedClipboard()
            if mode == "panel" {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { ClipboardPanelController.shared.show() }
            }
        }
        if variable("TANDEM_DEBUG_SETTINGS") != nil {
            // In a window of its own rather than through the Settings scene, which only opens for an app that is in front.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showSettingsWindow() }
        }
        // `TANDEM_DEBUG_QUICKSHARE=1`: the cards of an incoming Quick Share transfer.
        if variable("TANDEM_DEBUG_QUICKSHARE") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { QuickShare.shared.debugShow() }
        }
        // `TANDEM_DEBUG_ARRANGE=1`: the arrangement of the screens, with made up computers (one placed, two waiting).
        if variable("TANDEM_DEBUG_ARRANGE") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showArrangementWindow() }
        }
        // `TANDEM_DEBUG_DEVICEPAGE=linux|android`: the page of a made up device, for the layout of its buttons.
        if let kind = variable("TANDEM_DEBUG_DEVICEPAGE") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showDevicePageWindow(kind) }
        }
        // `TANDEM_DEBUG_AWAY=1`: the pill that stays while the pointer is on another computer.
        if variable("TANDEM_DEBUG_AWAY") != nil {
            let carried = variable("TANDEM_DEBUG_AWAY") == "carry"
                ? PointerAwayPill.Carried(title: "IMG_2041.jpg", symbol: "doc", image: NSWorkspace.shared.icon(for: .jpeg))
                : nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { PointerAwayPill.show(device: "Linux Laptop", carrying: carried) }
            if carried != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 6) { PointerAwayPill.dropped() } }
        }
        if variable("TANDEM_DEBUG_PANEL") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { showPanelWindow() }
        }
        // `TANDEM_DEBUG_INSERT=choose|which|waiting|receiving|done|ready|refused|offline|timeout|cancelled`: the Insert from phone panel.
        if let stage = variable("TANDEM_DEBUG_INSERT") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { InsertFromPhone.shared.debugShow(stage) }
        }
        // `TANDEM_DEBUG_LIVE=<recorded stream>`: a window of the phone's screen or camera, fed from a file (see LiveDebug).
        if variable("TANDEM_DEBUG_SOUNDRING") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                let failures = SoundRingSelfTest.run()
                let text = (failures.isEmpty ? "all passed" : "FAILED") + "\n" + failures.map { "failed: \($0)\n" }.joined()
                try? text.write(to: directory.appendingPathComponent("soundring.txt"), atomically: true, encoding: .utf8)
                NSApp.terminate(nil)
            }
        }
        if variable("TANDEM_DEBUG_LIVE_SELFTEST") != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { LiveSelfTest.run(directory: directory) }
        }
        if let path = variable("TANDEM_DEBUG_LIVE") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { LiveDebug.run(path: path, directory: directory) }
        }
        if variable("TANDEM_DEBUG_NO_WINDOW") != nil {
            // The app as it runs most of the day, in the menu bar with no window, for measuring what it costs.
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { closeWindows() }
        }
    }

    private static func closeWindows() {
        for window in NSApp.windows where window.isVisible && window.styleMask.contains(.titled) && !(window.delegate is LiveWindowController) { window.close() }
    }

    static func initialSelection(devices: [TandemDevice]) -> SidebarSelection? {
        guard let page = variable("TANDEM_DEBUG_PAGE") else { return nil }
        if page == "shared" { return .shared }
        if page == "clipboard" { return .clipboard }
        // `files` is the files of the first device that has some, `files:Name` the ones of that device.
        if page.hasPrefix("files") {
            let wanted = page.dropFirst("files".count).dropFirst()
            let found = devices.first { $0.caps.contains("files") && (wanted.isEmpty || $0.name == wanted) }
            return found.map { .files($0.id) }
        }
        return nil
    }

    /// The folder of a device's files to start in, and the row to have chosen there.
    static var filesPath: String? { variable("TANDEM_DEBUG_FILES_PATH") }
    static var filesSelection: String? { variable("TANDEM_DEBUG_FILES_SELECT") }
    /// Opens the files as a drive in Finder as soon as the page is there.
    static var mountDrive: Bool { variable("TANDEM_DEBUG_MOUNT") != nil }

    /// While a debug run shows the quick panel it must not close when the app is not the one in front.
    static var keepsPanelOpen: Bool { variable("TANDEM_DEBUG_CLIPBOARD") == "panel" }

    /// Samples for the clipboard history, so a snapshot has something to show.
    private static func seedClipboard() {
        let history = ClipboardHistory.shared
        history.clear(keepingPinned: false)
        let now = Date()
        func item(_ minutes: Double, _ kind: ClipItem.Kind, _ text: String, app: String?, name: String?, pinned: Bool = false, device: String? = nil, symbol: String? = nil) -> ClipItem {
            ClipItem(
                id: UUID(), kind: kind, preview: text, text: text, characters: text.count, lines: text.reduce(1) { $1.isNewline ? $0 + 1 : $0 },
                imageWidth: 0, imageHeight: 0, imageBytes: 0, date: now.addingTimeInterval(-minutes * 60), pinned: pinned,
                app: app, appName: name, device: device, devicePlatform: symbol, hash: ClipboardHistory.digest(Data(text.utf8))
            )
        }
        let samples = [
            item(0.2, .text, "Tandem sends your clipboard between your devices without a server", app: "com.apple.Notes", name: "Notes"),
            item(3, .link, "https://github.com/Marukiee/Tandem/releases/latest", app: "com.apple.Safari", name: "Safari"),
            item(9, .text, "Pixel 9 said: pick up bread, milk and the parcel from the neighbours", app: nil, name: nil, device: "Pixel 9", symbol: "iphone"),
            item(25, .text, "func paste(number: Int) {\n    let list = items\n    guard number >= 1, number <= list.count else { return }\n    onFinish?(list[number - 1], pastes)\n}", app: "com.apple.Terminal", name: "Terminal"),
            item(70, .text, "NL91 ABNA 0417 1643 00", app: "com.apple.Safari", name: "Safari", pinned: true),
            item(240, .link, "https://developer.apple.com/documentation/swiftui/glasseffectcontainer", app: "com.apple.Safari", name: "Safari"),
            item(1500, .text, "Dear Mark,\n\nThanks for the quick reply. The invoice is attached, and the delivery is planned for next Tuesday between nine and twelve.\n\nKind regards,\nSam", app: "com.apple.TextEdit", name: "TextEdit"),
        ]
        for sample in samples { history.debugInsert(sample) }

        // A picture, drawn here.
        let size = NSSize(width: 1280, height: 720)
        let image = NSImage(size: size, flipped: false) { rect in
            NSGradient(colors: [NSColor(Palette.indigo), NSColor(Palette.rose)])?.draw(in: rect, angle: 35)
            NSColor.white.withAlphaComponent(0.85).setFill()
            NSBezierPath(roundedRect: NSRect(x: 380, y: 210, width: 520, height: 300), xRadius: 60, yRadius: 60).fill()
            return true
        }
        if let tiff = image.tiffRepresentation, let prepared = ClipImage.prepare(data: tiff, isPNG: false) {
            let id = UUID()
            try? prepared.png.write(to: history.imageURL(id))
            if let thumbnail = prepared.thumbnail { try? thumbnail.write(to: history.thumbnailURL(id)) }
            history.debugInsert(ClipItem(
                id: id, kind: .image, preview: "", text: nil, characters: 0, lines: 0,
                imageWidth: prepared.width, imageHeight: prepared.height, imageBytes: prepared.png.count,
                date: now.addingTimeInterval(-45 * 60), pinned: false, app: "com.apple.Preview", appName: "Preview",
                device: nil, devicePlatform: nil, hash: prepared.hash
            ))
        }
    }

    static func initialSettingsSection() -> SettingsSection? {
        variable("TANDEM_DEBUG_SETTINGS").flatMap { SettingsSection(rawValue: $0) }
    }

    private static func showDevicePageWindow(_ kind: String) {
        let status = TandemStatus(battery: nil, network: nil, hotspot: nil, dnd: nil, locked: nil, freeStorage: nil, asleep: nil, wakeMac: nil, muted: nil)
        let phone = kind == "android"
        let device = TandemDevice(
            id: "debug", name: phone ? "Pixel 9" : "Linux Laptop", platform: phone ? .android : .linux, online: true, route: nil, rttMs: 7, status: status,
            appVersion: "0.1.76", caps: phone ? ["files", "media.screen", "media.camera"] : ["screen.host"], vouchedByRemoved: false, clipboardEnabled: true,
            autoAccept: true, notificationsEnabled: true, ble: false
        )
        let host = NSHostingView(rootView: DeviceDetail(device: device).environment(EngineModel.shared).frame(width: 760, height: 640))
        let window = NSWindow(contentRect: NSRect(x: 120, y: 120, width: 760, height: 640), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Device page (debug)"
        window.contentView = host
        window.makeKeyAndOrderFront(nil)
    }

    private static func showArrangementWindow() {
        let status = TandemStatus(battery: nil, network: nil, hotspot: nil, dnd: nil, locked: nil, freeStorage: nil, asleep: nil, wakeMac: nil, muted: nil)
        func made(_ id: String, _ name: String, _ platform: TandemPlatform, online: Bool = true) -> TandemDevice {
            TandemDevice(id: id, name: name, platform: platform, online: online, route: nil, rttMs: nil, status: status, appVersion: nil, caps: [],
                         vouchedByRemoved: false, clipboardEnabled: true, autoAccept: false, notificationsEnabled: true, ble: false)
        }
        let devices = [made("a", "Linux Laptop", .linux), made("b", "Windows PC", .windows), made("c", "Old Mac", .macOs, online: false)]
        let share = PointerShare.shared
        share.debugSizes(["a": CGSize(width: 1920, height: 1080), "b": CGSize(width: 2560, height: 1440)])
        share.layout = ["a": PointerShare.Placement(edge: "right", offset: 120)]
        let host = NSHostingView(rootView: ArrangementEditor(share: share, devices: devices).padding(20).frame(width: 640))
        let window = NSWindow(contentRect: NSRect(x: 120, y: 120, width: 640, height: 10), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Arrangement (debug)"
        window.contentView = host
        window.setContentSize(host.fittingSize)
        window.makeKeyAndOrderFront(nil)
    }

    private static func showSettingsWindow() {
        let host = NSHostingView(rootView: SettingsView().environment(EngineModel.shared))
        let window = NSWindow(
            contentRect: NSRect(x: 120, y: 120, width: 560, height: 10),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Settings (debug)"
        window.contentView = host
        window.setContentSize(host.fittingSize)
        window.isReleasedWhenClosed = false
        window.makeKeyAndOrderFront(nil)
        settingsWindow = window
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
        // The video layer is not drawn by cacheDisplay; the live windows put a plain picture in its place meanwhile.
        LiveManager.shared.prepareSnapshots()
        defer { LiveManager.shared.finishSnapshots() }
        for (index, window) in NSApp.windows.enumerated() where window.isVisible && window.contentView != nil {
            // A window without a title bar has no frame around its content that is worth drawing.
            guard let view = (window.styleMask.contains(.titled) ? window.contentView?.superview : nil) ?? window.contentView,
                  let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)
            else { continue }
            view.cacheDisplay(in: view.bounds, to: rep)
            if let png = rep.representation(using: .png, properties: [:]) {
                try? png.write(to: directory.appendingPathComponent("window-\(index).png"))
            }
        }
    }
}
