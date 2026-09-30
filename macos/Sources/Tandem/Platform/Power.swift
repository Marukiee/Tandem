import Foundation
import IOKit.ps
import Network
import TandemCore

/// Battery level and power source, reported to the other devices.
enum PowerReader {
    static func battery() -> TandemBattery? {
        guard
            let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
            let sources = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef]
        else { return nil }
        for source in sources {
            guard
                let description = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                let capacity = description[kIOPSCurrentCapacityKey] as? Int,
                let maximum = description[kIOPSMaxCapacityKey] as? Int, maximum > 0
            else { continue }
            let state = description[kIOPSPowerSourceStateKey] as? String
            let charging = (description[kIOPSIsChargingKey] as? Bool) ?? false
            let onAC = state == kIOPSACPowerValue
            return TandemBattery(
                level: UInt8(min(100, max(0, capacity * 100 / maximum))),
                charging: charging || onAC,
                powerSave: ProcessInfo.processInfo.isLowPowerModeEnabled
            )
        }
        return nil
    }
}

/// Watches the network and tells the engine when it changes.
@MainActor
final class NetworkWatcher {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "tandem.network")
    private var debounce: Task<Void, Never>?
    private(set) var isOnline = true
    private(set) var usesCellularHotspot = false

    var onChange: (() -> Void)?

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            let expensive = path.isExpensive
            Task { @MainActor in
                guard let self else { return }
                self.isOnline = online
                self.usesCellularHotspot = expensive
                // A network change often comes as a burst of updates. Wait for it to settle.
                self.debounce?.cancel()
                self.debounce = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(600))
                    if !Task.isCancelled { self.onChange?() }
                }
            }
        }
        monitor.start(queue: queue)
    }

    func stop() {
        monitor.cancel()
    }
}


/// The hardware address of the wired network port that is plugged in, for a Wake-on-LAN
/// packet. Only a Mac on a cable can be woken that way, so a Mac on Wi-Fi reports none.
enum WiredAddress {
    static func mac() -> String? {
        guard let text = run("/usr/sbin/networksetup", ["-listallhardwareports"]) else { return nil }
        var port = "", device = ""
        let skipped = ["wi-fi", "airport", "bluetooth", "bridge", "iphone", "ipad", "vlan", "loopback"]
        for line in text.components(separatedBy: "\n") {
            if line.hasPrefix("Hardware Port:") {
                port = line.dropFirst("Hardware Port:".count).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("Device:") {
                device = line.dropFirst("Device:".count).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("Ethernet Address:") {
                let address = line.dropFirst("Ethernet Address:".count).trimmingCharacters(in: .whitespaces)
                let name = port.lowercased()
                guard !device.isEmpty, address.count == 17, !skipped.contains(where: { name.contains($0) }) else { continue }
                if (run("/sbin/ifconfig", [device]) ?? "").contains("status: active") { return address }
            }
        }
        return nil
    }

    private static func run(_ tool: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        do { try process.run() } catch { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(data: data, encoding: .utf8)
    }
}
