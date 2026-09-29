import SwiftUI

/// Everything sent and received, newest first. A click opens the file, a right click
/// offers the rest, and Delete takes the row off the list.
struct SharedView: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var handoff: CGFloat = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Shared")
                        .font(.largeTitle.weight(.bold))
                        .handoffSource(handoff)
                    Text("Files you send and receive. Click one to open it, right click for more.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                if model.transfers.isEmpty {
                    ContentUnavailableView(
                        "Nothing yet",
                        systemImage: "arrow.up.arrow.down",
                        description: Text("Files you send and receive show up here.")
                    )
                    .frame(maxWidth: .infinity, minHeight: 260)
                } else {
                    Card(radius: Metrics.card, padding: Metrics.cardInset) {
                        VStack(spacing: 2) {
                            ForEach(model.transfers) { item in
                                TransferEntry(item: item, peerName: model.device(item.peer)?.name ?? "?")
                                    .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                            }
                        }
                    }
                }
            }
            .padding(26)
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: model.transfers.map(\.id))
        }
        .trackScrollHandoff($handoff, start: 14, distance: 34)
        .toolbar {
            ToolbarItem(placement: .navigation) {
                HandoffTitle(progress: handoff) {
                    Label("Shared", systemImage: "arrow.up.arrow.down.circle.fill")
                        .font(.headline)
                        .labelStyle(.titleAndIcon)
                        .padding(.horizontal, 6)
                }
            }
            .sharedBackgroundVisibility(.hidden)

            if model.transfers.contains(where: { $0.state != .active }) {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        withAnimation(.tandem) { model.clearFinishedTransfers() }
                    } label: {
                        Label("Clear finished", systemImage: "xmark.bin")
                    }
                    .help("Clear finished")
                }
            }
        }
    }
}
