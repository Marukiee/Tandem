import AppKit
import ApplicationServices
import SwiftUI
import TandemCore

// MARK: Model

/// What the quick panel shows and what its keys do. The window forwards the keys here, because a text field that has
/// the focus would otherwise keep the arrows and Return for itself.
@MainActor
@Observable
final class ClipboardPanelModel {
    enum Row: Identifiable {
        case header(String)
        case item(ClipItem)

        var id: String {
            switch self {
            case let .header(title): "header.\(title)"
            case let .item(item): item.id.uuidString
            }
        }
    }

    var query = "" { didSet { rebuild() } }
    var filter: ClipFilter = .all { didSet { rebuild() } }
    var selection: UUID?
    private(set) var rows: [Row] = []
    var actionsOpen = false
    var actionIndex = 0
    /// Return pastes into the app that was in front, rather than only copying.
    let pastes: Bool

    @ObservationIgnored var onFinish: ((ClipItem, Bool) -> Void)?
    @ObservationIgnored var onClose: (() -> Void)?

    private let history = ClipboardHistory.shared

    init(pastes: Bool) {
        self.pastes = pastes
        rebuild()
    }

    var items: [ClipItem] {
        rows.compactMap { if case let .item(item) = $0 { item } else { nil } }
    }

    var selected: ClipItem? {
        selection.flatMap { id in items.first { $0.id == id } }
    }

    func rebuild() {
        let matches = ClipQuery.apply(history.items, query: query, filter: filter)
        if filter == .all, query.isEmpty, matches.contains(where: \.pinned) {
            let pinned = matches.filter(\.pinned)
            let rest = matches.filter { !$0.pinned }
            rows = [.header(String(localized: "Pinned"))] + pinned.map(Row.item)
            if !rest.isEmpty { rows += [.header(String(localized: "Recent"))] + rest.map(Row.item) }
        } else {
            rows = matches.map(Row.item)
        }
        if selection == nil || !items.contains(where: { $0.id == selection }) { selection = items.first?.id }
        actionsOpen = false
    }

    // MARK: Keys

    func move(_ step: Int) {
        let list = items
        guard !list.isEmpty else { return }
        let current = list.firstIndex { $0.id == selection } ?? 0
        selection = list[min(list.count - 1, max(0, current + step))].id
    }

    func activate() {
        guard let item = selected else { return }
        onFinish?(item, pastes)
    }

    func copyOnly() {
        guard let item = selected else { return }
        onFinish?(item, false)
    }

    func choose(_ item: ClipItem) {
        selection = item.id
        activate()
    }

    func paste(number: Int) {
        let list = items
        guard number >= 1, number <= list.count else { return }
        onFinish?(list[number - 1], pastes)
    }

    func togglePin() {
        guard let item = selected else { return }
        history.togglePin(item.id)
        rebuild()
    }

    func deleteSelected() {
        guard let item = selected else { return }
        remove(item)
    }

    func remove(_ item: ClipItem) {
        let list = items
        let position = list.firstIndex { $0.id == item.id } ?? 0
        history.delete([item.id])
        selection = nil
        rebuild()
        let after = items
        if !after.isEmpty { selection = after[min(position, after.count - 1)].id }
    }

    func cycleFilter(_ step: Int) {
        let all = ClipFilter.allCases
        let index = all.firstIndex(of: filter) ?? 0
        filter = all[(index + step + all.count) % all.count]
    }

    /// Escape peels one layer: the actions, then the search, then the panel.
    func escape() {
        if actionsOpen {
            actionsOpen = false
        } else if !query.isEmpty {
            query = ""
        } else {
            onClose?()
        }
    }

    func toggleActions() {
        guard selected != nil else { return }
        actionIndex = 0
        actionsOpen.toggle()
    }

    func moveAction(_ step: Int, count: Int) {
        guard count > 0 else { return }
        actionIndex = min(count - 1, max(0, actionIndex + step))
    }

    // MARK: Actions

    func primaryActions(for item: ClipItem) -> [ClipAction] {
        var list: [ClipAction] = []
        if pastes {
            list.append(ClipAction(id: "paste", title: String(localized: "Paste"), symbol: "arrow.turn.down.left", shortcut: "↩") { [weak self] in self?.onFinish?(item, true) })
            list.append(ClipAction(id: "copy", title: String(localized: "Copy"), symbol: "doc.on.doc", shortcut: "⌘↩") { [weak self] in self?.onFinish?(item, false) })
        } else {
            list.append(ClipAction(id: "copy", title: String(localized: "Copy"), symbol: "doc.on.doc", shortcut: "↩") { [weak self] in self?.onFinish?(item, false) })
        }
        return list
    }

