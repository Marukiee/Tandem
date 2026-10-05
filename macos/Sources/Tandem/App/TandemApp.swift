import AppKit
import SwiftUI

@main
struct TandemApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @LocalState private var model = EngineModel.shared

    var body: some Scene {
        Window(AppIdentity.displayName, id: "main") {
            ReleasedWhenClosed {
                MainWindow().environment(model)
            }
            .frame(minWidth: 900, minHeight: 600)
        }
        .defaultSize(width: 1020, height: 700)
        .windowResizability(.contentMinSize)

        MenuBarExtra {
            MenuBarPanel().environment(model)
        } label: {
            MenuBarIcon().environment(model)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView().environment(model)
        }
        .commands {
            CommandGroup(after: .pasteboard) {
                Button("Clipboard History") { ClipboardPanelController.shared.show() }
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let services = ServiceProvider()
    private var terminateSignal: DispatchSourceSignal?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let showInDock = UserDefaults.standard.object(forKey: "showInDock") as? Bool ?? true
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.servicesProvider = services
        // A request to stop (from `kill`, a script, the system) is a quit like any other: the engine says goodbye and the
        // drives of phones are taken away first, instead of the process just being gone.
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        // Not from inside a block of the main queue: terminating waits for the engine to stop in a loop of its own, and
        // the work that stops it is on that same queue, which does not run while a block of it is still going.
        source.setEventHandler { RunLoop.main.perform(inModes: [.common]) { NSApp.terminate(nil) } }
        source.resume()
        terminateSignal = source
        NSUpdateDynamicServices()
        Task { @MainActor in
            DriveMount.removeLeftovers()
            DebugSupport.install()
            EngineModel.shared.start()
            InsertFromPhone.shared.install()
            ClipboardPanelController.shared.start()
            Updater.shared.checkIfDue()
            Updater.shared.startPeriodicChecks()
            if ProcessInfo.processInfo.environment["TANDEM_UPDATE_TEST_FROM"] != nil {
                Task { try? await Task.sleep(for: .seconds(4)); await Updater.shared.runTestUpdate() }
            }
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        Task { @MainActor in Updater.shared.checkIfDue() }
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        Task { @MainActor in
            for url in urls { EngineModel.shared.handle(url: url) }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        // Give the engine a moment to close its connections politely.
        Task { @MainActor in
            ClipboardHistory.shared.flush()
            await EngineModel.shared.stop()
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}

/// The "Send with Tandem" entry in the Services menu and in Finder's right-click menu.
final class ServiceProvider: NSObject {
    @objc func sendFiles(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        guard let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
              !urls.isEmpty
        else { return }
        Task { @MainActor in DevicePicker.send(urls) }
    }

    /// "Send with Quick Share" in the Services menu (in Finder: right click, Services). A real entry in the Share menu needs an
    /// app extension, which this build does not make; the Services menu is the way in that does not.
    @objc func sendQuickShare(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        guard let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
              !urls.isEmpty
        else { return }
        Task { @MainActor in DevicePicker.sendNearby(urls) }
    }

    /// "Insert from phone" in the Services menu. The picture arrives later and is pasted into the app the
    /// service was chosen in, so this returns at once and writes nothing to the pasteboard it was given.
    @objc func insertFromPhone(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        Task { @MainActor in InsertFromPhone.shared.begin() }
    }
}

/// Asks which device to send to, when there is a choice.
@MainActor
enum DevicePicker {
    private static var actions: [BlockAction] = []
    fileprivate static var quickActions: [BlockAction] = []

    static func send(_ urls: [URL]) {
        let model = EngineModel.shared
        let online = model.devices.filter(\.online)
        switch online.count {
        case 0:
            Notifier.shared.post(id: "nodevice", title: String(localized: "No device is online"), body: String(localized: "Open Tandem on your phone and try again."))
        case 1:
            model.send(urls: urls, to: [online[0].id])
        default:
            let menu = NSMenu()
            actions = []
            for device in online {
                let action = BlockAction { model.send(urls: urls, to: [device.id]) }
                actions.append(action)
                let item = NSMenuItem(title: device.name, action: #selector(BlockAction.run), keyEquivalent: "")
                item.target = action
                item.image = NSImage(systemSymbolName: device.platform.symbol, accessibilityDescription: nil)
                menu.addItem(item)
            }
            menu.addItem(.separator())
            let all = BlockAction { model.send(urls: urls, to: online.map(\.id)) }
            actions.append(all)
            let allItem = NSMenuItem(title: String(localized: "All devices"), action: #selector(BlockAction.run), keyEquivalent: "")
            allItem.target = all
            menu.addItem(allItem)
            menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
        }
    }
}

extension DevicePicker {
    /// Asks which of the devices that Quick Share found the files go to.
    static func sendNearby(_ urls: [URL]) {
        let share = QuickShare.shared
        guard share.enabled else {
            Notifier.shared.post(id: "quickshare-off", title: String(localized: "Quick Share is off"), body: String(localized: "Turn it on in the menu bar panel or in Settings, then try again."))
            return
        }
        let peers = share.peers
        guard !peers.isEmpty else {
            Notifier.shared.post(id: "quickshare-none", title: String(localized: "No devices nearby"), body: String(localized: "On the other device open Quick Share and set it to be seen by everyone."))
            return
        }
        let menu = NSMenu()
        quickActions = []
        for peer in peers {
            let action = BlockAction { QuickShare.shared.send(urls, to: peer) }
            quickActions.append(action)
            let item = NSMenuItem(title: peer.name, action: #selector(BlockAction.run), keyEquivalent: "")
            item.target = action
            item.image = NSImage(systemSymbolName: peer.kind.symbol, accessibilityDescription: nil)
            menu.addItem(item)
        }
        menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }
}

final class BlockAction: NSObject {
    private let block: () -> Void
    init(_ block: @escaping () -> Void) { self.block = block }
    @objc func run() { block() }
}
