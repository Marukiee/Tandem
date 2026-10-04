import AppKit
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

/// The files of another device: folders to open, files to fetch or put there, and what is being moved right now.
/// What the other device allows is decided there, so a button that it would refuse is simply not shown.
struct FilesView: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let onBack: () -> Void

    @LocalState private var browser = FilesBrowser()
    @LocalState private var askingFolderName = false
    @LocalState private var folderName = ""
    @LocalState private var renaming: TandemFsEntry?
    @LocalState private var newName = ""
    @LocalState private var deleting: [TandemFsEntry] = []
    @LocalState private var dropTargeted = false
    /// Rows show a check instead of the icon, and a tap chooses or lets go of a row. Starts with a hold on a row.
    @LocalState private var picking = false
    @LocalState private var rowFrames: [String: CGRect] = [:]
    @LocalState private var hold = HoldState()

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                actions
                content
                jobs
            }
            .pagePadding()
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
        .task(id: device.id) {
            browser.attach(device: device, model: model)
            if DebugSupport.mountDrive, let engine = model.tandem { model.drives.mount(device: device, engine: engine) }
        }
        .onChange(of: model.drives.state(of: device.id)) { _, state in
            if case .mounted = state { model.showToast(String(localized: "The drive is in Finder, in the sidebar under Locations")) }
        }
        .onChange(of: device.online) { _, online in
            if online {
                browser.refresh()
            } else {
                // A drive whose device has gone makes everything that touches it wait, so it goes first.
                Task { await model.drives.eject(device: device.id, engine: model.tandem) }
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard browser.writable else { return false }
            browser.upload(urls)
            return true
        } isTargeted: { dropTargeted = $0 }
        .overlay {
            if dropTargeted && browser.writable {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Palette.indigo.opacity(0.7), style: StrokeStyle(lineWidth: 2.5, dash: [8, 6]))
                    .padding(10)
                    .allowsHitTesting(false)
            }
        }
        .alert("New folder", isPresented: $askingFolderName) {
            TextField("Name", text: $folderName)
            Button("Make") { browser.makeFolder(named: folderName) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Rename") { if let entry = renaming { browser.rename(entry, to: newName) } }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(
            deleting.count == 1 ? "Delete \(deleting[0].name)?" : "Delete \(deleting.count) items?",
            isPresented: Binding(get: { !deleting.isEmpty }, set: { if !$0 { deleting = [] } }),
            titleVisibility: .visible
        ) {
            Button("Delete from \(device.name)", role: .destructive) { browser.delete(deleting) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It is removed from the other device and does not go to a trash there.")
        }
    }

    // MARK: Header

    private var header: some View {
        Card(radius: 28, padding: 18, tint: Palette.indigo) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left").font(.body.weight(.semibold))
                    }
                    .buttonStyle(.icon(size: 34))
                    .help("Back to the device")
                    DeviceGlyph(platform: device.platform, online: device.online, size: 44, deviceID: device.id)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Files on \(device.name)").font(.title3.weight(.bold))
                        Text(device.online ? "Folders this device shares with you" : "Not connected right now")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { browser.refresh() } label: {
                        Image(systemName: "arrow.clockwise").font(.body.weight(.semibold))
                    }
                    .buttonStyle(.icon(size: 34))
                    .help("Look again")
                }
                breadcrumb
                drive
            }
        }
    }

    /// The files as a drive between the other drives of Finder.
    @ViewBuilder
    private var drive: some View {
        let state = model.drives.state(of: device.id)
        HStack(spacing: 10) {
            switch state {
            case .off, .failed:
                Button { model.drives.mount(device: device, engine: model.tandem!) } label: {
                    Label("Show in Finder", systemImage: "externaldrive.badge.plus").font(.callout.weight(.medium))
                }
                .buttonStyle(.bordered)
                .disabled(!device.online || model.tandem == nil)
                .help("Open these files as a drive, so any app can use them")
                if case let .failed(message) = state {
                    Text(message).font(.caption).foregroundStyle(.red).lineLimit(2)
                } else {
                    Text("The first time, macOS asks whether Tandem may use network drives. Allow it.")
                        .font(.caption).foregroundStyle(.tertiary).lineLimit(2)
                }
            case .mounting:
                ProgressView().controlSize(.small)
                Text("Opening in Finder").font(.callout).foregroundStyle(.secondary)
            case .mounted:
                // The folder of all drives, which is a place on this Mac: this app does not go into the drive itself.
                Button { NSWorkspace.shared.open(URL(fileURLWithPath: "/Volumes", isDirectory: true)) } label: {
                    Label("Show drives in Finder", systemImage: "externaldrive.fill").font(.callout.weight(.medium))
                }
                .buttonStyle(.bordered)
                .help("The drive is in Finder, in the sidebar under Locations")
                Button { Task { await model.drives.eject(device: device.id, engine: model.tandem) } } label: {
                    Label("Eject", systemImage: "eject.fill").font(.callout.weight(.medium))
                }
                .buttonStyle(.bordered)
            }
            Spacer(minLength: 0)
        }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            if browser.hasFolderList { crumb(String(localized: "Folders"), index: 0) }
            ForEach(Array(browser.parts.enumerated()), id: \.offset) { index, part in
                if index > 0 || browser.hasFolderList {
                    Image(systemName: "chevron.right").font(.caption2.weight(.bold)).foregroundStyle(.tertiary)
                }
                crumb(part, index: index + 1)
            }
        }
        .lineLimit(1)
    }

    private func crumb(_ title: String, index: Int) -> some View {
        let last = index == browser.parts.count
        return Button { browser.go(upTo: index) } label: {
            Text(title)
                .font(.callout.weight(last ? .semibold : .regular))
                .foregroundStyle(last ? Color.primary : Color.secondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .hoverHighlight(radius: 8)
        }
        .buttonStyle(.plain)
        .disabled(last)
    }

    // MARK: Actions

    @ViewBuilder
    private var actions: some View {
        let chosen = browser.chosen
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if picking {
                    GlassActionButton(title: "Done", symbol: "checkmark", prominent: true) { stopPicking() }
                    GlassActionButton(title: "Select all", symbol: "checkmark.circle") { browser.selectAll() }
                    if !chosen.isEmpty {
                        GlassActionButton(title: "Download \(chosen.count)", symbol: "square.and.arrow.down") { browser.download(chosen) }
                        if browser.writable {
                            GlassActionButton(title: "Delete \(chosen.count)", symbol: "trash") { deleting = chosen }
                        }
                    }
                } else {
                    if browser.writable {
                        GlassActionButton(title: "Upload", symbol: "square.and.arrow.up", prominent: true) { pickFiles() }
                        GlassActionButton(title: "New folder", symbol: "folder.badge.plus") {
                            folderName = ""
                            askingFolderName = true
                        }
                    }
                    if let one = chosen.first, chosen.count == 1 {
                        GlassActionButton(title: "Download", symbol: "square.and.arrow.down") { browser.download([one]) }
                        if browser.writable {
                            GlassActionButton(title: "Rename", symbol: "pencil") {
                                newName = one.name
                                renaming = one
                            }
                            GlassActionButton(title: "Delete", symbol: "trash") { deleting = [one] }
                        }
                    }
                    if !browser.entries.isEmpty {
                        GlassActionButton(title: "Select", symbol: "checkmark.circle") { startPicking(with: browser.chosen.map(\.name)) }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .animation(.tandem, value: browser.selection)
        .animation(.tandem, value: picking)
        .animation(.tandem, value: browser.writable)
        .background {
            // Command A and Escape, for the hands that are on the keyboard.
            Button("") { startPicking(with: Array(browser.shown.map(\.name))) }
                .keyboardShortcut("a", modifiers: .command).opacity(0).frame(width: 0, height: 0)
        }
        .onExitCommand { if picking { stopPicking() } else { browser.clearSelection() } }
    }

    private func startPicking(with names: [String]) {
        withAnimation(.tandemSpringy) {
            picking = true
            browser.selection = Set(names)
        }
    }

    private func stopPicking() {
        withAnimation(.tandemSpringy) {
            picking = false
            browser.clearSelection()
        }
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { browser.upload(panel.urls) }
    }

    // MARK: Looking for something

    private var searchBar: some View {
        @Bindable var browser = browser
        return HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search in this folder", text: $browser.query)
                    .textFieldStyle(.plain)
                if !browser.query.isEmpty {
                    Button { browser.query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.quaternary.opacity(0.5), in: Capsule())
            Menu {
                ForEach(FilesBrowser.FileKind.allCases) { kind in
                    Button { browser.kind = kind } label: {
                        Label(kind.title, systemImage: browser.kind == kind ? "checkmark" : kind.symbol)
                    }
                }
            } label: {
                Label(browser.kind.title, systemImage: browser.kind.symbol)
                    .font(.callout.weight(.medium))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(browser.kind == .all ? AnyShapeStyle(.quaternary.opacity(0.5)) : AnyShapeStyle(Palette.indigo.opacity(0.25)), in: Capsule())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
        }
    }

    // MARK: What is there

    @ViewBuilder
    private var content: some View {
        if let failure = browser.failure {
            Card(radius: 22, padding: 18) {
                VStack(alignment: .leading, spacing: 12) {
                    Label(failure, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                    GlassActionButton(title: "Try again", symbol: "arrow.clockwise") { browser.refresh() }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else if browser.loading && browser.entries.isEmpty {
            VStack(spacing: 12) {
                PillSpinner(size: 34)
                Text("Looking").foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 200)
        } else if browser.entries.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: browser.path == "/" ? "folder.badge.questionmark" : "folder")
                    .font(.system(size: 34)).foregroundStyle(.tertiary)
                Text(browser.path == "/" ? "Nothing is shared" : "This folder is empty").foregroundStyle(.secondary)
                if browser.writable {
                    Text("Drop files here to put them in it.").font(.callout).foregroundStyle(.tertiary)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 200)
        } else {
            searchBar
            if browser.shown.isEmpty {
                ContentUnavailableView.search
                    .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                list
            }
        }
    }

    private var list: some View {
        LazyVStack(spacing: 2) {
            ForEach(browser.shown, id: \.name) { entry in
                FileRow(entry: entry, selected: browser.selection.contains(entry.name), picking: picking)
                    .contentShape(Rectangle())
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(key: RowFrames.self, value: [entry.name: proxy.frame(in: .named("fileList"))])
                        }
                    }
                    .onTapGesture(count: 2) {
                        guard !picking else { return }
                        browser.open(entry)
                    }
                    .onTapGesture { tapped(entry) }
                    .contextMenu {
                        let targets = picking && browser.selection.contains(entry.name) ? browser.chosen : [entry]
                        if targets.count == 1 { Button("Open") { browser.open(entry) } }
                        Button(targets.count == 1 ? "Download" : "Download \(targets.count)") { browser.download(targets) }
                        if browser.writable {
                            Divider()
                            if targets.count == 1 {
                                Button("Rename") {
                                    newName = entry.name
                                    renaming = entry
                                }
                            }
                            Button(targets.count == 1 ? "Delete" : "Delete \(targets.count)", role: .destructive) { deleting = targets }
                        }
                    }
            }
        }
        .coordinateSpace(name: "fileList")
        .onPreferenceChange(RowFrames.self) { rowFrames = $0 }
        .animation(.tandem, value: browser.shown.map(\.name))
        // A hold on a row starts choosing, and moving on from there chooses the rows the pointer passes over.
        .simultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .named("fileList"))
                .onChanged { drag in holdMoved(to: drag) }
                .onEnded { _ in holdEnded() }
        )
    }

    private func tapped(_ entry: TandemFsEntry) {
        if hold.suppressTap { return }
        let flags = NSEvent.modifierFlags
        if picking || flags.contains(.command) || flags.contains(.shift) {
            if !picking { startPicking(with: Array(browser.selection)) }
            withAnimation(.tandemSpringy) { browser.toggle(entry.name) }
            if browser.selection.isEmpty { stopPicking() }
        } else {
            browser.selection = [entry.name]
        }
    }

    // MARK: Hold and move

    /// The state of one press: where it began, whether it has been held long enough, and what was chosen before it.
    final class HoldState {
        var begun: Date?
        var start: CGPoint = .zero
        var moved = false
        var active = false
        var anchor: String?
        var kept: Set<String> = []
        var suppressTap = false
    }

    private func row(at point: CGPoint) -> String? {
        let frames = rowFrames
        guard !frames.isEmpty else { return nil }
        if let hit = frames.first(where: { $0.value.minY <= point.y && point.y < $0.value.maxY + 2 }) { return hit.key }
        // Above the first row or below the last one: the nearest end.
        let ordered = frames.sorted { $0.value.minY < $1.value.minY }
        return point.y < (ordered.first?.value.minY ?? 0) ? ordered.first?.key : ordered.last?.key
    }

    private func holdMoved(to drag: DragGesture.Value) {
        if hold.begun == nil {
            hold.begun = Date()
            hold.start = drag.startLocation
            hold.moved = false
            hold.active = false
            let began = hold.begun
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(320))
                guard hold.begun == began, !hold.moved, !hold.active, let anchor = row(at: hold.start) else { return }
                hold.active = true
                hold.suppressTap = true
                hold.anchor = anchor
                hold.kept = picking ? browser.selection : []
                withAnimation(.tandemSpringy) {
                    picking = true
                    browser.selection = hold.kept.union([anchor])
                }
                NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
            }
            return
        }
        if !hold.active {
            if hypot(drag.location.x - hold.start.x, drag.location.y - hold.start.y) > 8 { hold.moved = true }
            return
        }
        guard let anchor = hold.anchor, let name = row(at: drag.location) else { return }
        withAnimation(.tandem) { browser.select(from: anchor, to: name, keeping: hold.kept) }
    }

    private func holdEnded() {
        let wasHold = hold.active
        hold.begun = nil
        hold.active = false
        hold.anchor = nil
        // The tap that the end of a hold would count as comes right after this, and must not undo what the hold chose.
        if wasHold {
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(200))
                hold.suppressTap = false
            }
        } else {
            hold.suppressTap = false
        }
    }

    // MARK: What is moving

    @ViewBuilder
    private var jobs: some View {
        if !browser.jobs.isEmpty {
            VStack(spacing: 8) {
                ForEach(browser.jobs) { job in
                    Card(radius: 18, padding: 12) {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(job.title).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                                if let failure = job.failure {
                                    Text(failure).font(.caption).foregroundStyle(.red)
                                } else if job.finished {
                                    Text("Done").font(.caption).foregroundStyle(.secondary)
                                } else if job.total > 0 {
                                    ProgressView(value: Double(job.done), total: Double(job.total))
                                    Text("\(formatBytes(job.done)) of \(formatBytes(job.total))").font(.caption).foregroundStyle(.secondary)
                                } else {
                                    ProgressView().controlSize(.small)
                                }
                            }
                            Spacer(minLength: 0)
                            if job.failure != nil {
                                Button { browser.dismiss(job) } label: { Image(systemName: "xmark") }
                                    .buttonStyle(.icon(size: 28))
                            }
                        }
                    }
                    .transition(.rise)
                }
            }
            .animation(.tandem, value: browser.jobs)
        }
    }
}

