import SwiftUI

/// Everything sent and received, newest first. A click opens the file, a right click
/// offers the rest, and Delete takes the row off the list.
struct SharedView: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var query = ""

    /// What the search, and the device the page was narrowed to, leave of the list.
    private var items: [TransferItem] {
        let words = query.split(separator: " ").map { $0.lowercased() }
        return model.transfers.filter { item in
            (model.sharedDeviceFilter == nil || item.peer == model.sharedDeviceFilter)
                && words.allSatisfy { item.name.lowercased().contains($0) }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Shared")
                        .font(.largeTitle.weight(.bold))
                        
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
                    filterBar
                    if items.isEmpty {
                        ContentUnavailableView.search.frame(maxWidth: .infinity, minHeight: 200)
                    }
                    VStack(spacing: 3) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                            TransferEntry(
                                item: item, peerName: model.device(item.peer)?.name ?? "?",
                                first: index == 0, last: index == items.count - 1
                            )
                            .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                        }
                    }
                }
            }
            .pagePadding()
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: items.map(\.id))
        }
        .toolbar {
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

    /// The search, and the device the page is narrowed to with a way to let that go.
    private var filterBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search by name", text: $query).textFieldStyle(.plain)
                if !query.isEmpty {
                    Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.quaternary.opacity(0.5), in: Capsule())
            if let id = model.sharedDeviceFilter {
                Button {
                    withAnimation(.tandem) { model.sharedDeviceFilter = nil }
                } label: {
                    HStack(spacing: 6) {
                        Text(model.device(id)?.name ?? "?")
                        Image(systemName: "xmark").font(.caption.weight(.bold))
                    }
                    .font(.callout.weight(.medium))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Palette.indigo.opacity(0.25), in: Capsule())
                }
                .buttonStyle(.plain)
                .help("Show everything again")
            }
        }
    }
}