    func actions(for item: ClipItem) -> [ClipAction] {
        ClipActions.list(for: item, model: EngineModel.shared, primary: primaryActions(for: item)) { [weak self] in
            self?.rebuild()
        }
    }

    func run(_ action: ClipAction) {
        actionsOpen = false
        action.run()
        // Pinning and deleting change the list; pasting has already closed it.
        rebuild()
    }
}

// MARK: Controller

private final class ClipPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// The floating panel with the history: opened by a shortcut from anywhere, closed by Escape, a click elsewhere or a
/// choice. It does not make Tandem the active app, so the app that was in front stays in front and gets the paste.
@MainActor
final class ClipboardPanelController {
    static let shared = ClipboardPanelController()

    static let size = NSSize(width: 780, height: 500)

    private var panel: ClipPanel?
    private var model: ClipboardPanelModel?
    private let hotkey = GlobalHotkey()
    private var keyMonitor: Any?
    private var clickMonitor: Any?
    private var resignToken: NSObjectProtocol?
    private var target: NSRunningApplication?
    private var lastAccessibilityPrompt = Date.distantPast

    var isVisible: Bool { panel?.isVisible == true }

    func start() {
        hotkey.onPress = { [weak self] in self?.toggle() }
        registerHotkey()
        ClipboardRecorder.shared.refresh()
    }

    func registerHotkey() {
        let history = ClipboardHistory.shared
        if history.hotkeyOn { hotkey.register(history.hotkey) } else { hotkey.unregister() }
    }

    /// While a new shortcut is being recorded, the old one must not open the panel.
    func suspendHotkey() { hotkey.unregister() }

    func toggle() {
        if isVisible { hide() } else { show() }
    }

