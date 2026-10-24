import AppKit
import SwiftUI
import TandemCore

/// The question the first time a device becomes a second screen of this Mac: on which side does it stand? Found by sound, or chosen, and
/// kept for that device unless the person says not to. A glass panel at the top of the screen, like the one for a device that wants in.
@MainActor
final class SidePrompt {
    static let shared = SidePrompt()
    private var panel: NSPanel?

    func ask(_ device: TandemDevice) {
        close()
        let panel = KeyPanel(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 200),
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
        let view = NSHostingView(rootView: SidePromptView(device: device, close: { [weak self] in self?.close() }).environment(EngineModel.shared))
        panel.contentView = view
        panel.setContentSize(view.fittingSize)
        if let screen = NSScreen.main {
            let area = screen.visibleFrame
            // Under the bar that says a device is looking at this Mac, when that is there: over it, the question had no title. Both
            // panels have clear room for their shadows (30 under the bar, 12 above this one), which may overlap: 12 points between the glass.
            let top = ScreenPanels.shared.lowestEdge.map { $0 + 30 } ?? area.maxY - 10
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
        }
        // Key, without bringing Tandem to the front: the buttons of a panel that is not key are drawn grey, as if they could not be pressed.
        panel.makeKeyAndOrderFront(nil)
        self.panel = panel
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

private struct SidePromptView: View {
    let device: TandemDevice
    let close: () -> Void
    @LocalState private var remember = true
    @LocalState private var finding = false
    @LocalState private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: "rectangle.lefthalf.inset.filled.arrow.left")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Palette.indigo)
                    .frame(width: 40, height: 40)
                    .background(Palette.indigo.opacity(0.14), in: .circle)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Where does \(device.name) stand?").font(.headline)
                    Text(note ?? String(localized: "Its screen goes on that side of this Mac, so the pointer goes over where you expect it."))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Button(action: close) {
                    Image(systemName: "xmark").font(.callout.weight(.semibold)).frame(width: 12, height: 12)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .help("Close")
            }
            HStack(spacing: 8) {
                if device.caps.contains("side.listen") {
                    Button(finding ? "Listening…" : "Find automatically") { find() }
                        .buttonStyle(.glassProminent)
                        .tint(Palette.indigo)
                        .disabled(finding)
                }
                Spacer(minLength: 0)
                Button("Left") { choose("left") }.buttonStyle(.glass).disabled(finding)
                Button("Right") { choose("right") }.buttonStyle(.glass).disabled(finding)
            }
            .controlSize(.large)
            Toggle("Remember for this device", isOn: $remember)
                .toggleStyle(.checkbox)
        }
        .padding(18)
        .frame(width: 440)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
        // Room for the whole shadow inside the panel: a shadow that reaches past the edge of the window is cut off in a straight line.
        .padding(EdgeInsets(top: 12, leading: 28, bottom: 36, trailing: 28))
    }

    private func choose(_ side: String) {
        DeviceSides.set(side, for: device.id, remember: remember)
        close()
    }

    private func find() {
        finding = true
        Task { @MainActor in
            let side = await SideFinder.find(device, remember: remember)
            finding = false
            note = SideFinder.message(side, name: device.name)
            if side == .left || side == .right {
                try? await Task.sleep(for: .seconds(1.4))
                close()
            }
        }
    }
}

/// A panel that can take the keyboard without activating the app.
private final class KeyPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}
