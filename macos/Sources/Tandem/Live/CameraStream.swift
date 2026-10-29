import AppKit
import SwiftUI
import TandemCore

/// The camera of a phone looked at in a window here, passed on to OBS on this Mac as well (see `tsout.rs` in the core): in OBS a Media
/// Source with the address below shows it, without capturing the window. Off unless turned on.
enum CameraStream {
    static let enabledKey = "cameraToOBS"

    static var enabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }
    static var address: String { "udp://127.0.0.1:\(tandemCameraStreamPort())" }

    @MainActor
    static func apply(_ engine: TandemEngine?) {
        engine?.setCameraStream(port: enabled ? tandemCameraStreamPort() : 0)
    }
}

/// In Settings, Remote control: the switch and the address to give OBS.
struct CameraStreamSection: View {
    @Environment(EngineModel.self) private var model
    @AppStorage(CameraStream.enabledKey) private var enabled = false

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Phone camera to OBS")
                    Text("While a phone's camera is open here, OBS can show it too, without capturing the window.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .onChange(of: enabled) { _, _ in CameraStream.apply(model.tandem) }
            if enabled {
                LabeledContent("Address for OBS") {
                    HStack(spacing: 8) {
                        Text(verbatim: CameraStream.address).font(.body.monospaced()).textSelection(.enabled)
                        Button("Copy") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(CameraStream.address, forType: .string)
                            model.showToast(String(localized: "OBS address copied"))
                        }
                    }
                }
            }
        } header: {
            Text("Phone camera")
        } footer: {
            Text("In OBS: add a Media Source, turn off Local File and paste the address as Input. Open the phone's camera in Tandem to start the picture.")
        }
        .animation(.tandem, value: enabled)
    }
}
