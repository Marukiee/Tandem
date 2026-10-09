import AppKit
import SwiftUI
import TandemCore

/// A borderless window can only take clicks if it is allowed to be the key window.
private final class LiveWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

/// The window of one session: the picture edge to edge, locked to its shape and resizable. The title bar is not drawn until
/// the pointer goes to the top of the window, so by default there is nothing but the phone. An option makes it float above
/// everything, and a frame-less mode takes the title bar away for good.
@MainActor
final class LiveWindowController: NSObject, NSWindowDelegate {
    let session: LiveSession
    private unowned let manager: LiveManager
    private(set) var window: NSWindow!
    private var shownSize: CGSize = .zero
    private var hoverMonitor: Any?
    private static var cascade = NSPoint.zero
    /// How far down from the top of the window the pointer shows the title bar.
    private static let topZone: CGFloat = 56

    private static let titledMask: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
    private static let framelessMask: NSWindow.StyleMask = [.borderless, .resizable]

    init(session: LiveSession, manager: LiveManager) {
        self.session = session
        self.manager = manager
        super.init()

        let size = initialSize()
        let window = LiveWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: Self.titledMask, backing: .buffered, defer: false)
        window.title = session.title
        window.subtitle = session.subtitle
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.acceptsMouseMovedEvents = true
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.collectionBehavior = [.fullScreenPrimary]
        window.backgroundColor = .black
        window.contentMinSize = NSSize(width: 160, height: 160)

