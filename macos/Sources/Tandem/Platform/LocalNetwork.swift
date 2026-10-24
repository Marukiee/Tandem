import AppKit
import Network
import SwiftUI

/// Whether macOS lets Tandem use the local network. After an update it sometimes does not, while the switch under Privacy and Security
/// still looks on: every packet to another device then fails with "No route to host", and the devices never find each other. A Bonjour
/// browser for Tandem's own service tells: macOS refuses it with a policy error when the permission is off (and asks the first time).
@MainActor
enum LocalNetwork {
    /// Nil until the browser said something.
    private(set) static var allowed: Bool?
    private static var browser: NWBrowser?
    private static var warned = false

    static func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjour(type: "_tandem._udp", domain: nil), using: .udp)
        browser.stateUpdateHandler = { state in
            let now: Bool? = switch state {
            case .ready: true
            case let .waiting(error), let .failed(error): Self.isPolicy(error) ? false : nil
            default: nil
            }
            guard let now else { return }
            Task { @MainActor in changed(now) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    nonisolated private static func isPolicy(_ error: NWError) -> Bool {
        if case let .dns(code) = error { return code == DNSServiceErrorType(kDNSServiceErr_PolicyDenied) }
        return false
    }

    private static func changed(_ now: Bool) {
        let was = allowed
        allowed = now
        if !now, !warned {
            warned = true
            NetworkPrompt.shared.show()
        }
        if now, was == false {
            NetworkPrompt.shared.close()
            warned = false
        }
    }

    static func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") { NSWorkspace.shared.open(url) }
    }
}

/// Says that macOS keeps Tandem off the network, with the way to put it back.
@MainActor
final class NetworkPrompt {
    static let shared = NetworkPrompt()
    private var panel: NSPanel?

    func show() {
        close()
        let panel = KeyPanel(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 170),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let view = NSHostingView(rootView: NetworkPromptView(close: { [weak self] in self?.close() }))
        panel.contentView = view
        panel.setContentSize(view.fittingSize)
        if let screen = NSScreen.main {
            let area = screen.visibleFrame
            let top = ScreenPanels.shared.lowestEdge.map { $0 + 30 } ?? area.maxY - 10
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
        }
        panel.makeKeyAndOrderFront(nil)
        self.panel = panel
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

private struct NetworkPromptView: View {
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PromptHeader(
                symbol: "wifi.exclamationmark",
                tint: Palette.urgent,
                title: Text("Tandem cannot reach your devices"),
                message: Text("macOS has turned off Local Network for Tandem, which happens after some updates. Turn Tandem off and on again under Privacy and Security, Local Network.")
            )
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                Button("Later", action: close).buttonStyle(.tandemGlass)
                Button("Open Settings") { LocalNetwork.openSettings() }.buttonStyle(.tandemGlassProminent)
            }
            .controlSize(.large)
        }
        .floatingCard()
    }
}
