import SwiftUI

/// Everything sent and received, newest first. A click opens the file, a right click
/// offers the rest, and Delete takes the row off the list.
struct SharedView: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var query = ""
    @LocalState private var kind: SharedKind = .all
    @LocalState private var direction: SharedDirection = .all
    @LocalState private var period: SharedPeriod = .always
    @LocalState private var onlyProblems = false

    /// What the search and the filters, and the device the page was narrowed to, leave of the list.
    private var items: [TransferItem] {
        let words = query.split(separator: " ").map { $0.lowercased() }
        return model.transfers.filter { item in
            (model.sharedDeviceFilter == nil || item.peer == model.sharedDeviceFilter)
                && words.allSatisfy { item.name.lowercased().contains($0) }
                && (kind == .all || SharedKind.of(item.name) == kind)
                && direction.matches(item)
                && period.matches(item)
                && (!onlyProblems || item.state == .failed)
        }
    }

    private var filtering: Bool {
        // Not the search: that has its own cross, and a button that came while typing would push the row about.
        kind != .all || direction != .all || period != .always || onlyProblems || model.sharedDeviceFilter != nil
    }

    /// The devices that something was sent to or received from, for the menu of devices.
    private var peers: [(id: String, name: String)] {
        var seen = Set<String>()
        return model.transfers.compactMap { item in
            guard seen.insert(item.peer).inserted else { return nil }
            return (item.peer, model.device(item.peer)?.name ?? "?")
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PageHeader(title: "Shared", subtitle: "Files you send and receive. Click one to open it, right click for more.")
                if model.transfers.isEmpty {
                    ContentUnavailableView(
                        "Nothing yet",
                        systemImage: "arrow.up.arrow.down",
                        description: Text("Files you send and receive show up here.")
                    )
                    .frame(maxWidth: .infinity, minHeight: 260)
                } else {
                    // The search and the filters on one row, like the other pages; the filters go under it when the window is narrow.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) {
                            PageSearchField(text: $query, prompt: "Search by name").frame(minWidth: 200)
                            filterPills.fixedSize()
                        }
                        VStack(alignment: .leading, spacing: 10) {
                            PageSearchField(text: $query, prompt: "Search by name")
                            ScrollView(.horizontal, showsIndicators: false) { filterPills }.scrollClipDisabled()
                        }
                    }
                    if items.isEmpty {
                        ContentUnavailableView.search.frame(maxWidth: .infinity, minHeight: 200)
                    } else {
                        Card(radius: Metrics.card, padding: Metrics.cardInset) {
                            LazyVStack(spacing: 2) {
                                ForEach(items, id: \.id) { item in
                                    TransferEntry(item: item, peerName: model.device(item.peer)?.name ?? "?")
                                        .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                                }
                            }
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
            // The buttons at the trailing end, where the system's own apps have them.
            ToolbarSpacer(.flexible)
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

    /// The filters: what kind of file, who it was with, which way it went, when, and whether it went wrong. A filter that is on is
    /// coloured, and one press on "Clear filters" lets all of them go.
    private var filterPills: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: 8) {
                FilterMenu(title: Text(kind == .all ? "Type" : kind.title), symbol: kind.symbol, active: kind != .all) {
                    ForEach(SharedKind.allCases) { option in
                        Button { withAnimation(.tandem) { kind = option } } label: {
                            Label(option.title, systemImage: kind == option ? "checkmark" : option.symbol)
                        }
                    }
                }
                FilterMenu(
                    title: model.sharedDeviceFilter.flatMap { model.device($0)?.name }.map { Text(verbatim: $0) } ?? Text("Device"),
                    symbol: "laptopcomputer.and.iphone", active: model.sharedDeviceFilter != nil
                ) {
                    Button { withAnimation(.tandem) { model.sharedDeviceFilter = nil } } label: {
                        Label("All devices", systemImage: model.sharedDeviceFilter == nil ? "checkmark" : "circle.dashed")
                    }
                    Divider()
                    ForEach(peers, id: \.id) { peer in
                        Button { withAnimation(.tandem) { model.sharedDeviceFilter = peer.id } } label: {
                            Label(peer.name, systemImage: model.sharedDeviceFilter == peer.id ? "checkmark" : "circle")
                        }
                    }
                }
                FilterMenu(title: Text(direction == .all ? "Direction" : direction.title), symbol: direction.symbol, active: direction != .all) {
                    ForEach(SharedDirection.allCases) { option in
                        Button { withAnimation(.tandem) { direction = option } } label: {
                            Label(option.title, systemImage: direction == option ? "checkmark" : option.symbol)
                        }
                    }
                }
                FilterMenu(title: Text(period == .always ? "When" : period.title), symbol: "calendar", active: period != .always) {
                    ForEach(SharedPeriod.allCases) { option in
                        Button { withAnimation(.tandem) { period = option } } label: {
                            Label(option.title, systemImage: period == option ? "checkmark" : "calendar")
                        }
                    }
                }
                Button { withAnimation(.tandem) { onlyProblems.toggle() } } label: {
                    Label("Failed", systemImage: "exclamationmark.triangle")
                }
                .buttonStyle(TandemGlassStyle(prominent: onlyProblems))
                if filtering {
                    Button {
                        withAnimation(.tandem) {
                            query = ""
                            kind = .all
                            direction = .all
                            period = .always
                            onlyProblems = false
                            model.sharedDeviceFilter = nil
                        }
                    } label: {
                        Label("Clear filters", systemImage: "xmark")
                    }
                    .buttonStyle(.tandemGlass)
                    .transition(.opacity)
                }
            }
            .padding(.vertical, 2)
        }
    }
}