        let host = NSHostingView(rootView: LiveView(session: session, controller: self).environment(EngineModel.shared))
        host.sizingOptions = []
        window.contentView = host
        self.window = window
        setTitleBar(shown: false, animated: false)
        hoverMonitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .mouseExited]) { [weak self] event in
            self?.pointerMoved(event)
            return event
        }
        contentSizeChanged()
        window.center()
        if Self.cascade != .zero {
            Self.cascade = window.cascadeTopLeft(from: Self.cascade)
        } else {
            Self.cascade = window.cascadeTopLeft(from: NSPoint(x: window.frame.minX, y: window.frame.maxY))
        }
    }

    func show() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func bringToFront() {
        if window.isMiniaturized { window.deminiaturize(nil) }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // MARK: The title bar that comes when the pointer asks for it

    private func pointerMoved(_ event: NSEvent) {
        guard event.window === window, window.styleMask.contains(.titled) else { return }
        let inZone = event.type == .mouseMoved && event.locationInWindow.y >= window.frame.height - Self.topZone
        if inZone != session.titleBarShown { setTitleBar(shown: inZone, animated: true) }
    }

    /// The three buttons of the window and the name of the phone, shown or not. The buttons are only faded, so they are
    /// still there to click once the pointer has come to them.
    private func setTitleBar(shown: Bool, animated: Bool) {
        session.titleBarShown = shown
        let buttons: [NSWindow.ButtonType] = [.closeButton, .miniaturizeButton, .zoomButton]
        for type in buttons {
            guard let button = window.standardWindowButton(type) else { continue }
            if animated {
                NSAnimationContext.runAnimationGroup { $0.duration = 0.18; button.animator().alphaValue = shown ? 1 : 0 }
            } else {
                button.alphaValue = shown ? 1 : 0
            }
        }
    }

    // MARK: Size

    private func initialSize() -> CGSize {
        let area = (NSScreen.main ?? NSScreen.screens.first)?.visibleFrame.size ?? CGSize(width: 1440, height: 900)
        return fit(aspect: defaultAspect, in: area)
    }

    /// What the window is shaped like before the other side has said: a phone held upright for its screen, a wide window for the screen
    /// of a computer, a landscape picture for the camera. It is corrected the moment the answer comes.
    private var defaultAspect: CGSize {
        if session.kind == .camera { return CGSize(width: 16, height: 9) }
        // A computer has a wide screen; a phone is held upright.
        let computer = EngineModel.shared.device(session.peer)?.isComputer ?? false
        return computer ? CGSize(width: 16, height: 10) : CGSize(width: 9, height: 19.5)
    }

    /// Largest size with this shape that takes up at most 70% of the height and 60% of the width of the screen.
    private func fit(aspect: CGSize, in area: CGSize) -> CGSize {
        let maxHeight = area.height * 0.7
        let maxWidth = area.width * 0.6
        let scale = min(maxWidth / aspect.width, maxHeight / aspect.height)
        let size = CGSize(width: max(240, (aspect.width * scale).rounded()), height: max(135, (aspect.height * scale).rounded()))
        return size
    }

    /// The picture changed shape (the phone turned, the request was answered): the window follows, and keeps that shape
    /// while the person resizes it.
    func contentSizeChanged() {
        let shown = session.shownSize
        guard shown.width > 0, shown.height > 0, shown != shownSize else { return }
        let first = shownSize == .zero
        shownSize = shown
        window.contentAspectRatio = shown
        window.contentMinSize = NSSize(width: 160, height: 160 * shown.height / shown.width)
        guard let screen = window.screen ?? NSScreen.main else { return }
        let area = screen.visibleFrame.size
        let current = window.contentRect(forFrameRect: window.frame).size
        let target: CGSize
        if first {
            target = fit(aspect: shown, in: area)
        } else {
            // Same amount of window, new shape.
            let pixels = current.width * current.height
            let width = (pixels * shown.width / shown.height).squareRoot()
            target = CGSize(width: width, height: width * shown.height / shown.width)
        }
        var frame = window.frameRect(forContentRect: NSRect(origin: .zero, size: target))
        // Grow from the top left corner, where the eye already is.
        frame.origin = NSPoint(x: window.frame.minX, y: window.frame.maxY - frame.height)
        window.setFrame(frame.constrained(to: screen.visibleFrame), display: true, animate: !first)
    }

    // MARK: Modes

    func setAlwaysOnTop(_ on: Bool) {
        session.alwaysOnTop = on
        window.level = on ? .floating : .normal
    }

    /// A frame-less window: just the picture with rounded corners, movable by dragging it, still resizable at the edges.
    func setFrameless(_ on: Bool) {
        session.frameless = on
        let frame = window.frame
        let content = window.contentRect(forFrameRect: frame)
        window.styleMask = on ? Self.framelessMask : Self.titledMask
        window.isMovableByWindowBackground = on
        window.hasShadow = true
        window.isOpaque = !on
        window.backgroundColor = on ? .clear : .black
        if let view = window.contentView {
            view.wantsLayer = true
            view.layer?.cornerRadius = on ? 22 : 0
            view.layer?.cornerCurve = .continuous
            view.layer?.masksToBounds = on
        }
        // The title bar goes or comes; the picture stays where it was.
        window.setFrame(window.frameRect(forContentRect: content), display: true)
        window.setFrameOrigin(NSPoint(x: frame.minX, y: content.maxY - window.frame.height))
        if !on {
            window.title = session.title
            window.subtitle = session.subtitle
        }
        if on { session.titleBarShown = false } else { setTitleBar(shown: false, animated: false) }
        window.makeKeyAndOrderFront(nil)
    }

    func close() { window.close() }

    // MARK: The picture

    func copyScreenshot() {
        guard let image = session.surface.renderedImage(), let tiff = image.tiffRepresentation,
              let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
        else {
            FloatingToast.show(String(localized: "There is no picture to copy yet"), symbol: "exclamationmark.circle.fill")
            return
        }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.writeObjects([image])
        pasteboard.setData(png, forType: .png)
        FloatingToast.show(String(localized: "Picture copied"))
    }

    /// The window as a PNG, with the video drawn into it. For the debug harness.
    func snapshotPNG() -> Data? {
        guard let view = (window.styleMask.contains(.titled) ? window.contentView?.superview : nil) ?? window.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)
        else { return nil }
        session.surface.prepareSnapshot()
        view.cacheDisplay(in: view.bounds, to: rep)
        session.surface.finishSnapshot()
        return rep.representation(using: .png, properties: [:])
    }

    // MARK: NSWindowDelegate

    func windowWillClose(_ notification: Notification) {
        if let hoverMonitor { NSEvent.removeMonitor(hoverMonitor) }
        hoverMonitor = nil
        manager.windowClosed(session)
    }
}

private extension NSRect {
    /// Moved so that it lies inside `bounds` as far as it fits.
    func constrained(to bounds: NSRect) -> NSRect {
        var rect = self
        if rect.maxX > bounds.maxX { rect.origin.x = bounds.maxX - rect.width }
        if rect.minX < bounds.minX { rect.origin.x = bounds.minX }
        if rect.minY < bounds.minY { rect.origin.y = bounds.minY }
        if rect.maxY > bounds.maxY { rect.origin.y = bounds.maxY - rect.height }
        return rect
    }
}
