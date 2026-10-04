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
    @LocalState private var showPairing = false
    /// The page a debug run asked for is chosen once, as soon as the devices it names have shown up.
    @LocalState private var debugChosen = false
    /// True for a moment after the page changes. A new scroll view draws its top edge effect
    /// (a light band under the toolbar) before it knows where its content starts, which
    /// shows as a white bar for a few frames. Hidden while the page settles.
    @LocalState private var settling = false
    /// Whether the sidebar is shown. The bar along the top of the page is only wanted when it is not.
    @LocalState private var columnVisibility: NavigationSplitViewVisibility = .all

    private var sidebarShown: Bool { columnVisibility != .detailOnly }

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            Sidebar(selection: $selection, showPairing: $showPairing)
                .navigationSplitViewColumnWidth(min: 250, ideal: 280, max: 340)
        } detail: {
            ZStack {
                AmbientBackdrop(active: model.isTransferring)
                    .ignoresSafeArea()
                detail
            }
            .scrollEdgeEffectHidden(settling || sidebarShown, for: .top)
            .toolbarBackgroundVisibility(sidebarShown ? .hidden : .automatic, for: .windowToolbar)
            // The sidebar header already says Tandem. What the toolbar shows is the
            // device you are on, once its card has scrolled away.
            .toolbar(removing: .title)
            .animation(.tandem, value: selection)
            .onChange(of: selection) {
                MainWindow.remembered = selection
                settling = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { settling = false }
            }
        }
        .background(HideWindowTitle(showsBar: !sidebarShown))
        // A sheet or dialog covers the window; nothing behind it should react to the pointer.
        // Set before the sheet is attached, so the sheet itself is not affected.
        .environment(\.hoverEnabled, !(showPairing || model.pendingTrash != nil || model.removedFromCircle))
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(text: toast)
                    .padding(.bottom, 26)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.tandemSpringy, value: model.toast)
        .sheet(isPresented: $showPairing) { PairingSheet() }
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
        .onChange(of: model.devices.map(\.id)) { _, ids in
            if case let .device(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if case let .files(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            chooseDebugPage()
            if selection == nil, let first = ids.first { selection = .device(first) }
        }
        // What a device can do is only known once it has connected, which is after its name is.
        .onChange(of: model.devices.map { $0.caps.count }) { chooseDebugPage() }
        .onAppear {
            if let debug = DebugSupport.initialSelection(devices: model.devices) {
                selection = debug
            } else if case let .device(id) = selection, model.device(id) == nil {
                // The device that was open when the window went away has been removed since.
                selection = model.devices.first.map { .device($0.id) }
            } else if selection == nil, let first = model.devices.first {
                selection = .device(first.id)
            }
            if MainWindow.wantsPairing {
                MainWindow.wantsPairing = false
                showPairing = true
            }
        }
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
        } else if model.devices.isEmpty && selection != .shared && selection != .notifications {
            ScrollView { WelcomeView() }.transition(.page)
        } else {
            switch selection {
            case let .device(id):
                if let device = model.device(id) {
                    // One DeviceDetail for every device, so its glyph and title can morph
                    // from one device to the next instead of being replaced.
                    DeviceDetail(device: device, onBrowse: { selection = .files(device.id) }).transition(.page)
                } else {
                    Color.clear
                }
            case let .files(id):
                if let device = model.device(id) {
                    FilesView(device: device, onBack: { selection = .device(id) }).transition(.page)
                } else {
                    Color.clear
                }
            case .shared:
                SharedView().transition(.page)
            case .notifications:
                NotificationsView().transition(.page)
            case nil:
                Color.clear
            }
        }
    }
}

extension Notification.Name {
    static let tandemShowPairing = Notification.Name("tandem.showPairing")
}

// MARK: Empty and error states

struct WelcomeView: View {
    var body: some View {
        VStack(spacing: 8) {
            PillMark(size: 84, style: .plate)
                .padding(.top, 30)
            Card(radius: 34, padding: 8) {
                PairingPanel()
                    .frame(width: 460)
            }
            .padding(.top, 10)
            Text("Tandem finds your devices on your own network and over Tailscale. Nothing goes through a server.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
                .padding(.vertical, 18)
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
