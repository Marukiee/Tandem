import AppKit
import SwiftUI

/// A page whose big title slides up into the toolbar as the page scrolls.
///
/// The page reports how far it has scrolled as a progress from 0 to 1. The title in
/// the page fades out and drifts up with it, and the title in the toolbar fades in
/// from below, so the two read as one thing changing places.
extension View {
    /// Keeps `progress` in step with the scroll offset of the scroll view around this.
    func trackScrollHandoff(_ progress: Binding<CGFloat>, start: CGFloat = 36, distance: CGFloat = 46) -> some View {
        onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top
        } action: { _, offset in
            let raw = min(1, max(0, (offset - start) / distance))
            // Smoothstep, so the handoff eases in and out instead of starting abruptly.
            let eased = raw * raw * (3 - 2 * raw)
            if abs(eased - progress.wrappedValue) > 0.004 { progress.wrappedValue = eased }
        }
    }

    /// The title inside the page, leaving as the toolbar title arrives.
    func handoffSource(_ progress: CGFloat) -> some View {
        modifier(HandoffSource(progress: progress))
    }
}

private struct HandoffSource: ViewModifier {
    let progress: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(1 - min(1, progress * 1.5))
            .offset(y: reduceMotion ? 0 : -progress * 10)
            .blur(radius: reduceMotion ? 0 : progress * 2)
    }
}

/// The title as it appears in the toolbar, where the window title used to be.
struct HandoffTitle<Content: View>: View {
    let progress: CGFloat
    @ViewBuilder var content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        content
            .opacity(progress)
            .offset(y: reduceMotion ? 0 : (1 - progress) * 12)
            .scaleEffect(reduceMotion ? 1 : 0.92 + 0.08 * progress, anchor: .leading)
            .blur(radius: reduceMotion ? 0 : (1 - progress) * 3)
            .allowsHitTesting(progress > 0.6)
            .accessibilityHidden(progress < 0.3)
    }
}

/// Hides the window's own title, which would repeat the name in the sidebar header.
/// The title stays set, so the Window menu and Mission Control still show it.
struct HideWindowTitle: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async { view.window?.titleVisibility = .hidden }
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async { view.window?.titleVisibility = .hidden }
    }
}
