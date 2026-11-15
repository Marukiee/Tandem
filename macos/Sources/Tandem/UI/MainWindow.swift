import AppKit
import SwiftUI
import TandemCore

extension AnyTransition {
    /// How a whole page arrives and leaves: a plain fade. The page is a scroll view under the
    /// toolbar, and moving or scaling it while it fades uncovers the strip under the toolbar
    /// for a few frames, which shows as a light bar along the top edge.
    static var page: AnyTransition { .opacity }

    /// What sits inside a page, where a slight rise is safe: a short fade with a small lift.
    static var rise: AnyTransition {
        .asymmetric(
            insertion: .opacity.combined(with: .offset(y: 14)).combined(with: .scale(scale: 0.99)),
            removal: .opacity
        )
    }
}

struct MainWindow: View {
    /// The page that was open when the window last went away, for the next time its contents are made: they are
    /// dropped while the window is closed (see `ReleasedWhenClosed`).
    @MainActor private static var remembered: SidebarSelection?
    /// Set when something wants the pairing sheet before the contents of the window exist to be told.
    @MainActor static var wantsPairing = false

    @Environment(EngineModel.self) private var model
    @LocalState private var selection: SidebarSelection? = MainWindow.remembered
    /// The device this window opened on while nothing was connected yet: the first one to connect takes over, unless the person went
    /// somewhere else. At the start of the app the connections come a few seconds after the window.
    @LocalState private var waitingOn: String?
    @LocalState private var showPairing = false
    /// The page a debug run asked for is chosen once, as soon as the devices it names have shown up.
    @LocalState private var debugChosen = false
    /// The window is too narrow for the wide sidebar, which then folds to a column of icons.
    @LocalState private var narrow = false
    /// False until the window has been measured once, so a window that opens narrow has its sidebar folded from the start.
    @LocalState private var measured = false
    /// The folded sidebar is open over the page for a moment, until the pointer leaves it.
    @LocalState private var peek = false

    /// Under this width of the window the sidebar folds.
    private static let foldBelow: CGFloat = 900

    /// A window that opens on a device that is not connected goes to one that is. Another page stays as it was left.
    private func openOnConnected() {
        guard case let .device(id) = selection, model.device(id)?.online == false else {
            waitingOn = nil
            return
        }
        if let connected = model.devices.first(where: \.online) {
            selection = .device(connected.id)
            waitingOn = nil
        } else {
            waitingOn = id
        }
    }