private struct RowFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { _, new in new }
    }
}

/// A file or a folder in a list. While choosing, a check takes the place of the icon.
private struct FileRow: View {
    let entry: TandemFsEntry
    let selected: Bool
    let picking: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                icon.opacity(picking ? 0 : 1).scaleEffect(picking ? 0.6 : 1)
                check.opacity(picking ? 1 : 0).scaleEffect(picking ? 1 : 0.6)
            }
            .frame(width: 28, height: 28)
            .animation(.tandemSpringy, value: picking)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            if entry.readonly {
                Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer(minLength: 12)
            Text(entry.modifiedMs == 0 ? "" : Date(timeIntervalSince1970: Double(entry.modifiedMs) / 1000).formatted(date: .abbreviated, time: .shortened))
                .font(.callout).foregroundStyle(.secondary)
                .frame(minWidth: 150, alignment: .trailing)
            Text(entry.dir ? "" : formatBytes(entry.size))
                .font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                .frame(width: 80, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? Palette.indigo.opacity(0.22) : Color.clear)
        }
        .hoverHighlight(radius: 12, selected: selected)
    }

    /// A ring that fills with a check, so what is chosen can be seen at a glance down the whole list.
    private var check: some View {
        ZStack {
            Circle().strokeBorder(Color.secondary.opacity(0.5), lineWidth: 1.5)
            Circle().fill(Palette.indigo).scaleEffect(selected ? 1 : 0.2).opacity(selected ? 1 : 0)
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white)
                .scaleEffect(selected ? 1 : 0.2)
                .opacity(selected ? 1 : 0)
        }
        .frame(width: 24, height: 24)
        .animation(.tandemSpringy, value: selected)
    }

    @ViewBuilder
    private var icon: some View {
        if entry.dir {
            Image(systemName: "folder.fill").font(.title3).foregroundStyle(Palette.indigo)
        } else {
            let type = UTType(filenameExtension: (entry.name as NSString).pathExtension) ?? .data
            Image(nsImage: NSWorkspace.shared.icon(for: type)).resizable().scaledToFit()
        }
    }
}
