import AppKit
import SwiftUI

/// A page whose big title moves into the toolbar once it has scrolled out of view.
///
/// One switch drives both ends, animated with the same spring, so the title in the page
/// and the one in the toolbar read as a single thing changing places. The toolbar copy is
/// a real toolbar item, which macOS draws on glass, so it stays readable over any content.
extension View {
    /// Sets `compact` when the scroll view around this has scrolled the page title away.
    func trackCompactTitle(_ compact: Binding<Bool>, after threshold: CGFloat = 64) -> some View {
        scrollEdgeEffectStyle(.soft, for: .top)
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > threshold
            } action: { _, isCompact in
                withAnimation(.bouncy(duration: 0.45, extraBounce: 0.04)) { compact.wrappedValue = isCompact }
            }
    }

    /// The title inside the page, leaving as the toolbar's copy arrives.
    func titleHandoff(_ compact: Bool) -> some View {
        modifier(TitleHandoff(compact: compact))
    }
}

private struct TitleHandoff: ViewModifier {
    let compact: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(compact ? 0 : 1)
            .offset(y: reduceMotion || !compact ? 0 : -8)
            .blur(radius: reduceMotion || !compact ? 0 : 5)
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
