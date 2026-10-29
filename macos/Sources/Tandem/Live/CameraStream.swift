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

/// In Settings, Remote control, at the bottom: one row with the switch, an (i) that says how, and the address to copy while it is on.
struct CameraStreamSection: View {
    @Environment(EngineModel.self) private var model
    @AppStorage(CameraStream.enabledKey) private var enabled = false
    @LocalState private var explaining = false

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                HStack(spacing: 6) {
                    Text("Phone camera to OBS")
                    Button { explaining.toggle() } label: { Image(systemName: "info.circle").foregroundStyle(.secondary) }
                        .buttonStyle(.borderless)
                        .popover(isPresented: $explaining, arrowEdge: .bottom) {
                            Text("While a phone's camera is open in Tandem, OBS shows it too: add a Media Source, turn off Local File and paste the address as Input.")
                                .font(.callout)
                                .frame(width: 280, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(14)
                        }
                    if enabled {
                        Button("Copy address") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(CameraStream.address, forType: .string)
                            model.showToast(String(localized: "OBS address copied"))
                        }
                        .buttonStyle(.borderless)
                        .controlSize(.small)
                        .help(CameraStream.address)
                    }
                }
            }
            .onChange(of: enabled) { _, _ in CameraStream.apply(model.tandem) }
        }
        .animation(.tandem, value: enabled)
    }
}
