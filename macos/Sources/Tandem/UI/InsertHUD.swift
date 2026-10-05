import AppKit
import SwiftUI
import TandemCore

/// Where the pointer is and what is lit, shared by the window (which sees the mouse, also when the app
/// is not the active one) and the controls (which draw it).
///
/// SwiftUI's own hover does not fire in a panel of an app that is not in front, and this panel must not
/// bring the app to the front, because the paste at the end has to reach the app that was being typed in.
/// So the window watches the pointer itself and the controls say where they are.
@MainActor
@Observable
final class HUDInteraction {
    /// The tile or button that is lit: 0 to 2 are the three kinds, 10 and up are single buttons.
    var highlighted: Int?
    @ObservationIgnored var frames: [Int: CGRect] = [:]

    func pointerMoved(to point: CGPoint) {
        if let hit = frames.first(where: { $0.value.contains(point) })?.key {
            if hit != highlighted { highlighted = hit }
        } else if let current = highlighted, current >= 10 {
            // A button lets go of its light when the pointer leaves; a tile keeps it, the way a list keeps its selection.
            highlighted = nil
        }
    }
}

/// Whether the card is in, apart from whether the window exists, so it can come and go with a spring.
@MainActor
@Observable
final class HUDVisibility {
    var shown = false
}

/// The small floating panel of "Insert from phone": the choice, the wait, the arrival and the
/// result, one glass card that changes shape as the state changes.
///
/// It never takes the keyboard from the app that is being typed in, apart from the moment of the
/// choice, which wants 1, 2, 3, the arrows, Return and Escape. A window that is not allowed to activate
/// the app is what lets the paste at the end land where the cursor was.
@MainActor
final class InsertHUDController {
    static let shared = InsertHUDController()

    /// Room around the card for its shadow.
    static let margin: CGFloat = 34

    private var panel: HUDPanel?
    private let visibility = HUDVisibility()
    let interaction = HUDInteraction()
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
        if case .choosing = new, !(old.isChoosing) {
            interaction.highlighted = InsertFromPhone.lastKind.flatMap { InsertKind.allCases.firstIndex(of: $0) } ?? 0
        }
        updateKeyboard(new.isChoosing)
    }

    // MARK: Showing

    private func show() {
        hideTask?.cancel()
        if panel == nil { makePanel() }
        guard let panel else { return }
        if !panel.isVisible {
            place(panel, size: CGSize(width: InsertHUDCard.width + Self.margin * 2, height: 140 + Self.margin * 2))
            panel.orderFrontRegardless()
        }
        if !visibility.shown {
            // One turn later, so the first frame is laid out before the card comes in.
            DispatchQueue.main.async { [weak self] in
                withAnimation(.spring(response: 0.46, dampingFraction: 0.86)) { self?.visibility.shown = true }
            }
        }
    }

    private func hide() {
        updateKeyboard(false)
        // Going is quicker and quieter than coming: a short fade with a small step up, then the window leaves.
        withAnimation(.easeIn(duration: 0.16)) { visibility.shown = false }
        hideTask?.cancel()
        hideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(220))
            guard !Task.isCancelled, let self else { return }
            self.panel?.orderOut(nil)
            self.interaction.highlighted = nil
        }
    }

    private func makePanel() {
        let panel = HUDPanel(
            contentRect: NSRect(x: 0, y: 0, width: InsertHUDCard.width + Self.margin * 2, height: 140 + Self.margin * 2),
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
        panel.acceptsMouseMovedEvents = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.animationBehavior = .none
        panel.title = "Insert from phone"

        let root = InsertHUDRoot(visibility: visibility, interaction: interaction) { [weak self] size in self?.fit(to: size) }
        let host = HUDHostingView(rootView: root)
        host.sizingOptions = []
        host.onPointer = { [weak self] point in self?.interaction.pointerMoved(to: point) }
        // The hosting view is not the content view itself but sits in a plain one. A hosting view that is the content view
        // of its window follows the size of its content with the window frame, and when the card animates that happens
        // inside the layout pass of the window, where AppKit raises an exception that ended the app. Inside a plain view it
        // is only a view, and the panel is sized by `fit` alone.
        let container = NSView(frame: NSRect(origin: .zero, size: panel.frame.size))
        container.autoresizesSubviews = true
        host.frame = container.bounds
        host.autoresizingMask = [.width, .height]
        container.addSubview(host)
        panel.contentView = container
        self.panel = panel
    }

    // MARK: Place and size

    /// Top centre of the screen the pointer is on, just under the menu bar.
    private func place(_ panel: NSPanel, size: CGSize) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouse, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        topEdge = screen.visibleFrame.maxY - 8 + Self.margin
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
            // One turn later: the card reports its size while the window is being laid out, and a window must not be
            // resized in the middle of that.
            DispatchQueue.main.async { [weak self] in
                guard let self, let panel = self.panel else { return }
                panel.setFrame(self.frame(for: size), display: true)
            }
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
        if event.keyCode == 53 { // Escape
            model.cancel()
            return true
        }
        guard kind == nil, let device else { return false }
        let kinds = InsertKind.allCases
        switch event.keyCode {
        case 123: // left
            interaction.highlighted = max(0, (interaction.highlighted ?? 0) - 1)
            return true
        case 124: // right
            interaction.highlighted = min(kinds.count - 1, (interaction.highlighted ?? -1) + 1)
            return true
        case 36, 76, 49: // return, enter, space
            model.choose(kind: kinds[interaction.highlighted ?? 0], device: device)
            return true
        default:
            break
        }
        guard let number = event.charactersIgnoringModifiers.flatMap({ Int($0) }), (1...kinds.count).contains(number) else { return false }
        model.choose(kind: kinds[number - 1], device: device)
        return true
    }

    // MARK: Debug

    var isShown: Bool { panel?.isVisible == true }

    /// Lights a tile, for the snapshot harness.
    func debugHighlight(_ index: Int?) { interaction.highlighted = index }
}

