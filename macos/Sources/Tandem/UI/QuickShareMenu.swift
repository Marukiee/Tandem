import AppKit
import SwiftUI
import TandemCore

/// In the menu bar panel: the devices nearby that Quick Share found, to send files to with a click or by dropping them, and what is
/// being sent now. Only there while Quick Share is on.
struct MenuQuickShare: View {
    @Bindable private var share = QuickShare.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // The switch is always there, so Quick Share goes on and off from the menu bar without opening Settings.
            HStack(spacing: 10) {
                Image(systemName: "arrow.up.arrow.down.circle.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(share.enabled ? Palette.indigo : Color.secondary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(share.enabled ? Palette.indigo.opacity(0.14) : Color.primary.opacity(0.07)))
                VStack(alignment: .leading, spacing: 1) {
                    Text("Quick Share").font(.callout.weight(.semibold))
                    Text(share.enabled ? "Visible to everyone nearby" : "Off")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                Toggle("", isOn: $share.enabled).labelsHidden().toggleStyle(.switch).controlSize(.small)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .hoverHighlight(radius: 18)
            if share.enabled {
                if share.peers.isEmpty {
                    HStack(spacing: 10) {
                        PillSpinner(size: 16)
                        Text("Looking for devices nearby")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 36)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(share.peers) { peer in PeerChip(peer: peer) }
                        }
                        .padding(.horizontal, 4)
                    }
                    .scrollClipDisabled()
                }
                ForEach(share.outgoing) { item in OutgoingRow(item: item) }
            }
        }
        .animation(.tandem, value: share.enabled)
        .animation(.tandem, value: share.peers.map(\.id))
        .animation(.tandem, value: share.outgoing.map(\.id))
    }
}

private struct PeerChip: View {
    let peer: QuickShare.Peer
    @LocalState private var hovering = false
    @LocalState private var targeted = false

    var body: some View {
        Button { QuickShare.shared.pickAndSend(to: peer) } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(targeted || hovering ? Palette.indigo : Palette.indigo.opacity(0.14))
                    Image(systemName: peer.kind.symbol)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(targeted || hovering ? Color.white : Palette.indigo)
                }
                .frame(width: 46, height: 46)
                Text(peer.name)
                    .font(.system(size: 11, weight: .medium))
                    .lineLimit(1)
                    .frame(width: 72)
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 4)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.primary.opacity(targeted ? 0.10 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(RoundPressStyle())
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: hovering)
        .animation(.tandemFade, value: targeted)
        .dropDestination(for: URL.self) { urls, _ in
            QuickShare.shared.send(urls, to: peer)
            return true
        } isTargeted: { targeted = $0 }
        .help("Send files to \(peer.name)")
    }
}

private struct OutgoingRow: View {
    let item: QuickShare.Outgoing

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(label).font(.caption.weight(.medium)).lineLimit(1)
                Spacer()
                if let pin = item.pin, item.state == .sending {
                    Text("PIN \(pin)").font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            if item.state == .sending {
                ProgressCapsule(fraction: item.total == 0 ? 0 : Double(item.done) / Double(item.total)).frame(height: 5)
            }
        }
        .padding(.horizontal, 10)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private var label: String {
        switch item.state {
        case .sending: String(localized: "Sending to \(item.peerName)")
        case .sent: String(localized: "Sent to \(item.peerName)")
        case .refused: String(localized: "\(item.peerName) said no")
        case let .failed(reason): reason
        }
    }
}

/// The settings tab of Quick Share.
struct QuickShareSettings: View {
    @Bindable private var share = QuickShare.shared

    var body: some View {
        Form {
            Section {
                DescribedToggle(
                    "Quick Share",
                    subtitle: "Send files to and receive files from Android phones and Windows PCs with Quick Share, without Google services or an account",
                    isOn: $share.enabled
                )
                if let problem = share.problem {
                    Text(problem).font(.caption).foregroundStyle(Palette.urgent)
                }
            } footer: {
                Text("While this is on, this Mac can be found by everyone on the same network. Only the mode Everyone works, not Your contacts.")
            }

            if share.enabled {
                Section {
                    Label {
                        Text("On the other device open Quick Share and pick this Mac. Both have to be on the same Wi-Fi.")
                    } icon: {
                        Image(systemName: "iphone.gen3").foregroundStyle(Palette.indigo)
                    }
                    Label {
                        Text("To send, use the Quick Share row in the menu bar panel: click a device, or drop files on it.")
                    } icon: {
                        Image(systemName: "paperplane").foregroundStyle(Palette.indigo)
                    }
                    Label {
                        Text("Received files go to your download folder, the same as the files of your other devices.")
                    } icon: {
                        Image(systemName: "arrow.down.circle").foregroundStyle(Palette.indigo)
                    }
                } header: {
                    Text("How it works")
                }

                Section {
                    if share.peers.isEmpty {
                        Text("Nothing found yet. On the other device, open Quick Share and set it to be seen by everyone.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(share.peers) { peer in
                        LabeledContent {
                            Button("Send files…") { share.pickAndSend(to: peer) }
                        } label: {
                            Label(peer.name, systemImage: peer.kind.symbol)
                        }
                    }
                } header: {
                    Text("Nearby devices")
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: share.enabled)
    }
}
