import AppKit
import SwiftUI

/// Keeps its content only while its window is on screen.
///
/// A SwiftUI window that is closed keeps its whole view tree, and with it the layers, images and caches that nobody
/// is looking at: about 45 MB here, in an app that spends most of its day in the menu bar with the window closed.
/// Dropping the tree when the window has been closed gives that back, and it is made again when the window returns.
/// What the page should show lives in the model, not in the views, so nothing is lost.
struct ReleasedWhenClosed<Content: View>: View {
    @LocalState private var onScreen = true
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        ZStack {
            if onScreen { content() }
        }
        .background(WindowWatcher { visible in
            onScreen = visible
            if !visible {
                // The views go a moment later. What they leave behind is free memory the allocator would keep
                // for itself for good, so ask for it back.
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { _ = malloc_zone_pressure_relief(nil, 0) }
            }
        })
    }
}

private struct WindowWatcher: NSViewRepresentable {
    let changed: (Bool) -> Void

    func makeNSView(context: Context) -> WatcherView {
        let view = WatcherView()
        view.changed = changed
        return view
    }

    func updateNSView(_ view: WatcherView, context: Context) {
        view.changed = changed
    }
}

private final class WatcherView: NSView {
    var changed: ((Bool) -> Void)?
    private var tokens: [NSObjectProtocol] = []
    private var visibility: NSKeyValueObservation?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        stop()
        guard let window else { return }
        let center = NotificationCenter.default
        // A moment after the window has closed, so its fade-out does not show an empty window.
        tokens.append(center.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { [weak self, weak window] _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                if window?.isVisible != true { self?.changed?(false) }
            }
        })
        tokens.append(center.addObserver(forName: NSWindow.didBecomeKeyNotification, object: window, queue: .main) { [weak self] _ in
            self?.changed?(true)
        })
        visibility = window.observe(\.isVisible, options: [.new]) { [weak self] window, _ in
            guard window.isVisible else { return }
            DispatchQueue.main.async { self?.changed?(true) }
        }
    }

    private func stop() {
        tokens.forEach(NotificationCenter.default.removeObserver)
        tokens = []
        visibility = nil
    }
}
