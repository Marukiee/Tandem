import Foundation
import TandemCore
import WidgetKit

/// Feeds the battery widget (in the extension of `macos/ControlExtension`). The extension is sandboxed and cannot ask the engine, so this
/// writes the batteries to a small file it may read, and asks WidgetKit to draw again only when something a person would see has changed:
/// the system allows a widget only so many reloads a day.
@MainActor
final class BatteryBridge {
    static let shared = BatteryBridge()

    private var timer: Timer?
    private var written: BatteryWidgetSnapshot?
    /// The last level of each device, so one that went away stays in the widget dimmed. The engine forgets a battery when the app
    /// restarts, the file does not.
    private var known: [String: BatteryWidgetDevice] = [:]

    static var file: URL { EngineModel.supportDirectory().appendingPathComponent(BatteryWidgetSnapshot.fileName) }

    func start() {
        guard timer == nil else { return }
        if let data = try? Data(contentsOf: Self.file), let old = try? JSONDecoder().decode(BatteryWidgetSnapshot.self, from: data) {
            written = old
            for device in old.devices { known[device.id] = device }
        }
        publish()
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.publish() }
        }
    }

    private func current() -> BatteryWidgetSnapshot {
        let model = EngineModel.shared
        var devices: [BatteryWidgetDevice] = []
        if let mine = PowerReader.battery() {
            devices.append(BatteryWidgetDevice(id: "this", name: model.myName, symbol: "laptopcomputer", level: Int(mine.level), charging: mine.charging, away: false))
        }
        var others: [BatteryWidgetDevice] = []
        for device in model.devices {
            let symbol = model.deviceIcons[device.id] ?? device.platform.symbol
            if let battery = device.status.battery {
                let entry = BatteryWidgetDevice(id: device.id, name: device.name, symbol: symbol, level: Int(battery.level), charging: battery.charging && device.online, away: !device.online)
                known[device.id] = entry
                others.append(entry)
            } else if var last = known[device.id] {
                last.name = device.name
                last.symbol = symbol
                last.away = true
                last.charging = false
                others.append(last)
            }
        }
        // A device that was removed from the circle is not kept. An empty list is more likely a list not filled yet than a circle of one, and
        // would wipe what the widget last knew.
        if model.devices.isEmpty {
            others = known.values.map { var last = $0; last.away = true; last.charging = false; return last }
        } else {
            let ids = Set(model.devices.map(\.id))
            known = known.filter { ids.contains($0.key) }
        }
        others.sort { ($0.away ? 1 : 0, $0.name) < ($1.away ? 1 : 0, $1.name) }
        return BatteryWidgetSnapshot(devices: devices + others)
    }

    func publish() {
        // Before the engine has listed the devices the list is empty, which is not news.
        guard EngineModel.shared.engineHandle != nil else { return }
        let now = current()
        guard now != written else { return }
        let worthDrawing = written.map { Self.differsVisibly($0, now) } ?? true
        written = now
        if let data = try? JSONEncoder().encode(now) { try? data.write(to: Self.file, options: .atomic) }
        if worthDrawing { WidgetCenter.shared.reloadTimelines(ofKind: BatteryWidgetSnapshot.kind) }
    }

    /// A level that moves by one is written down but waits for the next timeline of the widget (every 15 minutes), unless it
    /// crosses into another colour.
    private static func differsVisibly(_ old: BatteryWidgetSnapshot, _ new: BatteryWidgetSnapshot) -> Bool {
        guard old.devices.map(\.id) == new.devices.map(\.id) else { return true }
        for (a, b) in zip(old.devices, new.devices) {
            if a.name != b.name || a.symbol != b.symbol || a.charging != b.charging || a.away != b.away { return true }
            if band(a.level) != band(b.level) || abs(a.level - b.level) >= 5 { return true }
        }
        return false
    }

    private static func band(_ level: Int) -> Int { level < 10 ? 0 : level < 20 ? 1 : level >= 100 ? 3 : 2 }
}