    func show() {
        guard !isVisible else { return }
        let front = NSWorkspace.shared.frontmostApplication
        target = front?.bundleIdentifier == Bundle.main.bundleIdentifier ? nil : front
        let model = ClipboardPanelModel(pastes: ClipboardHistory.shared.pastesDirectly && target != nil)
        model.onFinish = { [weak self] item, paste in self?.finish(item, paste: paste) }
        model.onClose = { [weak self] in self?.hide() }
        self.model = model

        let panel = self.panel ?? makePanel()
        self.panel = panel
        let host = NSHostingView(rootView: ClipboardPanelView(model: model).environment(EngineModel.shared))
        // The panel has a size of its own; letting the view size the window raised a layout exception.
        host.sizingOptions = []
        panel.contentView = host
        place(panel)
        panel.alphaValue = 0
        panel.makeKeyAndOrderFront(nil)
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.14
            panel.animator().alphaValue = 1
        }
        panel.invalidateShadow()
        watch(panel)
    }

    func hide() {
        guard let panel, panel.isVisible else { return }
        unwatch()
        model = nil
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.1
            panel.animator().alphaValue = 0
        }, completionHandler: {
            // Not if it was opened again in the meantime.
            MainActor.assumeIsolated {
                guard self.model == nil else { return }
                panel.orderOut(nil)
                panel.contentView = nil
            }
        })
    }

    private func makePanel() -> ClipPanel {
        let panel = ClipPanel(
            contentRect: NSRect(origin: .zero, size: Self.size),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.hidesOnDeactivate = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        return panel
    }

    /// Upper third of the screen the pointer is on, like the launchers.
    private func place(_ panel: NSPanel) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(mouse) } ?? NSScreen.main
        guard let area = screen?.visibleFrame else { return }
        let height = Self.size.height
        let top = area.maxY - area.height * 0.16
        panel.setFrame(
            NSRect(x: area.midX - Self.size.width / 2, y: max(area.minY + 20, top - height), width: Self.size.width, height: height),
            display: false
        )
    }

    // MARK: Watching

    private func watch(_ panel: NSPanel) {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, event.window === self.panel else { return event }
            return MainActor.assumeIsolated { self.handle(event) } ? nil : event
        }
        // A click anywhere else, in another app, closes it.
        clickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] _ in
            MainActor.assumeIsolated { if !DebugSupport.keepsPanelOpen { self?.hide() } }
        }
        resignToken = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: panel, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { if !DebugSupport.keepsPanelOpen { self?.hide() } }
        }
    }

    private func unwatch() {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        if let clickMonitor { NSEvent.removeMonitor(clickMonitor) }
        if let resignToken { NotificationCenter.default.removeObserver(resignToken) }
        keyMonitor = nil
        clickMonitor = nil
        resignToken = nil
    }

    // MARK: Keys

    /// Returns whether the key was used here. What is not used goes on to the search field.
    private func handle(_ event: NSEvent) -> Bool {
        guard let model, let panel, panel.isKeyWindow else { return false }
        // Composing text with an input method: its keys are its own.
        if let editor = panel.firstResponder as? NSTextView, editor.hasMarkedText() { return false }
        let flags = event.modifierFlags
        let command = flags.contains(.command)
        let control = flags.contains(.control)
        let choosing = model.actionsOpen

        switch Int(event.keyCode) {
        case 125: // down
            if choosing { model.moveAction(1, count: currentActions(model).count) } else { model.move(1) }
        case 126: // up
            if choosing { model.moveAction(-1, count: currentActions(model).count) } else { model.move(-1) }
        case 36, 76: // return
            if choosing {
                let list = currentActions(model)
                if list.indices.contains(model.actionIndex) { model.run(list[model.actionIndex]) }
            } else if command {
                model.copyOnly()
            } else {
                model.activate()
            }
        case 53: // escape
            model.escape()
        case 48: // tab
            guard !choosing else { return true }
            model.cycleFilter(flags.contains(.shift) ? -1 : 1)
        case 51, 117: // delete
            guard command, !choosing else { return false }
            model.deleteSelected()
        default:
            if control, let key = event.charactersIgnoringModifiers {
                if key == "n" { model.move(1); return true }
                if key == "p" { model.move(-1); return true }
            }
            guard command, let key = event.charactersIgnoringModifiers else { return false }
            switch key {
            case "p": model.togglePin()
            case "k": model.toggleActions()
            case "1", "2", "3", "4", "5", "6", "7", "8", "9": model.paste(number: Int(key) ?? 1)
            default: return false
            }
        }
        return true
    }

    private func currentActions(_ model: ClipboardPanelModel) -> [ClipAction] {
        model.selected.map { model.actions(for: $0) } ?? []
    }

    // MARK: Putting a clip to use

    /// Puts an item on the pasteboard, and when asked and allowed, pastes it where the cursor is.
    private func finish(_ item: ClipItem, paste: Bool) {
        write(item)
        hide()
        guard paste, target != nil else {
            FloatingToast.show(String(localized: "Copied"), symbol: "doc.on.doc.fill")
            return
        }
        guard AXIsProcessTrusted() else {
            askForAccessibility()
            FloatingToast.show(String(localized: "Copied. Allow Tandem under Accessibility to paste straight away."), symbol: "hand.raised.fill")
            return
        }
        // A moment for the app behind the panel to be the one that takes the keys again.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { Self.postPaste() }
    }

    /// Copies an item from outside the panel, for the page in the main window.
    func copy(_ item: ClipItem) {
        write(item)
        FloatingToast.show(String(localized: "Copied"), symbol: "doc.on.doc.fill")
    }

    private func write(_ item: ClipItem) {
        let history = ClipboardHistory.shared
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        switch item.kind {
        case .text, .link:
            pasteboard.setString(history.fullText(of: item) ?? item.preview, forType: .string)
        case .image:
            if let image = NSImage(contentsOf: history.imageURL(item.id)) { pasteboard.writeObjects([image]) }
        }
        // Not a new copy, so the recorder leaves it alone; it only moves up the list.
        ClipboardRecorder.shared.ignoreCurrent()
        history.touch(item.id)
    }

    private func askForAccessibility() {
        guard Date().timeIntervalSince(lastAccessibilityPrompt) > 600 else { return }
        lastAccessibilityPrompt = Date()
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }

    private static func postPaste() {
        let source = CGEventSource(stateID: .combinedSessionState)
        let code = CGKeyCode(KeyboardLayout.keyCode(forCharacter: "v") ?? 9)
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down)
            event?.flags = .maskCommand
            event?.post(tap: .cghidEventTap)
        }
    }
}

// MARK: View

struct ClipboardPanelView: View {
    let model: ClipboardPanelModel
    @Environment(EngineModel.self) private var engine
    @FocusState private var searchFocused: Bool
    @LocalState private var appeared = false

