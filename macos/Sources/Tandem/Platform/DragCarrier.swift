import AppKit
import TandemCore

/// Files that another computer dropped on the edge of its screen towards this Mac, carried here by the pointer. Linux under Wayland
/// cannot let a drag run on over the edge, so the button is already up when the files arrive: they hang under the pointer as an icon
/// instead, and the next press puts them down where it is, in a field of a website or any app that takes files. Under the hood the icon
/// is a small panel right under the pointer, so the press (of this Mac's own mouse, or one played here for the computer that uses it)
/// lands on it and starts a real drag of the system with the files; the release drops them. Nothing pressed in time, or Escape: they go
/// where a drop on the edge has always put them (`DragLanding`).
@MainActor
final class DragCarrier {
    static let shared = DragCarrier()

    private var files: [URL] = []
    private var from = ""
    private var name = ""
    private var panel: CarrierPanel?
    private var follow: Timer?
    private var giveUp: Task<Void, Never>?
    private var pickUp: Task<Void, Never>?
    /// The press that started the drag, so its release is let through and nothing else is taken for one.
    private var dragging = false
    /// Escape on this Mac's own keyboard, in another app or in Tandem.
    private var escape: [Any] = []

    var carrying: Bool { !files.isEmpty }

    /// Files from `device` arrived while it shares its pointer with this Mac. Taken (true) to hang under the pointer; false when this
    /// is not such a moment and the caller lands them as before.
    func carry(_ url: URL, name: String, from device: String) -> Bool {
        guard PointerShare.shared.isPeer(device), !dragging else { return false }
        if carrying, from != device { putAway() }
        from = device
        self.name = name
        files.append(url)
        show()
        // The pointer may still be over there: it has a while to come, then a while for the press.
        restartGiveUp(seconds: 30)
        pickUpIfHeld()
        return true
    }

    /// A drag that came over the edge from that computer with the button of this Mac's mouse still down: the drag goes on here at once,
    /// so the files are let go wherever the button comes up, not with a press later. The press is played onto the panel under the
    /// pointer, which starts a real drag of the system; the button the person holds ends it. Files of one drag come one by one, so it
    /// waits a moment for the rest, which a drag cannot take in once it has started.
    private func pickUpIfHeld() {
        pickUp?.cancel()
        pickUp = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard let self, !Task.isCancelled, self.carrying, !self.dragging, NSEvent.pressedMouseButtons & 1 != 0 else { return }
            let share = PointerShare.shared
            guard share.remote == nil, share.controlledBy == nil else { return }
            self.moveToPointer()
            let at = CGEvent(source: nil)?.location ?? .zero
            CGEvent(mouseEventSource: CGEventSource(stateID: .hidSystemState), mouseType: .leftMouseDown, mouseCursorPosition: at, mouseButton: .left)?
                .post(tap: .cgSessionEventTap)
        }
    }

    /// A press is about to be played here for the computer that uses the pointer: the panel is put exactly under the pointer first, in
    /// case the last movement came after the last step of the timer.
    func beforePress(from device: String) {
        guard carrying, device == from else { return }
        moveToPointer()
    }

    /// Escape from the computer that uses the pointer: the files go to the folder after all.
    func cancel(from device: String) {
        guard carrying, device == from, !dragging else { return }
        putAway()
    }

    // MARK: Showing

    private func show() {
        let panel = self.panel ?? CarrierPanel()
        self.panel = panel
        panel.view.update(files: files, carrier: self)
        panel.orderFrontRegardless()
        if escape.isEmpty {
            let pressed: (NSEvent) -> Void = { event in
                guard event.keyCode == 53 else { return }
                MainActor.assumeIsolated { DragCarrier.shared.cancel(from: DragCarrier.shared.from) }
            }
            if let global = NSEvent.addGlobalMonitorForEvents(matching: .keyDown, handler: pressed) { escape.append(global) }
            if let local = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { pressed($0); return $0 }) { escape.append(local) }
        }
        moveToPointer()
        follow?.invalidate()
        // Next to the pointer, the way a dragged file sits there, while it is on this Mac.
        follow = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { _ in
            MainActor.assumeIsolated { DragCarrier.shared.moveToPointer() }
        }
    }

    /// Right under the pointer while it is on this Mac (used from here, or by the computer the files came from), so a press lands on
    /// the panel; out of sight and out of the way while the pointer is on another computer.
    fileprivate func moveToPointer() {
        guard let panel, !dragging else { return }
        let share = PointerShare.shared
        let here = share.controlledBy == from || (share.controlledBy == nil && share.remote == nil)
        panel.alphaValue = here ? 1 : 0
        panel.ignoresMouseEvents = !here
        panel.view.armed = here
        let at = NSEvent.mouseLocation
        panel.setFrameOrigin(NSPoint(x: at.x - panel.frame.width / 2, y: at.y - panel.frame.height / 2))
    }

    private func restartGiveUp(seconds: Int) {
        giveUp?.cancel()
        giveUp = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, !self.dragging else { return }
            self.putAway()
        }
    }

    private func close() {
        escape.forEach(NSEvent.removeMonitor)
        escape = []
        follow?.invalidate()
        follow = nil
        giveUp?.cancel()
        giveUp = nil
        pickUp?.cancel()
        pickUp = nil
        panel?.orderOut(nil)
        panel = nil
        files = []
        dragging = false
    }

    /// Where a drop on the edge has always put the files, and the folder shown.
    private func putAway() {
        let (carried, device, label) = (files, from, name)
        close()
        for file in carried { DragLanding.shared.land(file, name: carried.count == 1 ? label : file.lastPathComponent, from: device) }
    }

    // MARK: The drag

    fileprivate func pressed(_ event: NSEvent, in view: CarrierView) {
        guard carrying, !dragging else { return }
        dragging = true
        giveUp?.cancel()
        let items = files.map { url -> NSDraggingItem in
            let item = NSDraggingItem(pasteboardWriter: url as NSURL)
            let icon = NSWorkspace.shared.icon(forFile: url.path)
            item.setDraggingFrame(NSRect(x: 0, y: 0, width: 48, height: 48), contents: icon)
            return item
        }
        view.beginDraggingSession(with: items, event: event, source: view)
        // Out of the way at once: under the pointer it would be the place of the drop itself.
        panel?.alphaValue = 0
        panel?.ignoresMouseEvents = true
    }

    fileprivate func ended(operation: NSDragOperation) {
        let (carried, device) = (files, from)
        let deviceName = EngineModel.shared.device(device)?.name ?? "?"
        if operation.isEmpty {
            putAway()
            return
        }
        close()
        EngineModel.shared.showToast(carried.count == 1
            ? String(localized: "\(carried[0].lastPathComponent) from \(deviceName) was put down")
            : String(localized: "\(carried.count) files from \(deviceName) were put down"))
    }
}