/// What a file is, by the end of its name.
enum SharedKind: String, CaseIterable, Identifiable {
    case all, pictures, videos, audio, documents, archives, other
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .all: "All types"
        case .pictures: "Pictures"
        case .videos: "Videos"
        case .audio: "Audio"
        case .documents: "Documents"
        case .archives: "Archives"
        case .other: "Other"
        }
    }

    var symbol: String {
        switch self {
        case .all: "square.grid.2x2"
        case .pictures: "photo"
        case .videos: "film"
        case .audio: "waveform"
        case .documents: "doc.text"
        case .archives: "archivebox"
        case .other: "questionmark.square.dashed"
        }
    }

    static func of(_ name: String) -> SharedKind {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff", "svg", "raw", "dng", "cr2", "nef", "arw": return .pictures
        case "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "mts": return .videos
        case "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "aiff": return .audio
        case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "ods", "odp", "csv", "pages", "numbers", "key", "epub": return .documents
        case "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "dmg", "iso", "apk", "pkg": return .archives
        default: return .other
        }
    }
}

enum SharedDirection: String, CaseIterable, Identifiable {
    case all, received, sent
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .all: "Sent and received"
        case .received: "Received"
        case .sent: "Sent"
        }
    }

    var symbol: String {
        switch self {
        case .all: "arrow.up.arrow.down"
        case .received: "arrow.down"
        case .sent: "arrow.up"
        }
    }

    func matches(_ item: TransferItem) -> Bool {
        switch self {
        case .all: true
        case .received: item.incoming
        case .sent: !item.incoming
        }
    }
}

enum SharedPeriod: String, CaseIterable, Identifiable {
    case always, today, week, month
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .always: "Any time"
        case .today: "Today"
        case .week: "Last 7 days"
        case .month: "Last 30 days"
        }
    }

    func matches(_ item: TransferItem) -> Bool {
        switch self {
        case .always: true
        case .today: Calendar.current.isDateInToday(item.started)
        case .week: item.started > Date().addingTimeInterval(-7 * 86_400)
        case .month: item.started > Date().addingTimeInterval(-30 * 86_400)
        }
    }
}
