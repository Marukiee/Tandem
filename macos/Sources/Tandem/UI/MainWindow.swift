import AppKit
import SwiftUI
import TandemCore

extension AnyTransition {
    /// How a page arrives and leaves: a short fade with a slight rise, never a snap.
    static var page: AnyTransition {
        .asymmetric(
            insertion: .opacity.combined(with: .offset(y: 14)).combined(with: .scale(scale: 0.99)),
            removal: .opacity
        )
    }
}

struct MainWindow: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var selection: SidebarSelection?
    @LocalState private var showPairing = false

    var body: some View {
        NavigationSplitView {
            Sidebar(selection: $selection, showPairing: $showPairing)
                .navigationSplitViewColumnWidth(min: 250, ideal: 280, max: 340)
        } detail: {
            ZStack {
                AmbientBackdrop(active: model.isTransferring)
                    .ignoresSafeArea()
                detail
            }
            .safeAreaInset(edge: .top, spacing: 0) { UpdateBanner() }
            // The sidebar header already says Tandem. What the toolbar shows is the
            // device you are on, once its card has scrolled away.
            .toolbar(removing: .title)
            .animation(.tandem, value: selection)
        }
        .background(HideWindowTitle())
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
            if selection == nil, let first = ids.first { selection = .device(first) }
        }
        .onAppear {
            if let debug = DebugSupport.initialSelection(devices: model.devices.map(\.id)) {
                selection = debug
            } else if selection == nil, let first = model.devices.first {
                selection = .device(first.id)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .tandemShowPairing)) { _ in showPairing = true }
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
        } else if model.devices.isEmpty && selection != .shared {
            ScrollView { WelcomeView() }.transition(.page)
        } else {
            switch selection {
            case let .device(id):
                if let device = model.device(id) {
                    // One DeviceDetail for every device, so its glyph and title can morph
                    // from one device to the next instead of being replaced.
                    DeviceDetail(device: device).transition(.page)
                } else {
                    Color.clear
                }
            case .shared:
                SharedView().transition(.page)
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