    private var history: ClipboardHistory { .shared }

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 0) {
            searchBar(model: model)
            divider
            HStack(spacing: 0) {
                list
                    .frame(width: 330)
                detail
                    .padding(.top, 10)
                    .padding(.trailing, 10)
                    .padding(.bottom, 10)
            }
            divider
            footer
        }
        .frame(width: ClipboardPanelController.size.width, height: ClipboardPanelController.size.height)
        .modifier(PanelGlass(radius: 30))
        .overlay(alignment: .bottomTrailing) {
            if model.actionsOpen, let item = model.selected {
                ClipActionsMenu(actions: model.actions(for: item), index: model.actionIndex) { model.run($0) }
                    .padding(.trailing, 16)
                    .padding(.bottom, 50)
                    .transition(.scale(scale: 0.94, anchor: .bottomTrailing).combined(with: .opacity))
            }
        }
        .animation(.tandemSpringy, value: model.actionsOpen)
        .scaleEffect(appeared ? 1 : 0.97)
        .onAppear {
            withAnimation(.tandemSpringy) { appeared = true }
            searchFocused = true
        }
    }

    private var divider: some View {
        Rectangle().fill(Color.primary.opacity(0.08)).frame(height: 1)
    }

    // MARK: Search

    private func searchBar(model: ClipboardPanelModel) -> some View {
        @Bindable var model = model
        return HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.title3.weight(.medium))
                .foregroundStyle(.secondary)
            TextField("Search clipboard history", text: $model.query)
                .textFieldStyle(.plain)
                .font(.title3)
                .focused($searchFocused)
            if !model.query.isEmpty {
                Button { model.query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .transition(.scale.combined(with: .opacity))
            }
            ClipFilterChips(filter: $model.filter)
        }
        .padding(.horizontal, 20)
        .frame(height: 58)
        .animation(.tandem, value: model.query.isEmpty)
    }

    // MARK: List

    @ViewBuilder
    private var list: some View {
        if model.rows.isEmpty {
            emptyList
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(model.rows) { row in
                            switch row {
                            case let .header(title):
                                Text(title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 12)
                                    .padding(.top, 8)
                                    .padding(.bottom, 2)
                            case let .item(item):
                                PanelRow(item: item, selected: model.selection == item.id)
                                    .id(item.id)
                                    .onTapGesture { model.choose(item) }
                                    .contextMenu { menu(for: item) }
                            }
                        }
                    }
                    .padding(8)
                    .animation(.tandem, value: model.rows.map(\.id))
                }
                .scrollIndicators(.never)
                .onChange(of: model.selection) { _, id in
                    guard let id else { return }
                    withAnimation(.tandem) {
                        if id == model.items.first?.id, let first = model.rows.first {
                            proxy.scrollTo(first.id, anchor: .top)
                        } else {
                            proxy.scrollTo(id)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func menu(for item: ClipItem) -> some View {
        ForEach(model.actions(for: item)) { action in
            Button(role: action.destructive ? .destructive : nil) {
                model.selection = item.id
                model.run(action)
            } label: {
                Label(action.title, systemImage: action.symbol)
            }
        }
    }

    private var emptyList: some View {
        VStack(spacing: 8) {
            Image(systemName: emptySymbol)
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.tertiary)
            Text(emptyTitle).font(.callout.weight(.medium))
            Text(emptyDetail)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if !history.enabled {
                Button("Turn on") { history.enabled = true; model.rebuild() }
                    .buttonStyle(.glassProminent)
                    .tint(Palette.indigo)
                    .controlSize(.small)
                    .padding(.top, 4)
            }
        }
        .padding(.horizontal, 30)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var searching: Bool { !model.query.isEmpty || model.filter != .all }

    private var emptySymbol: String {
        if !history.enabled { return "pause.circle" }
        return searching ? "magnifyingglass" : "doc.on.clipboard"
    }

    private var emptyTitle: LocalizedStringKey {
        if !history.enabled { return "The history is off" }
        return searching ? "No matches" : "Nothing copied yet"
    }

    private var emptyDetail: LocalizedStringKey {
        if !history.enabled { return "Tandem is not keeping what you copy." }
        return searching ? "Try other words, or another filter." : "What you copy shows up here, and what arrives from your other devices."
    }

    // MARK: Detail

    @ViewBuilder
    private var detail: some View {
        if let item = model.selected {
            VStack(spacing: 0) {
                ClipPreview(item: item)
                    .id(item.id)
                    .padding(.horizontal, 18)
                    .padding(.top, 16)
                    .padding(.bottom, 8)
                    .transition(.opacity)
                buttons(for: item)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 14)
            }
            .background(Color.primary.opacity(0.045), in: .rect(cornerRadius: 20, style: .continuous))
            .animation(.tandemFade, value: item.id)
        } else {
            Color.clear
        }
    }

    private func buttons(for item: ClipItem) -> some View {
        let reachable = engine.devices.filter { item.isImage ? $0.online : ($0.online || $0.ble) }
        return GlassEffectContainer(spacing: 8) {
            HStack(spacing: 8) {
                Button { model.togglePin() } label: {
                    Image(systemName: item.pinned ? "pin.slash" : "pin").frame(width: 16, height: 16)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .help(LocalizedStringKey(item.pinned ? "Unpin" : "Pin"))

                Menu {
                    ForEach(reachable, id: \.id) { device in
                        Button { engine.send(clip: item, to: [device.id]) } label: {
                            Label(device.name, systemImage: device.platform.symbol)
                        }
                    }
                } label: {
                    Image(systemName: "paperplane").frame(width: 16, height: 16)
                }
                .menuStyle(.button)
                .menuIndicator(.hidden)
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .disabled(reachable.isEmpty)
                .help(LocalizedStringKey(reachable.isEmpty ? "No device is connected" : "Send to a device"))

                Button { model.deleteSelected() } label: {
                    Image(systemName: "trash").frame(width: 16, height: 16)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .help("Delete")

                Spacer(minLength: 8)

                Button { model.activate() } label: {
                    HStack(spacing: 8) {
                        Text(LocalizedStringKey(model.pastes ? "Paste" : "Copy")).font(.callout.weight(.semibold))
                        Text("↩").font(.system(size: 12, weight: .semibold, design: .rounded)).opacity(0.75)
                    }
                    .padding(.horizontal, 6)
                }
                .buttonStyle(.glassProminent)
                .tint(Palette.indigo)
            }
            .controlSize(.large)
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 14) {
            hint("↩", model.pastes ? "Paste" : "Copy")
            if model.pastes { hint("⌘↩", "Copy") }
            hint("⌘K", "Actions")
            hint("⌘P", "Pin")
            hint("⇥", "Filter")
            Spacer(minLength: 0)
            hint("esc", "Close")
        }
        .padding(.horizontal, 18)
        .frame(height: 38)
    }

    private func hint(_ key: String, _ title: LocalizedStringKey) -> some View {
        HStack(spacing: 5) {
            KeyCap(key)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct PanelRow: View {
    let item: ClipItem
    let selected: Bool

    var body: some View {
        Hoverable { hovering in
            ClipRowView(item: item, selected: selected, hovering: hovering)
        }
    }
}

/// The ⌘K menu: everything that can be done with the chosen item, with the keys for it.
struct ClipActionsMenu: View {
    let actions: [ClipAction]
    let index: Int
    let run: (ClipAction) -> Void

    var body: some View {
        VStack(spacing: 2) {
            ForEach(Array(actions.enumerated()), id: \.element.id) { position, action in
                Hoverable { hovering in
                    HStack(spacing: 10) {
                        Image(systemName: action.symbol)
                            .font(.callout)
                            .frame(width: 20)
                            .foregroundStyle(action.destructive ? Palette.urgent : Palette.indigo)
                        Text(action.title)
                            .font(.callout)
                            .foregroundStyle(action.destructive ? Palette.urgent : Color.primary)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        if let shortcut = action.shortcut { KeyCap(shortcut) }
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 34)
                    .background {
                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                            .fill(position == index ? Palette.indigo.opacity(0.16) : Color.primary.opacity(hovering ? 0.07 : 0))
                    }
                    .contentShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .onTapGesture { run(action) }
                    .animation(.tandemFade, value: position == index)
                    .animation(.tandemFade, value: hovering)
                }
            }
        }
        .padding(6)
        .frame(width: 280)
        .modifier(PanelGlass(radius: 19))
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }
}

/// Glass for what floats. A debug snapshot cannot capture glass, so a debug run that keeps the panel open draws a
/// plain fill in its place and the layout can be checked.
private struct PanelGlass: ViewModifier {
    let radius: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if DebugSupport.keepsPanelOpen {
            content.background(Color(nsColor: .windowBackgroundColor), in: shape)
        } else {
            content.glassEffect(.regular, in: shape)
        }
    }
}
