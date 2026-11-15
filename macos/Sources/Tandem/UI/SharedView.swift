import QuickLookThumbnailing
import SwiftUI

/// Everything sent and received, newest first. Three tiles at the top say how it stands (today, what waits for an answer, what
/// failed), and two of them narrow the list. Under them the list is a table with the size, the time and the state in columns. With
/// room for it a click picks a file to show beside the list and a double click opens it; without, a click opens it. A right click
/// and the three dots offer the rest, and Delete takes the row off the list.
struct SharedView: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var query = ""
    @LocalState private var kind: SharedKind = .all
    @LocalState private var direction: SharedDirection = .all
    @LocalState private var period: SharedPeriod = .always
    @LocalState private var onlyProblems = false
    /// Rows chosen with Command or Shift, to remove or trash at once. While any is chosen a click chooses instead of opening.
    @LocalState private var chosen: Set<String> = []
    @LocalState private var anchor: String?
    @LocalState private var kept: Set<String> = []
    @LocalState private var askTrash = false
    /// The file shown beside the list, when there is room for that.
    @LocalState private var pickedID: String?
    /// What the width of the page decides: whether the picked file fits beside the list, and how many columns the table has. Only
    /// these two are kept, so a window that is being resized redraws the page when one of them changes and not on every frame.
    private struct Room: Equatable {
        /// From this width on the picked file stands beside the list.
        static let forDetail: CGFloat = 900
        var wide: Bool
        /// The size and the time have a column while the table has room for them; the state always has one.
        var columns: TransferColumns

        init(width: CGFloat) {
            wide = width >= Self.forDetail
            columns = width - (wide ? SharedView.detailWidth + 14 : 0) >= 660 ? .all : .state
        }
    }

    @LocalState private var room = Room(width: 700)
    fileprivate static let detailWidth: CGFloat = 240
    private var wide: Bool { room.wide }
    private var columns: TransferColumns { room.columns }

    /// The file beside the list: the one that was picked, otherwise the newest.
    private var shown: TransferItem? {
        guard wide else { return nil }
        return items.first { $0.id == pickedID } ?? items.first
    }

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
                PageHeader(title: "Shared", subtitle: "Files you send and receive. Command or Shift-click to choose more.") {
                    EmptyView()
                } actions: {
                    if model.transfers.contains(where: { $0.state != .active }) {
                        Button("Clear finished", systemImage: "xmark.bin") {
                            withAnimation(.tandem) { model.clearFinishedTransfers() }
                        }
                        .buttonStyle(.tandemGlass)
                        .transition(.opacity)
                    }
                }
                if !chosen.isEmpty {
                    chosenBar.transition(.opacity.combined(with: .move(edge: .top)))
                }
                let waiting = UnderwayTransfers.shared.incoming.filter { $0.kind == .waiting && (model.sharedDeviceFilter == nil || $0.peer.device == model.sharedDeviceFilter) }
                if !model.transfers.isEmpty || !waiting.isEmpty {
                    tiles(waiting: waiting)
                }
                UnderwaySection()
                if model.transfers.isEmpty {
                    if HalfwayTransfers.shared.waiting.isEmpty && UnderwayTransfers.shared.all.isEmpty {
                    ContentUnavailableView(
                        "Nothing yet",
                        systemImage: "arrow.up.arrow.down",
                        description: Text("Files you send and receive show up here.")
                    )
                    .frame(maxWidth: .infinity, minHeight: 260)
                    }
                } else {
                    // The search and the filters on one row, like the other pages; the filters go under it when the window is narrow.
                    // The pills as tall as the search field.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) {
                            PageSearchField(text: $query, prompt: "Search by name").frame(minWidth: 160)
                            filterPills.fixedSize()
                        }
                        VStack(alignment: .leading, spacing: 10) {
                            PageSearchField(text: $query, prompt: "Search by name")
                            ScrollView(.horizontal, showsIndicators: false) { filterPills }.scrollClipDisabled()
                        }
                    }
                    .controlSize(.large)
                    if items.isEmpty {
                        ContentUnavailableView.search.frame(maxWidth: .infinity, minHeight: 200)
                    } else {
                        HStack(alignment: .top, spacing: 14) {
                            table
                            if let shown {
                                SharedDetail(item: shown)
                                    .frame(width: Self.detailWidth)
                                    // It stays in sight while the list scrolls on under the top of the page.
                                    .visualEffect { content, proxy in
                                        content.offset(y: max(0, 12 - proxy.frame(in: .scrollView(axis: .vertical)).minY))
                                    }
                                    .transition(.opacity.combined(with: .offset(x: 10)))
                            }
                        }
                    }
                }
            }
            .onGeometryChange(for: Room.self) { Room(width: $0.size.width) } action: { room = $0 }
            .pagePadding()
            // Wider than the other pages: a table has columns to show, and a file beside it.
            .frame(maxWidth: 1080, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: items.map(\.id))
            .animation(.tandem, value: chosen.isEmpty)
            .animation(.tandem, value: wide)
            .animation(.tandem, value: HalfwayTransfers.shared.waiting)
            .animation(.tandem, value: UnderwayTransfers.shared.all)
        }
        .onAppear {
            guard let count = DebugSupport.sharedChosen else { return }
            if model.transfers.isEmpty { model.transfers = DebugSupport.sharedSamples() }
            chosen = Set(items.prefix(count).map(\.id))
        }
        // What is no longer shown cannot stay chosen.
        .onChange(of: items.map(\.id)) { _, ids in chosen.formIntersection(ids) }
        .onKeyPress(.escape) {
            guard !chosen.isEmpty else { return .ignored }
            withAnimation(.tandem) { chosen = [] }
            return .handled
        }
        .confirmationDialog(trashTitle, isPresented: $askTrash) {
            Button("Move to Trash", role: .destructive) { trashChosen() }
        } message: {
            Text("Files you sent stay where they were.")
        }
    }

    // MARK: Tiles

    /// What the three tiles say: today, what waits for an answer here, and what went wrong. Today and failed also narrow the list,
    /// the way their filters do.
    private func tiles(waiting: [UnderwayTransfers.Row]) -> some View {
        let mine = model.transfers.filter { model.sharedDeviceFilter == nil || $0.peer == model.sharedDeviceFilter }
        let today = mine.filter { Calendar.current.isDateInToday($0.started) }
        let todayBytes = today.filter { $0.state == .done }.reduce(UInt64(0)) { $0 + $1.total }
        let failed = mine.filter { $0.state == .failed }.count
        return WeightedRow(weights: [1, 1.5, 1], spacing: 10) {
            SharedTile(tone: .lavender, title: "Today", on: period == .today) {
                withAnimation(.tandem) { period = period == .today ? .always : .today }
            } content: {
                TileNumber(today.count)
                Text("files, \(formatBytes(todayBytes)) arrived or sent").tileLine()
            }
            SharedTile(tone: .rose, title: "Waiting for you") {
                if let first = waiting.first {
                    let underway = UnderwayTransfers.shared
                    HStack(spacing: 12) {
                        TileNumber(waiting.count)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: underway.title(first)).font(.callout.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                            Text(verbatim: "\(underway.name(first)) \u{00B7} \(formatBytes(underway.bytes(first).total))").tileLine()
                        }
                        Spacer(minLength: 0)
                    }
                    HStack(spacing: 8) {
                        Button("Accept") { underway.accept(first) }.buttonStyle(TileButtonStyle(prominent: true))
                        Button("Decline") { underway.decline(first) }.buttonStyle(TileButtonStyle())
                    }
                } else {
                    TileNumber(0)
                    Text("Nothing is waiting").tileLine()
                }
            }
            SharedTile(tone: .peach, title: "Failed", on: onlyProblems) {
                withAnimation(.tandem) { onlyProblems.toggle() }
            } content: {
                TileNumber(failed)
                Text("did not get through").tileLine()
            }
        }
    }

    // MARK: Table

    private var table: some View {
        Card(radius: Metrics.card, padding: Metrics.cardInset) {
            LazyVStack(spacing: 2) {
                if columns == .all { tableHead }
                ForEach(items, id: \.id) { item in
                    TransferEntry(
                        item: item, peerName: model.device(item.peer)?.name ?? "?",
                        chosen: chosen.contains(item.id), onTap: { clicked(item) },
                        columns: columns, picked: shown?.id == item.id
                    )
                        .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// The names of the columns, over the rows and as wide as they are in them.
    private var tableHead: some View {
        HStack(spacing: 12) {
            // Past the tile of a row, where its name starts.
            Text("Name").padding(.leading, 52).frame(maxWidth: .infinity, alignment: .leading)
            Text("Size").frame(width: TransferRow.sizeWidth, alignment: .leading)
            Text("When").frame(width: TransferRow.whenWidth, alignment: .leading)
            // The three dots of a row come after its state.
            Text("State").frame(width: TransferRow.stateWidth, alignment: .leading).padding(.trailing, 34)
        }
        .font(.caption.weight(.bold))
        .foregroundStyle(.tertiary)
        .padding(.horizontal, 10)
        .padding(.top, 6)
        .padding(.bottom, 2)
    }

    /// A click with Command chooses a row or lets it go, with Shift everything from the row clicked last; while something is chosen a plain
    /// click does the same as Command. Otherwise a click opens the file, as before.
    private func clicked(_ item: TransferItem) {
        let flags = NSEvent.modifierFlags
        let ids = items.map(\.id)
        if flags.contains(.shift), let anchor, let a = ids.firstIndex(of: anchor), let b = ids.firstIndex(of: item.id) {
            withAnimation(.tandem) { chosen = kept.union(ids[min(a, b)...max(a, b)]) }
            return
        }
        if flags.contains(.command) || flags.contains(.shift) || !chosen.isEmpty {
            withAnimation(.tandemSpringy) {
                if chosen.contains(item.id) { chosen.remove(item.id) } else { chosen.insert(item.id) }
            }
            anchor = item.id
            kept = chosen
            return
        }
        anchor = item.id
        kept = []
        // With the file beside the list a click picks it and the second click of a double click opens it.
        if wide {
            if NSApp.currentEvent?.clickCount ?? 1 >= 2 { model.open(item) } else { withAnimation(.tandem) { pickedID = item.id } }
            return
        }
        model.open(item)
    }

    private var chosenItems: [TransferItem] { model.transfers.filter { chosen.contains($0.id) } }
    /// Only what arrived on this Mac goes to the Trash: a file that was sent is the person's own original.
    private var trashable: [TransferItem] { chosenItems.filter { $0.incoming && model.canOpen($0) } }

    private var trashTitle: String {
        trashable.count == 1 ? String(localized: "Move 1 file to the Trash?") : String(localized: "Move \(trashable.count) files to the Trash?")
    }

    /// In place of nothing, above the list while rows are chosen: how many, and what can be done with all of them.
    private var chosenBar: some View {
        HStack(spacing: 10) {
            Text(chosen.count == 1 ? String(localized: "1 chosen") : String(localized: "\(chosen.count) chosen"))
                .font(.headline)
                .contentTransition(.numericText(value: Double(chosen.count)))
            Spacer(minLength: 8)
            Button(chosen.count == items.count ? "Choose None" : "Choose All") {
                withAnimation(.tandem) { chosen = chosen.count == items.count ? [] : Set(items.map(\.id)) }
            }
            Button("Open") { chosenItems.filter(model.canOpen).forEach(model.open) }
                .disabled(!chosenItems.contains(where: model.canOpen))
            Button("Remove from List") {
                withAnimation(.tandem) {
                    model.removeFromList(chosen)
                    chosen = []
                }
            }
            Button("Move to Trash…", role: .destructive) { askTrash = true }
                .disabled(trashable.isEmpty)
                .help(trashable.isEmpty ? Text("Only files that came in on this Mac can go to the Trash") : Text("Move to Trash"))
            RowActionButton(symbol: "xmark", help: "Stop choosing") { withAnimation(.tandem) { chosen = [] } }
        }
        // The app's own pills, as everywhere else on the page: the glass buttons of the system lean towards the pointer.
        .buttonStyle(.tandemGlass)
        .controlSize(.large)
    }

    private func trashChosen() {
        let items = trashable
        var moved: Set<String> = []
        for item in items {
            guard let path = item.location, (try? FileManager.default.trashItem(at: URL(fileURLWithPath: path), resultingItemURL: nil)) != nil else { continue }
            moved.insert(item.id)
        }
        // What could not be moved stays in the list, and chosen, so it can be tried again or looked at.
        withAnimation(.tandem) {
            model.removeFromList(moved)
            chosen.subtract(moved)
        }
        if moved.count < items.count { model.showToast(String(localized: "Some files could not be moved to the Trash")) }
        else { model.showToast(String(localized: "Moved to the Trash")) }
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

// MARK: Tiles and the picked file

/// Things in a row that share its width by weight and are all as tall as the tallest.
struct WeightedRow: Layout {
    var weights: [CGFloat]
    var spacing: CGFloat = 10

    private func widths(_ width: CGFloat, count: Int) -> [CGFloat] {
        let used = Array(weights.prefix(count)) + Array(repeating: 1, count: max(0, count - weights.count))
        let room = max(0, width - spacing * CGFloat(max(0, count - 1)))
        let total = max(used.reduce(0, +), 0.01)
        return used.map { room * $0 / total }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 700
        let parts = widths(width, count: subviews.count)
        let height = zip(subviews, parts).map { $0.sizeThatFits(ProposedViewSize(width: $1, height: nil)).height }.max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        for (subview, part) in zip(subviews, widths(bounds.width, count: subviews.count)) {
            subview.place(at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: part, height: bounds.height))
            x += part + spacing
        }
    }
}

/// A tile at the top of the page: what it counts, the number, and a line about it, on a ground of its own colour. One with an
/// action narrows the list: it shows a ring while it does, and a faint one under the pointer.
private struct SharedTile<Content: View>: View {
    let tone: Tone
    let title: LocalizedStringKey
    var on = false
    var action: (() -> Void)?
    @ViewBuilder var content: Content
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    init(tone: Tone, title: LocalizedStringKey, on: Bool = false, action: (() -> Void)? = nil, @ViewBuilder content: () -> Content) {
        self.tone = tone
        self.title = title
        self.on = on
        self.action = action
        self.content = content()
    }

    var body: some View {
        if let action {
            Button(action: action) { face }
                .buttonStyle(TilePressStyle())
                .onHover { hovering = $0 }
                .accessibilityAddTraits(on ? .isSelected : [])
        } else {
            face
        }
    }

    private var face: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        return VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption.weight(.bold)).opacity(0.78)
            Spacer(minLength: 0)
            content
        }
        .foregroundStyle(tone.ink)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 108, maxHeight: .infinity, alignment: .topLeading)
        .background(tone.fill, in: shape)
        .overlay { shape.strokeBorder(tone.ink.opacity(on ? 1 : (hovering && hoverEnabled && action != nil ? 0.28 : 0)), lineWidth: 2) }
        .contentShape(shape)
        .animation(.hover(hovering), value: hovering)
        .animation(.tandemFade, value: on)
    }
}

