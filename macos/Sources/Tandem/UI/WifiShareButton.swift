import SwiftUI
import TandemCore

/// "Share Wi-Fi" on the page of a device that can join a network: hands it the name and password of this Mac's Wi-Fi. Over a
/// Bluetooth link too, since a device that is not on the network is often only reachable that way.
struct WifiShareButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let reachable = device.online || device.ble
        let why = WifiShare.shared.whyNotShare(with: device)
        GlassActionButton(title: "Share Wi-Fi", symbol: "wifi") {
            if let why { model.showToast(why) } else { WifiShare.shared.share(with: device) }
        }
            .opacity(reachable && why != nil ? 0.5 : 1)
            .disabled(!reachable)
            .help("Send the name and password of this Mac's Wi-Fi to \(device.name)")
    }
}
