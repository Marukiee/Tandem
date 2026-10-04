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
        .task(id: device.id) { browser.attach(device: device, model: model) }
        .onChange(of: device.online) { _, online in if online { browser.refresh() } }
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
            }
        }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            crumb(String(localized: "Folders"), index: 0)
            ForEach(Array(browser.parts.enumerated()), id: \.offset) { index, part in
                Image(systemName: "chevron.right").font(.caption2.weight(.bold)).foregroundStyle(.tertiary)
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
        let chosen = browser.selected
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if browser.writable {
                    GlassActionButton(title: "Upload", symbol: "square.and.arrow.up", prominent: true) { pickFiles() }
                    GlassActionButton(title: "New folder", symbol: "folder.badge.plus") {
                        folderName = ""
                        askingFolderName = true
                    }
                }
                if let chosen {
                    GlassActionButton(title: "Download", symbol: "square.and.arrow.down") { browser.download([chosen]) }
                    if browser.writable {
                        GlassActionButton(title: "Rename", symbol: "pencil") {
                            newName = chosen.name
                            renaming = chosen
                        }
                        GlassActionButton(title: "Delete", symbol: "trash") { deleting = [chosen] }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .animation(.tandem, value: browser.selection)
        .animation(.tandem, value: browser.writable)
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { browser.upload(panel.urls) }
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
            LazyVStack(spacing: 2) {
                ForEach(browser.entries, id: \.name) { entry in
                    FileRow(entry: entry, selected: browser.selection == entry.name)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) { browser.open(entry) }
                        .onTapGesture { browser.selection = entry.name }
                        .contextMenu {
                            Button("Open") { browser.open(entry) }
                            Button("Download") { browser.download([entry]) }
                            if browser.writable {
                                Divider()
                                Button("Rename") {
                                    newName = entry.name
                                    renaming = entry
                                }
                                Button("Delete", role: .destructive) { deleting = [entry] }
                            }
                        }
                }
            }
            .animation(.tandem, value: browser.entries.map(\.name))
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

/// A file or a folder in a list.
private struct FileRow: View {
    let entry: TandemFsEntry
    let selected: Bool

    var body: some View {
        HStack(spacing: 12) {
            icon.frame(width: 28, height: 28)
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
