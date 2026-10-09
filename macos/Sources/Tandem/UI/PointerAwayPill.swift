import AppKit
import Observation
import SwiftUI

/// The pill at the bottom of the screen while the pointer of this Mac is on another computer. It stays until the pointer is back,
/// and shows the keys that bring it back as keys, so it is clear what to press without reading a sentence.
///
/// Like the toast it lets every click through, so it never gets in the way of what is under it.
@MainActor
enum PointerAwayPill {
    private static var panel: NSPanel?
    private static let state = PillState()
    private static var hideTask: Task<Void, Never>?

    static func show(device: String) {
        hideTask?.cancel()
        let panel = panel ?? makePanel()
        self.panel = panel
        place(panel)
        state.device = device
        if !panel.isVisible { panel.orderFrontRegardless() }
        state.visible = true
    }

    static func hide() {
        guard let panel, state.visible else { return }
        state.visible = false
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            // Long enough for the spring that takes it away.
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            panel.orderOut(nil)
        }
    }

    private static func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 620, height: 120),
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
        let host = NSHostingView(rootView: PointerAwayView(state: state))
        host.sizingOptions = []
        panel.contentView = host
        return panel
    }

    /// On the screen the pointer left, above the Dock.
    private static func place(_ panel: NSPanel) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(mouse) } ?? NSScreen.main
        guard let area = screen?.visibleFrame else { return }
        panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: area.minY + 28))
    }
}

@MainActor
@Observable
private final class PillState {
    var device = ""
    var visible = false
}

private struct PointerAwayView: View {
    let state: PillState

    var body: some View {
        ZStack(alignment: .bottom) {
            if state.visible {
                HStack(spacing: 12) {
                    Image(systemName: "cursorarrow.motionlines")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Palette.indigo)
                    Text("The pointer is on \(state.device)")
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    Divider().frame(height: 16)
                    Text("Bring it back with")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        PillKey("⌃")
                        PillKey("⌥")
                        PillKey("⌘")
                        PillKey("esc", wide: true)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .glassEffect(.regular, in: .capsule)
                .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.92)))
            }
        }
        .frame(width: 620, height: 120, alignment: .bottom)
        .animation(.tandemSpringy, value: state.visible)
    }
}

/// One key of the keyboard, drawn small.
private struct PillKey: View {
    let label: String
    var wide = false

    init(_ label: String, wide: Bool = false) {
        self.label = label
        self.wide = wide
    }

    var body: some View {
        Text(label)
            .font(.system(size: wide ? 11 : 13, weight: .semibold, design: .rounded))
            .frame(minWidth: wide ? 34 : 24, minHeight: 24)
            .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.primary.opacity(0.10)))
            .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(Color.primary.opacity(0.16)))
    }
}
