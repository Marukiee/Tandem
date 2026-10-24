import AppKit

/// The column of floating cards at the top right of the screen: questions, calls, the choice of a number. Each placed under the one
/// before, the newest on top, and the others slide up when one goes. Placed each on its own they ended up over each other.
@MainActor
final class FloatingStack {
    static let shared = FloatingStack()

    /// The room a panel keeps around its card for the shadow, which is no part of the card: the column lines up the cards themselves,
    /// or the gaps between them came out as wide as two shadows. `FloatingCard` keeps 12 above, 36 under and 28 at the sides.
    struct Room {
        var top: CGFloat = 12
        var bottom: CGFloat = 36
        var side: CGFloat = 28
        static let floatingCard = Room()
        static let none = Room(top: 0, bottom: 0, side: 0)
    }

    private var panels: [(panel: NSPanel, room: Room)] = []
    private static let gap: CGFloat = 8
    private static let margin: CGFloat = 10

    /// Puts `panel` at the top of the column and shows it. Showing one that is there already moves it to the top.
    func show(_ panel: NSPanel, room: Room = .floatingCard, key: Bool = false) {
        panels.removeAll { $0.panel === panel }
        panels.insert((panel, room), at: 0)
        layout(animated: true, appearing: panel)
        if key { panel.makeKeyAndOrderFront(nil) } else { panel.orderFrontRegardless() }
    }

    /// Takes `panel` out of the column; the ones under it slide up into its place.
    func remove(_ panel: NSPanel) {
        guard panels.contains(where: { $0.panel === panel }) else { return }
        panels.removeAll { $0.panel === panel }
        layout(animated: true, appearing: nil)
    }

    /// The size of a card changed (its content grew): the ones under it make room.
    func relayout() {
        layout(animated: true, appearing: nil)
    }

    private func layout(animated: Bool, appearing: NSPanel?) {
        guard let screen = NSScreen.main else { return }
        let area = screen.visibleFrame
        // Where the next card's top edge goes; its panel sits higher by the room above the card.
        var top = area.maxY - Self.margin
        for (panel, room) in panels {
            let origin = NSPoint(x: area.maxX - Self.margin - panel.frame.width + room.side, y: top + room.top - panel.frame.height)
            top -= panel.frame.height - room.top - room.bottom + Self.gap
            // A new card starts where it ends up; only the ones that were there already move.
            if panel === appearing || !animated || !panel.isVisible {
                panel.setFrameOrigin(origin)
            } else {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.28
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.3, 1)
                    panel.animator().setFrameOrigin(origin)
                }
            }
        }
    }
}
