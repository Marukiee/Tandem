import AppKit
import Foundation
import Observation
import TandemCore

/// One file or folder on its way between this Mac and the other device, for the strip at the foot of the page.
struct FileJob: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var done: UInt64 = 0
    var total: UInt64 = 0
    var failure: String?
    var finished = false
}

/// Tells the browser how far a file has come, but not at every chunk: a fast link makes hundreds of them a second and
/// a page that redraws for each would be busier than the transfer.
private final class JobProgress: TandemFsProgress, @unchecked Sendable {
    private let update: @Sendable (UInt64, UInt64) -> Void
    private let lock = NSLock()
    private var last = Date.distantPast

    init(update: @escaping @Sendable (UInt64, UInt64) -> Void) {
        self.update = update
    }

    func progress(done: UInt64, total: UInt64) {
        lock.lock()
        let now = Date()
        let due = now.timeIntervalSince(last) >= 0.15 || done >= total
        if due { last = now }
        lock.unlock()
        if due { update(done, total) }
    }
}

/// Looking at the files of another device: where we are, what is there, and what is being moved.
@MainActor @Observable
final class FilesBrowser {
    private(set) var deviceId = ""
    private(set) var deviceName = ""
    /// What is being looked at: `/` for the list of shared folders, or `/Share/folder`.
    private(set) var path = "/"
    private(set) var entries: [TandemFsEntry] = []
    private(set) var loading = false
    private(set) var failure: String?
    private(set) var jobs: [FileJob] = []
    /// The folders the other device shares.
    private(set) var roots: [TandemFsRoot] = []
    /// The name of the chosen row.
    var selection: String?

    @ObservationIgnored private var model: EngineModel?
    @ObservationIgnored private var changeable: [String: Bool] = [:]
    @ObservationIgnored private var generation = 0

    var parts: [String] { path.split(separator: "/").map(String.init) }

    /// Whether the list of shared folders is a place worth going to. With a single folder there is nothing to choose,
    /// so the page starts inside it and has no list to go back to.
    var hasFolderList: Bool { roots.count != 1 }

    /// Whether things can be put here and changed: not in the list of folders, and only where the other device allows it.
    var writable: Bool {
        guard let share = parts.first else { return false }
        return changeable[share] ?? false
    }

    var selected: TandemFsEntry? { entries.first { $0.name == selection } }

    func attach(device: TandemDevice, model: EngineModel) {
        self.model = model
        guard deviceId != device.id else { return }
        deviceId = device.id
        deviceName = device.name
        path = DebugSupport.filesPath ?? "/"
        roots = []
        entries = []
        selection = DebugSupport.filesSelection
        jobs = []
        refresh()
    }

    // MARK: Looking

    func refresh() {
        guard let engine = model?.tandem else { return }
        generation += 1
        let mine = generation
        let (id, path) = (deviceId, self.path)
        loading = true
        failure = nil
        Task {
            do {
                let roots = try await engine.fsRoots(id: id)
                // Asked for the list of folders while there is only one: look inside it instead.
                let target = path == "/" && roots.count == 1 ? "/" + roots[0].name : path
                let items = try await engine.fsList(id: id, path: target)
                guard mine == generation else { return }
                self.roots = roots
                if target != path { self.path = target }
                changeable = Dictionary(uniqueKeysWithValues: roots.map { ($0.name, $0.write) })
                entries = items.sorted { a, b in
                    if a.dir != b.dir { return a.dir }
                    return a.name.localizedStandardCompare(b.name) == .orderedAscending
                }
                if let chosen = selection, !items.contains(where: { $0.name == chosen }) { selection = nil }
                loading = false
            } catch {
                guard mine == generation else { return }
                entries = []
                failure = describe(error)
                loading = false
            }
        }
    }

    func go(to newPath: String) {
        path = newPath
        selection = nil
        refresh()
    }

    func into(_ folder: String) {
        go(to: (path == "/" ? "" : path) + "/" + folder)
    }

    func up() {
        let kept = parts.dropLast()
        guard !kept.isEmpty || hasFolderList else { return }
        go(to: kept.isEmpty ? "/" : "/" + kept.joined(separator: "/"))
    }

