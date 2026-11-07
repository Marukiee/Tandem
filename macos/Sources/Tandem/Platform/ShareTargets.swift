import Foundation
import Security
import TandemCore

/// The devices for "Tandem" in the Share menu (macos/ShareExtension). The extension is sandboxed and cannot see the circle, so the app
/// leaves the list in the app group they share, with a key of this run: a `tandem://send` that carries it comes from that sheet, where the
/// person chose the device, and is sent at once. Any other page or app could open such a link too, so without the key the app asks.
@MainActor
final class ShareTargets {
    static let shared = ShareTargets()

    let key = UUID().uuidString

    private var last: Data?

    /// Only when the app has the group (a Developer ID build with its profile). Without it macOS would ask the person, and the extension
    /// cannot read it either, so it hands the files over and the app asks.
    private lazy var file: URL? = {
        guard Self.hasGroup, let bundle = Bundle.main.bundleIdentifier else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "X8Y2M32MNQ.\(bundle)")?
            .appendingPathComponent("share.json")
    }()

    private static let hasGroup: Bool = {
        guard let task = SecTaskCreateFromSelf(nil),
              let value = SecTaskCopyValueForEntitlement(task, "com.apple.security.application-groups" as CFString, nil) as? [String]
        else { return false }
        return !value.isEmpty
    }()

    /// Called whenever the devices change, and with `running: false` when the app quits. Writes only what is new.
    func update(_ devices: [TandemDevice], running: Bool = true) {
        guard let file else { return }
        let list: [String: Any] = [
            "key": key,
            "running": running,
            "devices": devices.map { ["id": $0.id, "name": $0.name, "symbol": $0.platform.symbol, "online": running && $0.online] },
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: list, options: [.sortedKeys]), data != last else { return }
        last = data
        try? data.write(to: file, options: [.atomic])
    }

    func trusts(_ key: String?) -> Bool { key == self.key }
}
