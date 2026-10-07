import AppKit
import TandemCore

/// Dragging files over the edge of the screen to the computer that sits there (see the shared pointer in `PointerShare`). While files
/// are being dragged, a drop zone shows at the edge where a computer was put, with its name on it. Letting go of the files on it
/// sends them to that computer, the way a drop on the device in the menu bar panel does. The zone is a window like any other, so
/// the drag and the drop are the system's own and nothing of the drag is taken away from the app it started in.
@MainActor
final class EdgeDrop {
    static let shared = EdgeDrop()

    private var monitor: Any?
    private var lastCount = NSPasteboard(name: .drag).changeCount
    private var zones: [String: EdgeZone] = [:]
    private var hideTask: Task<Void, Never>?

    func start() {
        guard monitor == nil else { return }
        monitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDragged, .leftMouseUp]) { [weak self] event in
            let kind = event.type
            Task { @MainActor in self?.saw(kind) }
        }
    }

    private func saw(_ type: NSEvent.EventType) {
        let share = PointerShare.shared
        guard share.enabled, !share.neighbours.isEmpty else { return }
        switch type {
        case .leftMouseDragged:
            let board = NSPasteboard(name: .drag)
            // A drag that has files in it, started since the last one ended.
            guard board.changeCount != lastCount || !zones.isEmpty,
                  board.canReadObject(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
            else { return }
            hideTask?.cancel()
            if zones.isEmpty { show(for: share.neighbours) }
        case .leftMouseUp:
            lastCount = NSPasteboard(name: .drag).changeCount
            // A moment's grace: the drop on a zone is dealt with before the zone goes.
            hideTask?.cancel()
            hideTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(450))
                if !Task.isCancelled { self.hide() }
            }
        default:
            break
        }
    }

    private func show(for neighbours: [String: String]) {
        guard let screen = NSScreen.main else { return }
        for (device, edge) in neighbours {
            guard let found = EngineModel.shared.device(device), found.online else { continue }
            let zone = EdgeZone(device: found, edge: edge, screen: screen.frame)
            zones[device] = zone
            zone.show()
        }
    }

    private func hide() {
        for zone in zones.values { zone.hide() }
        zones = [:]
    }
}

/// One drop zone: a slim panel at an edge, with the name of the computer and a symbol.
@MainActor
private final class EdgeZone {
    private let panel: NSPanel
    private let view: ZoneView

    init(device: TandemDevice, edge: String, screen: NSRect) {
        let thickness: CGFloat = 96
        let length: CGFloat = min(max((edge == "left" || edge == "right" ? screen.height : screen.width) * 0.45, 260), 520)
        let frame: NSRect
        switch edge {
        case "left": frame = NSRect(x: screen.minX, y: screen.midY - length / 2, width: thickness, height: length)
        case "right": frame = NSRect(x: screen.maxX - thickness, y: screen.midY - length / 2, width: thickness, height: length)
        case "top": frame = NSRect(x: screen.midX - length / 2, y: screen.maxY - thickness, width: length, height: thickness)
        default: frame = NSRect(x: screen.midX - length / 2, y: screen.minY, width: length, height: thickness)
        }
        view = ZoneView(frame: NSRect(origin: .zero, size: frame.size), device: device, vertical: edge == "left" || edge == "right")
        panel = NSPanel(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.level = .popUpMenu
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        // A plain container around the view, as the other panels of the app have it.
        let container = NSView(frame: NSRect(origin: .zero, size: frame.size))
        container.addSubview(view)
        panel.contentView = container
    }

    func show() {
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.18
            panel.animator().alphaValue = 1
        }
    }

    func hide() {
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.15
            panel.animator().alphaValue = 0
        }, completionHandler: { [panel] in panel.orderOut(nil) })
    }
}

private final class ZoneView: NSVisualEffectView {
    private let device: TandemDevice
    private let label = NSTextField(labelWithString: "")
    private let icon = NSImageView()
    private var over = false { didSet { needsDisplay = true; updateLook() } }

    init(frame: NSRect, device: TandemDevice, vertical: Bool) {
        self.device = device
        super.init(frame: frame)
        material = .hudWindow
        blendingMode = .behindWindow
        state = .active
        wantsLayer = true
        layer?.cornerRadius = 22
        layer?.masksToBounds = true
        registerForDraggedTypes([.fileURL])

        icon.image = NSImage(systemSymbolName: device.platform.symbol, accessibilityDescription: nil)
        icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 26, weight: .semibold)
        label.stringValue = device.name
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        label.alignment = .center
        label.lineBreakMode = .byTruncatingTail
        label.maximumNumberOfLines = 2
        let stack = NSStackView(views: [icon, label])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -12),
        ])
        updateLook()
    }

    required init?(coder: NSCoder) { nil }

    private func updateLook() {
        icon.contentTintColor = over ? .white : NSColor(Palette.indigo)
        label.textColor = over ? .white : .labelColor
        layer?.backgroundColor = over ? NSColor(Palette.indigo).withAlphaComponent(0.85).cgColor : nil
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        over = true
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) { over = false }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        over = false
        guard let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
              !urls.isEmpty
        else { return false }
        let device = self.device
        Task { @MainActor in
            EngineModel.shared.send(urls: urls, to: [device.id])
            EngineModel.shared.showToast(String(localized: "Sending to \(device.name)"))
        }
        return true
    }
}