    /// Shows the first `count` parts of the path, which is what a click on a part of the breadcrumb asks for.
    func go(upTo count: Int) {
        let kept = parts.prefix(count)
        go(to: kept.isEmpty ? "/" : "/" + kept.joined(separator: "/"))
    }

    private func child(_ name: String, in folder: String? = nil) -> String {
        let base = folder ?? path
        return (base == "/" ? "" : base) + "/" + name
    }

    // MARK: Moving

    func open(_ entry: TandemFsEntry) {
        if entry.dir {
            into(entry.name)
        } else {
            openFile(entry)
        }
    }

    /// A file to a folder of its own in the temporary files, and then to whatever opens it.
    private func openFile(_ entry: TandemFsEntry) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("TandemFiles", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(entry.name)
        let remote = child(entry.name)
        run(title: entry.name, total: entry.size) { engine, id, progress in
            try await engine.fsDownload(id: id, path: remote, to: target.path, progress: progress)
            await MainActor.run { _ = NSWorkspace.shared.open(target) }
        }
    }

    /// Files and folders to the folder of this Mac where received files go, folders with everything in them.
    func download(_ chosen: [TandemFsEntry]) {
        let folder = DownloadFolder.url
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for entry in chosen {
            let remote = child(entry.name)
            let target = Self.uniqueURL(in: folder, name: entry.name)
            run(title: entry.name, total: entry.size) { engine, id, progress in
                if entry.dir {
                    try await Self.downloadFolder(engine: engine, id: id, remote: remote, to: target)
                } else {
                    try await engine.fsDownload(id: id, path: remote, to: target.path, progress: progress)
                }
                await MainActor.run { NSWorkspace.shared.activateFileViewerSelecting([target]) }
            }
        }
    }

    private nonisolated static func downloadFolder(engine: TandemEngine, id: String, remote: String, to local: URL) async throws {
        try FileManager.default.createDirectory(at: local, withIntermediateDirectories: true)
        for item in try await engine.fsList(id: id, path: remote) {
            let path = (remote == "/" ? "" : remote) + "/" + item.name
            let target = local.appendingPathComponent(item.name)
            if item.dir {
                try await downloadFolder(engine: engine, id: id, remote: path, to: target)
            } else {
                try await engine.fsDownload(id: id, path: path, to: target.path, progress: nil)
            }
        }
    }

    /// Files and folders from this Mac into the folder that is open. A name that is taken gets a number instead of
    /// replacing what is there.
    func upload(_ urls: [URL]) {
        guard writable else { return }
        let taken = Set(entries.map(\.name))
        for url in urls {
            let isFolder = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            let name = Self.freeName(url.lastPathComponent, taken: taken)
            let remote = child(name)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map { UInt64($0) } ?? 0
            run(title: name, total: size) { engine, id, progress in
                if isFolder {
                    try await Self.uploadFolder(engine: engine, id: id, from: url, to: remote)
                } else {
                    try await engine.fsUpload(id: id, from: url.path, path: remote, overwrite: false, progress: progress)
                }
            }
        }
    }

