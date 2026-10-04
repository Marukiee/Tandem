import AppKit
import Observation
import SwiftUI

/// A small glass toast at the bottom of the screen, for what happens while no Tandem window is open: a code that was
/// copied, a clip that was pasted. The toast in the main window only exists while that window does.
///
/// The window is always the same size and lets every click through, so it never gets in the way of what is under it.
@MainActor
enum FloatingToast {
    private static var panel: NSPanel?
    private static let state = ToastState()
    private static var hideTask: Task<Void, Never>?

    static func show(_ text: String, symbol: String = "checkmark.circle.fill") {
        let panel = panel ?? makePanel()
        self.panel = panel
        place(panel)

        state.text = text
        state.symbol = symbol
        // Already on screen: the text changes in place and the timer starts over.
        if !panel.isVisible { panel.orderFrontRegardless() }
        state.visible = true

        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.4))
            guard !Task.isCancelled else { return }
            state.visible = false
            // Long enough for the spring that takes it away.
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            panel.orderOut(nil)
        }
    }

    private static func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 560, height: 120),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.ignoresMouseEvents = true
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let host = NSHostingView(rootView: FloatingToastView(state: state))
        host.sizingOptions = []
        panel.contentView = host
        return panel
    }

    /// On the screen the pointer is on, above the Dock.
    private static func place(_ panel: NSPanel) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(mouse) } ?? NSScreen.main
        guard let area = screen?.visibleFrame else { return }
        panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: area.minY + 28))
    }
}

@MainActor
@Observable
private final class ToastState {
    var text = ""
    var symbol = "checkmark.circle.fill"
    var visible = false
}

private struct FloatingToastView: View {
    let state: ToastState

    var body: some View {
        ZStack(alignment: .bottom) {
            if state.visible {
                HStack(spacing: 9) {
                    Image(systemName: state.symbol)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Palette.indigo)
                    Text(state.text)
                        .font(.callout.weight(.medium))
                        .lineLimit(2)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .glassEffect(.regular, in: .capsule)
                .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.92)))
            }
        }
        .frame(width: 560, height: 120, alignment: .bottom)
        .animation(.tandemSpringy, value: state.visible)
    }
}
