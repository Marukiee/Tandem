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
                charging: charging || onAC && capacity < maximum,
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
