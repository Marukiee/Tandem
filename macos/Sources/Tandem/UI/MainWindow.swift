import AppKit
import SwiftUI
import TandemCore

enum SidebarSelection: Hashable {
    case device(String)
    case transfers
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
                    .transition(.opacity)
            }
        }
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
        .onChange(of: model.devices.map(\.id)) { _, ids in
            if case let .device(id) = selection, !ids.contains(id) { selection = ids.first.map { .device($0) } }
            if selection == nil, let first = ids.first { selection = .device(first) }
        }
        .onAppear {
            if selection == nil, let first = model.devices.first { selection = .device(first.id) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .tandemShowPairing)) { _ in showPairing = true }
    }

    @ViewBuilder
    private var detail: some View {
        if let error = model.startError {
            StartFailure(message: error)
        } else if !model.ready {
            VStack(spacing: 14) {
                PillSpinner(size: 40)
                Text("Starting").foregroundStyle(.secondary)
            }
        } else if model.devices.isEmpty {
            ScrollView { WelcomeView() }
        } else {
            switch selection {
            case let .device(id):
                if let device = model.device(id) {
                    DeviceDetail(device: device).id(id)
                } else {
                    Color.clear
                }
            case .transfers:
                TransfersView()
            case nil:
                Color.clear
            }
        }
    }
}

extension Notification.Name {
    static let tandemShowPairing = Notification.Name("tandem.showPairing")
}

// MARK: Sidebar

private struct Sidebar: View {
    @Environment(EngineModel.self) private var model
    @Binding var selection: SidebarSelection?
    @Binding var showPairing: Bool

    var body: some View {
        List(selection: $selection) {
            Section {
                ForEach(model.devices, id: \.id) { device in
                    DeviceRow(device: device)
                        .tag(SidebarSelection.device(device.id))
                }
            } header: {
                Text("Devices")
            }

            Section {
                Label {
                    Text("Transfers")
                } icon: {
                    Image(systemName: "arrow.up.arrow.down")
                }
                .badge(model.transfers.filter { $0.state == .active }.count)
                .tag(SidebarSelection.transfers)
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack(spacing: 10) {
                Capsule().fill(Palette.indigo.gradient).frame(width: 12, height: 26).rotationEffect(.degrees(30))
                Capsule().fill(Palette.rose.gradient).frame(width: 12, height: 26).rotationEffect(.degrees(30)).offset(x: -14)
                Text("Tandem").font(.title3.weight(.bold)).padding(.leading, -6)
                Spacer()
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 6)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                showPairing = true
            } label: {
                Label("Pair a device", systemImage: "plus")
                    .font(.callout.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
            .padding(14)
        }
    }
}

private struct DeviceRow: View {
    let device: TandemDevice

    var body: some View {
        HStack(spacing: 12) {
            DeviceGlyph(platform: device.platform, online: device.online, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                HStack(spacing: 6) {
                    PresenceDot(online: device.online)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let battery = device.status.battery {
                HStack(spacing: 2) {
                    if battery.charging { Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.green) }
                    Text("\(battery.level)%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .contentTransition(.numericText(value: Double(battery.level)))
                }
            }
        }
        .padding(.vertical, 3)
        .animation(.tandem, value: device.online)
    }

    private var subtitle: String {
        guard device.online else { return String(localized: "Offline") }
        var parts = [String(localized: "Online")]
        if let route = device.route {
            switch route {
            case .lan: parts.append(String(localized: "Local network"))
            case .tailnet: parts.append("Tailscale")
            case .other: parts.append("Internet")
            }
        }
        if let rtt = device.rttMs { parts.append("\(rtt) ms") }
        return parts.joined(separator: " · ")
    }
}

// MARK: Empty and error states

struct WelcomeView: View {
    var body: some View {
        VStack(spacing: 8) {
            GlassCard(radius: 34, padding: 8) {
                PairingPanel()
                    .frame(width: 460)
            }
            .padding(.top, 34)
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
        GlassCard(tint: Palette.urgent) {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(Palette.urgent)
                Text("Tandem could not start").font(.title3.weight(.semibold))
                Text(message).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .frame(width: 340)
        }
    }
}

// MARK: Transfers

struct TransfersView: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Transfers").font(.title.weight(.bold))
                    Spacer()
                    if model.transfers.contains(where: { $0.state != .active }) {
                        Button("Clear finished") { withAnimation(.tandem) { model.clearFinishedTransfers() } }
                            .buttonStyle(.glass)
                    }
                }
                if model.transfers.isEmpty {
                    ContentUnavailableView("Nothing yet", systemImage: "arrow.up.arrow.down", description: Text("Files you send and receive show up here."))
                        .frame(maxWidth: .infinity, minHeight: 260)
                } else {
                    GlassCard(radius: 26, padding: 14) {
                        VStack(spacing: 2) {
                            ForEach(model.transfers) { item in
                                TransferRow(item: item, peerName: model.device(item.peer)?.name ?? "?")
                                    .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                                if item.id != model.transfers.last?.id { Divider().opacity(0.4) }
                            }
                        }
                    }
                }
            }
            .padding(26)
            .animation(.tandem, value: model.transfers.count)
        }
    }
}