    // Three parts, not one long chain: as one chain the compiler on the release machines ran out of time on it.
    //
    // The sidebar is a column of our own and not the one of a split view: that one cannot fold to a column of icons, and a change of
    // its width is not animated. Ours floats over the leading edge of the window as a sheet of glass, and the page starts where it ends.
    private var window: some View {
        let folded = narrow && !peek
        return ZStack(alignment: .topLeading) {
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // While the sidebar is open over the page for a moment, the page stays where it is.
                .padding(.leading, Sidebar.margin + (narrow ? Sidebar.railWidth : Sidebar.wideWidth))
                // The window buttons sit on the sidebar, so the top of the page has nothing to carry: no bar, no edge.
                .scrollEdgeEffectHidden(true, for: .top)
                .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
                .toolbar(removing: .title)
                .animation(.tandem, value: selection)
                .onChange(of: selection) {
                    MainWindow.remembered = selection
                    if case let .messages(id) = selection { Sidebar.lastMessagesPhone = id }
                    if selection != waitingOn.map({ .device($0) }) { waitingOn = nil }
                    // A page was chosen: the sidebar that was opened over the page has done its work.
                    if peek { withAnimation(.tandemSidebar) { peek = false } }
                }
            Sidebar(selection: $selection, showPairing: $showPairing, folded: folded) {
                withAnimation(.tandemSidebar) { peek = true }
            }
            .frame(width: folded ? Sidebar.railWidth : Sidebar.wideWidth)
            .frame(maxHeight: .infinity)
            .background { SidebarGlass(lifted: peek) }
            .onGlassSurface()
            .padding(.leading, Sidebar.margin)
            .padding(.bottom, Sidebar.margin)
            .onHover { inside in
                if !inside, peek { withAnimation(.tandemSidebar) { peek = false } }
            }
        }
        .onGeometryChange(for: Bool.self) { $0.size.width < MainWindow.foldBelow } action: { tight in
            if measured {
                withAnimation(.tandemSidebar) {
                    narrow = tight
                    if !tight { peek = false }
                }
            } else {
                narrow = tight
            }
        }
        .onAppear { DispatchQueue.main.async { measured = true } }
        .modifier(EscapeKey(active: peek) { withAnimation(.tandemSidebar) { peek = false } })
        // A bar with nothing in it: the window keeps the strip along its top that the pages count on, and its rounder corners.
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
                .sharedBackgroundVisibility(.hidden)
        }
        .background(HideWindowTitle(showsBar: false))
        // A sheet or dialog covers the window; nothing behind it should react to the pointer.
        // Set before the sheet is attached, so the sheet itself is not affected.
        .environment(\.hoverEnabled, !(showPairing || model.pendingTrash != nil || model.removedFromCircle || CallConfirmation.shared.pending != nil))
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(text: toast)
                    .padding(.bottom, 26)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.tandemSpringy, value: model.toast)
        .overlay {
            if showPairing {
                PairingSheet { showPairing = false }
                    .transition(.opacity)
            }
        }
        .animation(.tandemSpringy, value: showPairing)
        .callConfirmation()
    }

    private var windowWithQuestions: some View {
        window
        .alert("This device was removed", isPresented: Binding(
            get: { model.removedFromCircle },
            set: { model.removedFromCircle = $0 }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Another device took this Mac out of your circle. To join again, reset Tandem in Settings and pair once more.")
        }
        .confirmationDialog(
            trashTitle,
            isPresented: Binding(
                get: { model.pendingTrash != nil },
                set: { if !$0 { model.pendingTrash = nil } }
            ),
            titleVisibility: .visible,
            presenting: model.pendingTrash
        ) { item in
            Button("Move to Trash", role: .destructive) { model.confirmTrash(item) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("The file is removed from this Mac. It stays on the device it came from.")
        }
    }

    private var deviceIDs: [String] { model.devices.map(\.id) }
    private var onlineIDs: [String] { model.devices.filter(\.online).map(\.id) }
    private var capCounts: [Int] { model.devices.map { $0.caps.count } }

    var body: some View {
        windowWithQuestions
        .onChange(of: deviceIDs) { _, ids in
            if case let .device(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if case let .files(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if case let .messages(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if case let .watch(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if case let .health(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            chooseDebugPage()
            if selection == nil, let first = ids.first {
                selection = .device(first)
                openOnConnected()
            }
        }
        .onChange(of: onlineIDs) {
            if waitingOn != nil { openOnConnected() }
        }
        // What a device can do is only known once it has connected, which is after its name is.
        .onChange(of: capCounts) { chooseDebugPage() }
        .onAppear {
            if let debug = DebugSupport.initialSelection(devices: model.devices) {
                selection = debug
            } else if case let .device(id) = selection, model.device(id) == nil {
                // The device that was open when the window went away has been removed since.
                selection = model.devices.first.map { .device($0.id) }
                openOnConnected()
            } else if selection == nil, let first = model.devices.first {
                selection = .device(first.id)
                openOnConnected()
            } else {
                openOnConnected()
            }
            if MainWindow.wantsPairing {
                MainWindow.wantsPairing = false
                showPairing = true
            }
        }
        .modifier(SpotlightNavigation(selection: $selection))
        .onReceive(NotificationCenter.default.publisher(for: .tandemShowPairing)) { _ in
            MainWindow.wantsPairing = false
            showPairing = true
        }
    }

    private func chooseDebugPage() {
        guard !debugChosen, let debug = DebugSupport.initialSelection(devices: model.devices) else { return }
        debugChosen = true
        selection = debug
    }

    private var trashTitle: String {
        String(localized: "Move \(model.pendingTrash?.name ?? "") to the Trash?")
    }

    @ViewBuilder
    private var detail: some View {
        if let error = model.startError {
            StartFailure(message: error).transition(.page)
        } else if !model.ready {
            VStack(spacing: 14) {
                PillSpinner(size: 40)
                Text("Starting").foregroundStyle(.secondary)
            }
            .transition(.opacity)
        } else if model.devices.isEmpty && selection != .shared && selection != .notifications && selection != .clipboard {
            ScrollView { WelcomeView() }.transition(.page)
        } else {
            switch selection {
            case let .device(id):
                if let device = model.device(id) {
                    // One DeviceDetail for every device, so its glyph and title can morph
                    // from one device to the next instead of being replaced.
                    DeviceDetail(
                        device: device,
                        onBrowse: { selection = .files(device.id) },
                        onViewAll: {
                            model.sharedDeviceFilter = device.id
                            selection = .shared
                        }
                    ).transition(.page)
                } else {
                    Color.clear
                }
            case let .files(id):
                if let device = model.device(id) {
                    FilesView(device: device, onBack: { selection = .device(id) }).transition(.page)
                } else {
                    Color.clear
                }
            case let .messages(id):
                if let device = model.device(id) {
                    // A page of its own per phone, so the conversation that was open on one does not stay open on the other.
                    MessagesView(device: device, phones: model.devices.filter(Sidebar.offersMessages), onPhone: { selection = .messages($0) })
                        .id(id)
                        .transition(.page)
                } else {
                    Color.clear
                }
            case let .watch(id):
                if let device = model.device(id), device.status.watch?.paired == true {
                    WatchDetail(phone: device, onOpenPhone: { selection = .device(id) }, onOpenHealth: { selection = .health(id) }).transition(.page)
                } else {
                    Color.clear
                }
            case let .health(id):
                if let device = model.device(id) {
                    HealthPage(phone: device, onBack: { selection = .watch(id) }).transition(.page)
                } else {
                    Color.clear
                }
            case .shared:
                SharedView().transition(.page)
            case .notifications:
                NotificationsView().transition(.page)
            case .clipboard:
                ClipboardPage().transition(.page)
            case nil:
                Color.clear
            }
        }
    }
}

/// Escape, while something is open that it should close, wherever the focus is in the window.
private struct EscapeKey: ViewModifier {
    let active: Bool
    let action: () -> Void
    @LocalState private var monitor: Any?

    func body(content: Content) -> some View {
        content
            .onChange(of: active, initial: true) { _, on in
                if on, monitor == nil {
                    monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
                        // 53 is Escape.
                        guard event.keyCode == 53 else { return event }
                        action()
                        return nil
                    }
                } else if !on {
                    stop()
                }
            }
            .onDisappear(perform: stop)
    }

    private func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }
}

/// The glass the sidebar floats on. It runs up under the strip along the top of the window, around the window's buttons.
private struct SidebarGlass: View {
    /// Open over the page for a moment: it stands further off it.
    var lifted = false

    var body: some View {
        // Its corners turn around the same point as those of the window: the window's radius less the margin between them, so the
        // strip of window around it is as wide in a corner as along an edge. A fixed radius only fits one window: the corners of a
        // window are not the same on every macOS. In full screen the window has none, and the sheet keeps what it has in a window.
        let shape = ConcentricRectangle(corners: .concentric(minimum: .fixed(Sidebar.margin)), isUniform: true)
        Group {
            if DebugSupport.flatGlass {
                shape.fill(.regularMaterial).overlay { shape.stroke(Color.primary.opacity(0.1), lineWidth: 1) }
            } else {
                Color.clear.glassEffect(.regular, in: shape)
            }
        }
        .shadow(color: .black.opacity(lifted ? 0.24 : 0.08), radius: lifted ? 24 : 10, y: lifted ? 10 : 3)
        .padding(.top, Sidebar.margin)
        .ignoresSafeArea(.container, edges: .top)
    }
}

extension Notification.Name {
    static let tandemShowPairing = Notification.Name("tandem.showPairing")
}

// MARK: Empty and error states

struct WelcomeView: View {
    var body: some View {
        VStack(spacing: 4) {
            PillMark(size: 64, style: .plate)
                .padding(.top, 6)
            // No card around it: the code has its own tile, and a card around a tile around a code is one frame too many.
            PairingPanel()
                .frame(width: 460)
            Text("Tandem finds your devices on your own network. Nothing goes through a server.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
                .padding(.top, 6)
                .padding(.bottom, 20)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct StartFailure: View {
    let message: String

    var body: some View {
        Card(tint: Palette.urgent) {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(Palette.urgent)
                Text("Tandem could not start").font(.title3.weight(.semibold))
                Text(message).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .frame(width: 340)
        }
    }
}