    private nonisolated static func uploadFolder(engine: TandemEngine, id: String, from local: URL, to remote: String) async throws {
        try await engine.fsMkdir(id: id, path: remote)
        let items = try FileManager.default.contentsOfDirectory(at: local, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
        for item in items {
            let path = remote + "/" + item.lastPathComponent
            if (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false {
                try await uploadFolder(engine: engine, id: id, from: item, to: path)
            } else {
                try await engine.fsUpload(id: id, from: item.path, path: path, overwrite: false, progress: nil)
            }
        }
    }

    func makeFolder(named name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard writable, !clean.isEmpty, !clean.contains("/") else { return }
        let remote = child(clean)
        run(title: clean, total: 0, then: { [weak self] in self?.refresh() }) { engine, id, _ in
            try await engine.fsMkdir(id: id, path: remote)
        }
    }

    func rename(_ entry: TandemFsEntry, to name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard writable, !clean.isEmpty, !clean.contains("/"), clean != entry.name else { return }
        let (from, to) = (child(entry.name), child(clean))
        selection = clean
        run(title: clean, total: 0, then: { [weak self] in self?.refresh() }) { engine, id, _ in
            try await engine.fsRename(id: id, from: from, to: to, overwrite: false)
        }
    }

    func delete(_ chosen: [TandemFsEntry]) {
        guard writable else { return }
        for entry in chosen {
            let remote = child(entry.name)
            run(title: entry.name, total: 0, then: { [weak self] in self?.refresh() }) { engine, id, _ in
                try await engine.fsRemove(id: id, path: remote, recursive: entry.dir)
            }
        }
        selection = nil
    }

    func dismiss(_ job: FileJob) {
        jobs.removeAll { $0.id == job.id }
    }

    // MARK: Running

    /// Does one thing against the other device, with a line in the strip at the foot that shows how it goes.
    private func run(
        title: String,
        total: UInt64,
        then done: (@MainActor () -> Void)? = nil,
        _ work: @escaping @Sendable (TandemEngine, String, TandemFsProgress) async throws -> Void
    ) {
        guard let engine = model?.tandem else { return }
        let id = deviceId
        let job = FileJob(title: title, total: total)
        jobs.append(job)
        let progress = JobProgress { [weak self] done, total in
            Task { @MainActor in self?.update(job.id) { $0.done = done; $0.total = max($0.total, total) } }
        }
        Task {
            do {
                try await work(engine, id, progress)
                update(job.id) { $0.finished = true; $0.done = max($0.done, $0.total) }
                done?()
                // A line that says it went well is gone a moment later.
                try? await Task.sleep(for: .seconds(2.5))
                jobs.removeAll { $0.id == job.id }
                if path != "/" { refreshQuietly() }
            } catch {
                update(job.id) { $0.failure = describe(error) }
                refreshQuietly()
            }
        }
    }

    /// What is on screen after a change, without the spinner that a first look has.
    private func refreshQuietly() {
        guard let engine = model?.tandem else { return }
        let (id, path) = (deviceId, self.path)
        Task {
            guard let items = try? await engine.fsList(id: id, path: path), path == self.path else { return }
            entries = items.sorted { a, b in
                if a.dir != b.dir { return a.dir }
                return a.name.localizedStandardCompare(b.name) == .orderedAscending
            }
        }
    }

    private func update(_ id: UUID, _ change: (inout FileJob) -> Void) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        change(&jobs[index])
    }

    // MARK: Words

    /// What went wrong, in words for a person.
    func describe(_ error: Error) -> String {
        if case let TandemError.Files(code, reason) = error {
            switch code {
            case "disabled":
                return String(localized: "\(deviceName) does not share its files. Turn it on in Tandem on that device, under File access.")
            case "denied" where reason.localizedCaseInsensitiveContains("permission"):
                return String(localized: "Tandem on \(deviceName) is not allowed to reach its files. Allow access to all files in Tandem on that device, under File access.")
            case "denied":
                return String(localized: "\(deviceName) does not allow that.")
            case "not_found":
                return String(localized: "That is gone.")
            case "exists":
                return String(localized: "There is something with that name already.")
            case "not_empty":
                return String(localized: "That folder is not empty.")
            default:
                return reason
            }
        }
        if case TandemError.NotConnected = error {
            return String(localized: "\(deviceName) is not connected right now.")
        }
        return error.localizedDescription
    }

    // MARK: Names

    private nonisolated static func uniqueURL(in folder: URL, name: String) -> URL {
        var candidate = folder.appendingPathComponent(name)
        var number = 2
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = folder.appendingPathComponent(ext.isEmpty ? "\(base) \(number)" : "\(base) \(number).\(ext)")
            number += 1
        }
        return candidate
    }

    private nonisolated static func freeName(_ name: String, taken: Set<String>) -> String {
        guard taken.contains(name) else { return name }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var number = 2
        while true {
            let candidate = ext.isEmpty ? "\(base) \(number)" : "\(base) \(number).\(ext)"
            if !taken.contains(candidate) { return candidate }
            number += 1
        }
    }
}
