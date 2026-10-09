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
    private static var dropTask: Task<Void, Never>?

    /// What goes along with the pointer: the thing that was being dragged when it went over.
    struct Carried {
        let title: String
        let symbol: String
        let image: NSImage?
    }

    static func show(device: String, carrying: Carried? = nil) {
        hideTask?.cancel()
        dropTask?.cancel()
        let panel = panel ?? makePanel()
        self.panel = panel
        place(panel)
        state.device = device
        state.dropped = false
        if !panel.isVisible { panel.orderFrontRegardless() }
        state.visible = true
        // The chip comes a moment after the pill, as if it were let go from the pointer that just went over.
        if let carrying {
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(120))
                state.carried = carrying
            }
        } else {
            state.carried = nil
        }
    }

    /// The button came up over there: what was carried is put down, and says so for a moment.
    static func dropped() {
        guard state.carried != nil else { return }
        state.dropped = true
        dropTask?.cancel()
        dropTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.2))
            guard !Task.isCancelled else { return }
            state.carried = nil
            state.dropped = false
        }
    }

    static func hide() {
        guard let panel, state.visible else { return }
        state.visible = false
        state.carried = nil
        state.dropped = false
        dropTask?.cancel()
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
            contentRect: NSRect(x: 0, y: 0, width: 620, height: 190),
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
    var carried: PointerAwayPill.Carried?
    var dropped = false
}

private struct PointerAwayView: View {
    let state: PillState

    var body: some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)
            if state.visible, let carried = state.carried {
                CarriedChip(carried: carried, device: state.device, dropped: state.dropped)
                    .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.8)))
            }
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
                .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.92)))
            }
        }
        .padding(.bottom, 24)
        .frame(width: 620, height: 190, alignment: .bottom)
        .animation(.tandemSpringy, value: state.visible)
        .animation(.tandemSpringy, value: state.carried?.title)
        .animation(.tandemSpringy, value: state.dropped)
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

/// What went along over the edge: a picture or icon, its name, and where it will be put down, then that it was.
private struct CarriedChip: View {
    let carried: PointerAwayPill.Carried
    let device: String
    let dropped: Bool

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                if let image = carried.image {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: carried.symbol)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.indigo)
                }
            }
            .frame(width: 34, height: 34)
            .background(Color.primary.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(carried.title)
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)
                    .frame(maxWidth: 260, alignment: .leading)
                Text(dropped ? "Put down on \(device)" : "Let go to put it down on \(device)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .contentTransition(.opacity)
            }
            if dropped {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.green)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, 8)
        .glassEffect(.regular, in: .rect(cornerRadius: 18, style: .continuous))
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }
}