/// The small window that hangs under the pointer with the icon of the files.
private final class CarrierPanel: NSPanel {
    let view = CarrierView(frame: NSRect(x: 0, y: 0, width: 56, height: 56))

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 56, height: 56), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        level = .popUpMenu
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        contentView = view
    }
}

private final class CarrierView: NSView, NSDraggingSource {
    weak var carrier: DragCarrier?
    var armed = false
    private let image = NSImageView()
    private let badge = NSTextField(labelWithString: "")

    override init(frame: NSRect) {
        super.init(frame: frame)
        image.frame = bounds.insetBy(dx: 4, dy: 4)
        image.imageScaling = .scaleProportionallyUpOrDown
        addSubview(image)
        badge.font = .systemFont(ofSize: 11, weight: .bold)
        badge.textColor = .white
        badge.alignment = .center
        badge.wantsLayer = true
        badge.layer?.backgroundColor = NSColor.systemIndigo.cgColor
        badge.layer?.cornerRadius = 9
        badge.frame = NSRect(x: bounds.maxX - 22, y: bounds.maxY - 20, width: 22, height: 18)
        addSubview(badge)
    }

    required init?(coder: NSCoder) { nil }

    func update(files: [URL], carrier: DragCarrier) {
        self.carrier = carrier
        image.image = files.first.map { NSWorkspace.shared.icon(forFile: $0.path) }
        image.alphaValue = 0.85
        badge.isHidden = files.count < 2
        badge.stringValue = "\(files.count)"
    }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        guard armed else { return }
        armed = false
        MainActor.assumeIsolated { carrier?.pressed(event, in: self) }
    }

    nonisolated func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        context == .outsideApplication ? [.copy, .generic] : [.copy]
    }

    nonisolated func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        MainActor.assumeIsolated { carrier?.ended(operation: operation) }
    }
}