/// A tile is large: it dips less far than a button does.
private struct TilePressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

/// The large number of a tile. It rolls when it changes.
private struct TileNumber: View {
    let value: Int
    init(_ value: Int) { self.value = value }

    var body: some View {
        Text("\(value)")
            .font(.system(size: 40, weight: .bold, design: .rounded).monospacedDigit())
            .contentTransition(.numericText(value: Double(value)))
            .animation(.tandem, value: value)
            .lineLimit(1)
    }
}

extension Text {
    /// The line under the number of a tile.
    fileprivate func tileLine() -> some View {
        font(.system(size: 12.5)).lineLimit(2).fixedSize(horizontal: false, vertical: true).opacity(0.85)
    }
}

/// A button on a tile: a fill of the tile's own ink and not glass, because it sits on the coloured ground of the tile.
private struct TileButtonStyle: ButtonStyle {
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        TileButtonBody(configuration: configuration, prominent: prominent)
    }
}

private struct TileButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let prominent: Bool
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let lit = hovering && hoverEnabled
        configuration.label
            .font(.system(size: 12.5, weight: .semibold))
            .foregroundStyle(prominent ? AnyShapeStyle(Color.white) : AnyShapeStyle(.foreground))
            .padding(.horizontal, 12)
            .frame(height: 26)
            .background {
                Capsule().fill(prominent ? AnyShapeStyle(Palette.indigo) : AnyShapeStyle(.foreground.opacity(lit ? 0.2 : 0.12)))
                    .overlay { if prominent { Capsule().fill(Color.white.opacity(lit ? 0.16 : 0)) } }
            }
            .contentShape(.capsule)
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .onHover { hovering = $0 }
            .animation(.hover(hovering), value: hovering)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

