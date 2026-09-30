import AppKit
import SwiftUI

@main
struct TandemApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @LocalState private var model = EngineModel.shared

    var body: some Scene {
        Window(AppIdentity.displayName, id: "main") {
            MainWindow()
                .environment(model)
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
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let services = ServiceProvider()

    func applicationDidFinishLaunching(_ notification: Notification) {
        let showInDock = UserDefaults.standard.object(forKey: "showInDock") as? Bool ?? true
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
        Task { @MainActor in
            DebugSupport.install()
            EngineModel.shared.start()
            Updater.shared.checkIfDue()
            Updater.shared.startPeriodicChecks()
        }
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
}

/// Asks which device to send to, when there is a choice.
@MainActor
enum DevicePicker {
    private static var actions: [BlockAction] = []

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

final class BlockAction: NSObject {
    private let block: () -> Void
    init(_ block: @escaping () -> Void) { self.block = block }
    @objc func run() { block() }
}
