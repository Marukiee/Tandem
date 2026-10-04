import AppKit
import SwiftUI
import TandemCore

/// The small floating panel of "Insert from phone": the choice, the wait, the arrival and the
/// result, one glass card that changes shape as the state changes.
///
/// It never takes the keyboard from the app that is being typed in, apart from the moment of the
/// choice, which wants 1, 2, 3 and Escape. A window that is not allowed to activate the app is what
/// lets the paste at the end land where the cursor was.
@MainActor
final class InsertHUDController {
    static let shared = InsertHUDController()

    /// Room around the card for its shadow.
    static let margin: CGFloat = 30

    private var panel: HUDPanel?
    private let visibility = HUDVisibility()
    private var hideTask: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?
    private var keyMonitor: Any?
    private var topEdge: CGFloat = 0
    private var centerX: CGFloat = 0

    func phaseChanged(from old: InsertFromPhone.Phase, to new: InsertFromPhone.Phase) {
        if case .idle = new {
            hide()
            return
        }
        show()
        let wantsKeys: Bool = { if case .choosing = new { true } else { false } }()
        updateKeyboard(wantsKeys)
    }

    // MARK: Showing

    private func show() {
        hideTask?.cancel()
        if panel == nil { makePanel() }
        guard let panel else { return }
        if !panel.isVisible {
            place(panel, size: CGSize(width: 392 + Self.margin * 2, height: 120 + Self.margin * 2))
            panel.orderFrontRegardless()
        }
        if !visibility.shown {
            // One turn later, so the first frame is laid out before the card comes in.
            DispatchQueue.main.async { [weak self] in
                withAnimation(.tandemSpringy) { self?.visibility.shown = true }
            }
        }
    }

    private func hide() {
        updateKeyboard(false)
        withAnimation(.tandem) { visibility.shown = false }
        hideTask?.cancel()
        hideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(380))
            guard !Task.isCancelled, let self else { return }
            self.panel?.orderOut(nil)
        }
    }

    private func makePanel() {
        let panel = HUDPanel(
            contentRect: NSRect(x: 0, y: 0, width: 392 + Self.margin * 2, height: 120 + Self.margin * 2),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.isOpaque = false
        panel.backgroundColor = .clear
        // The card draws its own shadow, which follows the glass.
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.animationBehavior = .none
        panel.title = "Insert from phone"

        let root = InsertHUDRoot(visibility: visibility) { [weak self] size in self?.fit(to: size) }
        let host = HUDHostingView(rootView: root)
        host.sizingOptions = []
        panel.contentView = host
        self.panel = panel
    }

    // MARK: Place and size

    /// Top centre of the screen the pointer is on, just under the menu bar.
    private func place(_ panel: NSPanel, size: CGSize) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouse, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        topEdge = screen.visibleFrame.maxY - 10 + Self.margin
        centerX = screen.visibleFrame.midX
        panel.setFrame(frame(for: size), display: true)
    }

    private func frame(for size: CGSize) -> NSRect {
        NSRect(x: centerX - size.width / 2, y: topEdge - size.height, width: size.width, height: size.height)
    }

    /// The card reports its size on every frame of its own animation. The window grows with it at
    /// once, so the card is never cut off, and shrinks only after it has settled.
    private func fit(to content: CGSize) {
        guard let panel, content.width > 0 else { return }
        let size = CGSize(width: content.width + Self.margin * 2, height: content.height + Self.margin * 2)
        if size.height >= panel.frame.height - 0.5 {
            panel.setFrame(frame(for: size), display: true)
        }
        settleTask?.cancel()
        settleTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            guard !Task.isCancelled, let self, let panel = self.panel else { return }
            panel.setFrame(self.frame(for: size), display: true)
        }
    }

    // MARK: Keyboard

    /// The choice takes the keys; every other state leaves the keyboard with the app in front.
    private func updateKeyboard(_ wanted: Bool) {
        guard let panel else { return }
        if wanted {
            panel.takesKey = true
            if !panel.isKeyWindow { panel.makeKeyAndOrderFront(nil) }
            if keyMonitor == nil {
                keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                    self?.key(event) == true ? nil : event
                }
            }
        } else {
            if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
            keyMonitor = nil
            if panel.takesKey {
                panel.takesKey = false
                if panel.isVisible {
                    // Ordering out gives the key back to the app that had it, ordering in again keeps the card on screen.
                    panel.orderOut(nil)
                    panel.orderFrontRegardless()
                }
            }
        }
    }

    private func key(_ event: NSEvent) -> Bool {
        let model = InsertFromPhone.shared
        guard case let .choosing(kind, device) = model.phase else { return false }
        switch event.keyCode {
        case 53: // Escape
            model.cancel()
            return true
        default:
            break
        }
        guard kind == nil, let device else { return false }
        switch event.charactersIgnoringModifiers {
        case "1": model.choose(kind: .photo, device: device)
        case "2": model.choose(kind: .document, device: device)
        case "3": model.choose(kind: .picture, device: device)
        default: return false
        }
        return true
    }

    // MARK: Debug

    var isShown: Bool { panel?.isVisible == true }
}

/// A panel that can be clicked without waking the app, and that takes the keys only when asked.
private final class HUDPanel: NSPanel {
    var takesKey = false
    override var canBecomeKey: Bool { takesKey }
    override var canBecomeMain: Bool { false }
}

private final class HUDHostingView: NSHostingView<InsertHUDRoot> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

// MARK: Content

/// Everything the panel can show, keyed so SwiftUI knows when the card turns into another one.
private extension InsertFromPhone.Phase {
    var key: String {
        switch self {
        case .idle: "idle"
        case .choosing(let kind, _): "choosing-\(kind?.rawValue ?? "any")"
        case .waiting: "waiting"
        case .receiving: "receiving"
        case .inserted(_, let pasted, _): pasted ? "inserted" : "ready"
        case .failed: "failed"
        }
    }
}

/// Whether the card is in, apart from whether the window exists, so it can come and go with a spring.
@Observable
final class HUDVisibility {
    var shown = false
}

struct InsertHUDRoot: View {
    let visibility: HUDVisibility
    let onSize: (CGSize) -> Void

    var body: some View {
        ZStack(alignment: .top) {
            if visibility.shown {
                InsertHUDCard(model: InsertFromPhone.shared, onSize: onSize)
                    .transition(.asymmetric(
                        insertion: .move(edge: .top).combined(with: .scale(scale: 0.9, anchor: .top)).combined(with: .opacity),
                        removal: .scale(scale: 0.94, anchor: .top).combined(with: .opacity)
                    ))
            }
        }
        .padding(InsertHUDController.margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}