/// The file that is picked on the page, beside the list when the window is wide enough for both: what it looks like, what is
/// known about it, and the two things done most with it.
private struct SharedDetail: View {
    @Environment(EngineModel.self) private var model
    let item: TransferItem
    @LocalState private var preview: NSImage?

    var body: some View {
        let peer = model.device(item.peer)?.name ?? "?"
        let kind = SharedKind.of(item.name)
        let reason = TransferReason.of(item, peerName: peer, model: model)
        let folder = model.canOpen(item) ? model.filePath(item).map { ($0 as NSString).deletingLastPathComponent } : nil
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        Card(radius: Metrics.card, padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                ZStack {
                    shape.fill(kind.tone.fill)
                    if let preview {
                        // A tall picture is cut to the frame by the clip, but would still take the clicks of what is above and under it.
                        Image(nsImage: preview).resizable().scaledToFill().allowsHitTesting(false)
                    } else {
                        Image(systemName: kind.tileSymbol).font(.system(size: 34, weight: .medium)).foregroundStyle(kind.tone.ink)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 112)
                .clipShape(shape)
                .contentShape(shape)
                Text(item.name)
                    .font(.callout.weight(.semibold))
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Grid(alignment: .topLeading, horizontalSpacing: 12, verticalSpacing: 5) {
                    fact(item.incoming ? "From" : "To", peer)
                    if item.total > 0 { fact("Size", formatBytes(item.total)) }
                    fact("When", item.updated.formatted(.relative(presentation: .named)))
                    if let reason { fact("State", reason.text, tint: reason.tint) }
                    if let folder { fact("Folder", (folder as NSString).abbreviatingWithTildeInPath) }
                }
                .font(.caption)
                if model.canOpen(item) {
                    GlassEffectContainer(spacing: 0) {
                        VStack(spacing: 8) {
                            GlassActionButton(title: "Open", symbol: "arrow.up.forward.square", prominent: true, wide: true) { model.open(item) }
                            GlassActionButton(title: "Open File Location", symbol: "folder", wide: true) { model.reveal(item) }
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task(id: item.id + (item.location ?? "")) { await loadPreview() }
        .animation(.tandemFade, value: item.id)
    }

    private func fact(_ name: LocalizedStringKey, _ value: String, tint: Color = .primary) -> some View {
        GridRow {
            Text(name).foregroundStyle(.tertiary)
            Text(verbatim: value)
                .foregroundStyle(tint)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func loadPreview() async {
        preview = nil
        guard item.state == .done, model.canOpen(item), let path = model.filePath(item) else { return }
        let request = QLThumbnailGenerator.Request(
            fileAt: URL(fileURLWithPath: path), size: CGSize(width: 212, height: 112), scale: 2, representationTypes: .thumbnail
        )
        let made = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request)
        preview = made?.nsImage
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
