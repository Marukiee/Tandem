import AppKit

/// The column of floating cards at the top right of the screen: questions, calls, the choice of a number. Each placed under the one
/// before, the newest on top, and the others slide up when one goes. Placed each on its own they ended up over each other.
@MainActor
final class FloatingStack {
    static let shared = FloatingStack()

    private var panels: [NSPanel] = []
    private static let gap: CGFloat = 8
    private static let margin: CGFloat = 10

    /// Puts `panel` at the top of the column and shows it. Showing one that is there already moves it to the top.
    func show(_ panel: NSPanel, key: Bool = false) {
        panels.removeAll { $0 === panel }
        panels.insert(panel, at: 0)
        layout(animated: true, appearing: panel)
        if key { panel.makeKeyAndOrderFront(nil) } else { panel.orderFrontRegardless() }
    }

    /// Takes `panel` out of the column; the ones under it slide up into its place.
    func remove(_ panel: NSPanel) {
        guard panels.contains(where: { $0 === panel }) else { return }
        panels.removeAll { $0 === panel }
        layout(animated: true, appearing: nil)
    }

    /// The size of a card changed (its content grew): the ones under it make room.
    func relayout() {
        layout(animated: true, appearing: nil)
    }

    private func layout(animated: Bool, appearing: NSPanel?) {
        guard let screen = NSScreen.main else { return }
        let area = screen.visibleFrame
        var top = area.maxY - Self.margin
        for panel in panels {
            let origin = NSPoint(x: area.maxX - panel.frame.width - Self.margin, y: top - panel.frame.height)
            top -= panel.frame.height + Self.gap
            // A new card starts where it ends up; only the ones that were there already move.
            if panel === appearing || !animated || !panel.isVisible {
                panel.setFrameOrigin(origin)
            } else {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.28
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.3, 1)
                    // `setFrame`: the animator of a window does nothing at all with `setFrameOrigin`, and the card would stay put.
                    panel.animator().setFrame(NSRect(origin: origin, size: panel.frame.size), display: true)
                }
            }
        }
    }
}
