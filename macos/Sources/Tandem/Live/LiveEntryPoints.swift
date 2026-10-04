import SwiftUI
import TandemCore

extension TandemDevice {
    /// The phone says it can show its screen. Only a phone whose app has the host side announces it.
    var canShowScreen: Bool { platform == .android && caps.contains("screen.host") }
    var canShowCamera: Bool { platform == .android && caps.contains("camera.host") }
}

/// The buttons on the page of a phone that can show its screen or its camera in a window here.
struct LiveActionRow: View {
    let device: TandemDevice

    var body: some View {
        if device.canShowScreen || device.canShowCamera {
            VStack(alignment: .leading, spacing: 8) {
                GlassEffectContainer(spacing: 12) {
                    HStack(spacing: 12) {
                        if device.canShowScreen {
                            let running = LiveManager.shared.session(of: .screen, on: device.id) != nil
                            GlassActionButton(title: "Show phone screen", symbol: "iphone.gen3", prominent: running) {
                                LiveManager.shared.start(device: device, kind: .screen)
                            }
                            .disabled(!device.online)
                            .opacity(device.online ? 1 : 0.5)
                            .help("Shows your phone's screen in a window. The phone asks first.")
                        }
                        if device.canShowCamera {
                            let running = LiveManager.shared.session(of: .camera, on: device.id) != nil
                            GlassActionButton(title: "Phone camera", symbol: "camera", prominent: running) {
                                LiveManager.shared.start(device: device, kind: .camera)
                            }
                            .disabled(!device.online)
                            .opacity(device.online ? 1 : 0.5)
                            .help("Shows your phone's camera in a window. It is not a virtual webcam.")
                        }
                        Spacer(minLength: 0)
                    }
                }
                if device.canShowCamera {
                    Text("The camera opens in a window, not as a webcam. In Zoom or OBS, share or capture that window.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                }
            }
            .animation(.tandemFade, value: device.online)
        }
    }
}

/// One button in the row of a phone in the menu bar panel, with the two things the phone can show behind it.
struct LiveMenuButton: View {
    let device: TandemDevice

    var body: some View {
        if device.canShowScreen || device.canShowCamera {
            Menu {
                if device.canShowScreen {
                    Button {
                        LiveManager.shared.start(device: device, kind: .screen)
                    } label: {
                        Label("Show phone screen", systemImage: "iphone.gen3")
                    }
                }
                if device.canShowCamera {
                    Button {
                        LiveManager.shared.start(device: device, kind: .camera)
                    } label: {
                        Label("Phone camera", systemImage: "camera")
                    }
                }
            } label: {
                Image(systemName: "rectangle.on.rectangle").frame(width: 14, height: 14)
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .fixedSize()
            .hoverGrey()
            .hoverSwell(1.12)
            .disabled(!device.online)
            .help("Show phone screen or camera")
        }
    }
}