/// A panel that can be clicked without waking the app, and that takes the keys only when asked.
private final class HUDPanel: NSPanel {
    var takesKey = false
    override var canBecomeKey: Bool { takesKey }
    override var canBecomeMain: Bool { false }
}

/// Hosts the card and reports the pointer in the coordinates SwiftUI draws in (origin top left).
private final class HUDHostingView: NSHostingView<InsertHUDRoot> {
    var onPointer: ((CGPoint) -> Void)?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        for area in trackingAreas where area.owner === self { removeTrackingArea(area) }
        addTrackingArea(NSTrackingArea(
            rect: bounds,
            options: [.mouseMoved, .mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self
        ))
    }

    override func mouseMoved(with event: NSEvent) {
        super.mouseMoved(with: event)
        let point = convert(event.locationInWindow, from: nil)
        onPointer?(CGPoint(x: point.x, y: isFlipped ? point.y : bounds.height - point.y))
    }

    override func mouseExited(with event: NSEvent) {
        super.mouseExited(with: event)
        onPointer?(CGPoint(x: -1000, y: -1000))
    }
}

private extension InsertFromPhone.Phase {
    var isChoosing: Bool { if case .choosing = self { true } else { false } }
}

struct InsertHUDRoot: View {
    let visibility: HUDVisibility
    let interaction: HUDInteraction
    let onSize: (CGSize) -> Void

    var body: some View {
        ZStack(alignment: .top) {
            if visibility.shown {
                InsertHUDCard(model: InsertFromPhone.shared, onSize: onSize)
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.9, anchor: .top).combined(with: .offset(y: -10)).combined(with: .opacity),
                        removal: .offset(y: -6).combined(with: .opacity)
                    ))
            }
        }
        .padding(InsertHUDController.margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .coordinateSpace(name: HUDSpace.name)
        .environment(interaction)
    }
}

enum HUDSpace {
    static let name = "hud"
}
