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
            // What is on is blue, and pressing it again turns it off.
            GlassActionButton(title: running ? "Stop controlling" : "Control computer", symbol: running ? "stop.circle.fill" : "display", prominent: running) {
                if let session = LiveManager.shared.session(of: .screen, on: device.id), !session.extend { LiveManager.shared.stop(session) }
                else { LiveManager.shared.start(device: device, kind: .screen) }
            }
            .disabled(!ready)
            .opacity(ready ? 1 : 0.5)
            .help("Shows the screen of this computer in a window. It asks first, and then you can use its mouse and keyboard.")
            // A screen that computer makes for this one, next to the ones it has: windows are moved to it with the mouse of that computer.
            if device.caps.contains("screen.extend") {
                let secondRunning = LiveManager.shared.session(of: .screen, on: device.id)?.extend == true
                // Named from where the extra screen appears: this Mac becomes a screen of that computer.
                GlassActionButton(
                    title: secondRunning ? "Stop being its screen" : "Be its second screen",
                    symbol: secondRunning ? "stop.circle.fill" : "rectangle.righthalf.inset.filled.arrow.right",
                    prominent: secondRunning
                ) {
                    if secondRunning, let session = LiveManager.shared.session(of: .screen, on: device.id) { LiveManager.shared.stop(session) }
                    else { LiveManager.shared.start(device: device, kind: .screen, extend: true) }
                }
                .disabled(!ready)
                .opacity(ready ? 1 : 0.5)
                .help("This Mac becomes an extra screen of that computer. Move windows to it with the mouse of that computer.")
            }
        }
        // This Mac's own extra screen, shown on that device: it makes the screen, the device is told to look at it. Grey until both can.
        if device.caps.contains("screen.extend.view") || device.platform == .android {
            let canHost = VirtualDisplay.isAvailable
            let ready = device.online && canHost && device.caps.contains("screen.extend.view")
            // On while this Mac shows a screen of its own on that device; pressing it then ends it.
            let extending = ScreenHost.shared.isExtending(to: device.id)
            // The other way round: this Mac gets an extra screen, shown on that device.
            GlassActionButton(
                title: extending ? "Stop extending" : "Extend my screen",
                symbol: extending ? "stop.circle.fill" : "rectangle.on.rectangle",
                prominent: extending
            ) {
                if extending { ScreenHost.shared.stop(peer: device.id) } else { ScreenHost.shared.offerExtend(to: device) }
            }
            .disabled(!ready && !extending)
            .opacity(ready || extending ? 1 : 0.5)
            .help(!device.caps.contains("screen.extend.view") ? "Update Tandem on that device to use it as a second screen" : "This Mac gets an extra screen, shown on that device. Move windows to it with your mouse.")
        }
        // Always there for a phone, grey while it cannot: a phone with an older Tandem is told what to do in the tooltip.
        if device.platform == .android {
            let screenReady = device.online && device.canShowScreen
            let running = LiveManager.shared.session(of: .screen, on: device.id) != nil
            GlassActionButton(title: running ? "Stop phone screen" : "Show phone screen", symbol: running ? "stop.circle.fill" : "iphone.gen3", prominent: running) {
                if let session = LiveManager.shared.session(of: .screen, on: device.id) { LiveManager.shared.stop(session) }
                else { LiveManager.shared.start(device: device, kind: .screen) }
            }
            .disabled(!screenReady && !running)
            .opacity(screenReady || running ? 1 : 0.5)
            .help(device.canShowScreen ? "Shows your phone's screen in a window. The phone asks first." : "Update Tandem on the phone to version 0.1.38 or newer")

            let cameraReady = device.online && device.canShowCamera
            let cameraRunning = LiveManager.shared.session(of: .camera, on: device.id) != nil
            GlassActionButton(title: cameraRunning ? "Stop camera" : "Phone camera", symbol: cameraRunning ? "stop.circle.fill" : "camera", prominent: cameraRunning) {
                if let session = LiveManager.shared.session(of: .camera, on: device.id) { LiveManager.shared.stop(session) }
                else { LiveManager.shared.start(device: device, kind: .camera) }
            }
            .disabled(!cameraReady && !cameraRunning)
            .opacity(cameraReady || cameraRunning ? 1 : 0.5)
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
                            Label("Control computer", systemImage: "display")
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
