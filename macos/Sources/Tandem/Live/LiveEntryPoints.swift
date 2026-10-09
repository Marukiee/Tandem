import SwiftUI
import TandemCore

extension TandemDevice {
    /// A computer: it can show its screen, and be used from here when it allows that.
    var isComputer: Bool { platform == .macOs || platform == .windows || platform == .linux }
    /// The device says it can show its screen. Only an app that has the host side announces it.
    var canShowScreen: Bool { (platform == .android || isComputer) && caps.contains("screen.host") }
    var canShowCamera: Bool { platform == .android && caps.contains("camera.host") }
}

/// The buttons on the page of a phone that can show its screen or its camera in a window here.
struct LiveActionRow: View {
    let device: TandemDevice

    var body: some View {
        // A computer that shows its screen, like a remote desktop: look at it, and use it when it allows that.
        if device.isComputer, device.caps.contains("screen.host") {
            let ready = device.online
            let running = LiveManager.shared.session(of: .screen, on: device.id) != nil
            GlassActionButton(title: "Control this computer", symbol: "display", prominent: running, wide: true) {
                LiveManager.shared.start(device: device, kind: .screen)
            }
            .disabled(!ready)
            .opacity(ready ? 1 : 0.5)
            .help("Shows the screen of this computer in a window. It asks first, and then you can use its mouse and keyboard.")
        }
        // Always there for a phone, grey while it cannot: a phone with an older Tandem is told what to do in the tooltip.
        if device.platform == .android {
            let screenReady = device.online && device.canShowScreen
            let running = LiveManager.shared.session(of: .screen, on: device.id) != nil
            GlassActionButton(title: "Show phone screen", symbol: "iphone.gen3", prominent: running, wide: true) {
                LiveManager.shared.start(device: device, kind: .screen)
            }
            .disabled(!screenReady)
            .opacity(screenReady ? 1 : 0.5)
            .help(device.canShowScreen ? "Shows your phone's screen in a window. The phone asks first." : "Update Tandem on the phone to version 0.1.38 or newer")

            let cameraReady = device.online && device.canShowCamera
            let cameraRunning = LiveManager.shared.session(of: .camera, on: device.id) != nil
            GlassActionButton(title: "Phone camera", symbol: "camera", prominent: cameraRunning, wide: true) {
                LiveManager.shared.start(device: device, kind: .camera)
            }
            .disabled(!cameraReady)
            .opacity(cameraReady ? 1 : 0.5)
            .help(device.canShowCamera ? "Shows your phone's camera in a window. It is a window and not a virtual webcam: share or capture it in Zoom or OBS." : "Update Tandem on the phone to version 0.1.38 or newer")
        }
    }
}

/// One button in the row of a phone in the menu bar panel, with the two things the phone can show behind it.
struct LiveMenuButton: View {
    let device: TandemDevice
    var onHover: ((Bool) -> Void)?

    var body: some View {
        if device.canShowScreen || device.canShowCamera {
            RoundIconMenu(symbol: "rectangle.on.rectangle", size: 28, onHover: onHover) {
                if device.canShowScreen {
                    Button {
                        LiveManager.shared.start(device: device, kind: .screen)
                    } label: {
                        if device.isComputer {
                            Label("Control this computer", systemImage: "display")
                        } else {
                            Label("Show phone screen", systemImage: "iphone.gen3")
                        }
                    }
                }
                if device.canShowCamera {
                    Button {
                        LiveManager.shared.start(device: device, kind: .camera)
                    } label: {
                        Label("Phone camera", systemImage: "camera")
                    }
                }
            }
            .disabled(!device.online)
        }
    }
}
