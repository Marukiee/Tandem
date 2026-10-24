import SwiftUI
import TandemCore

/// "Share Wi-Fi" on the page of a device that can join a network: hands it the name and password of this Mac's Wi-Fi. Over a
/// Bluetooth link too, since a device that is not on the network is often only reachable that way.
struct WifiShareButton: View {
    let device: TandemDevice

    var body: some View {
        let reachable = device.online || device.ble
        GlassActionButton(title: "Share Wi-Fi", symbol: "wifi") { WifiShare.shared.share(with: device) }
            .disabled(!reachable)
            .opacity(reachable ? 1 : 0.5)
            .help("Send the name and password of this Mac's Wi-Fi to \(device.name)")
    }
}
