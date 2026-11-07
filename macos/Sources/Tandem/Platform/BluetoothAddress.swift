import Foundation
import IOBluetooth

/// This Mac's own Bluetooth address, for a watch that wants to be its mouse straight over Bluetooth. The phone passes it on to the watch,
/// so tapping the mouse on the watch connects to this Mac without choosing it in a list of Bluetooth devices.
enum BluetoothAddress {
    /// "AA:BB:CC:DD:EE:FF", or nil without Bluetooth.
    static func mine() -> String? {
        guard let raw = IOBluetoothHostController.default()?.addressAsString(), !raw.isEmpty else { return nil }
        let address = raw.replacingOccurrences(of: "-", with: ":").uppercased()
        return address.range(of: "^([0-9A-F]{2}:){5}[0-9A-F]{2}$", options: .regularExpression) != nil ? address : nil
    }
}
