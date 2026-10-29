import FileProvider
import Foundation
import UniformTypeIdentifiers

/// The files of one phone as a place in the Finder sidebar. The app adds a domain for each phone that has it turned on
/// (`FilesInFinder`), and the system starts this extension for it. Everything goes to the WebDAV server that the app runs
/// for that phone on the loopback address: folders are listed when they are opened, a file is fetched when it is opened,
/// and what is saved goes back whole.
@objc(TandemFilesExtension)
final class TandemFilesExtension: NSObject, NSFileProviderReplicatedExtension {
    private let domain: NSFileProviderDomain
    private let client: WebDAVClient
    private let ids: Identities
    private let seen: SeenFolders

    required init(domain: NSFileProviderDomain) {
        self.domain = domain
        let place = SharedPlace(domain: domain.identifier.rawValue)
        client = WebDAVClient { place.credentials() }
        ids = Identities(file: place.identitiesFile)
        seen = SeenFolders()
        super.init()
    }

    func invalidate() {
        ids.save()
    }

    // MARK: Items

    func item(for identifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest, completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void) -> Progress {
        run(completionHandler: { completionHandler(nil, $0) }) {
            if identifier == .rootContainer {
                completionHandler(Item.root(named: self.domain.displayName), nil)
                return
            }
            guard let path = self.ids.path(for: identifier) else { throw NSFileProviderError(.noSuchItem) }
            completionHandler(self.item(for: try await self.client.stat(path)), nil)
        }
    }

    func fetchContents(for itemIdentifier: NSFileProviderItemIdentifier, version requestedVersion: NSFileProviderItemVersion?, request: NSFileProviderRequest, completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void) -> Progress {
        run(completionHandler: { completionHandler(nil, nil, $0) }) {
            guard let path = self.ids.path(for: itemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
            let entry = try await self.client.stat(path)
            // The system moves the file from here into place, so it has to be on the same volume, which this folder is.
            let folder = try NSFileProviderManager(for: self.domain)?.temporaryDirectoryURL() ?? FileManager.default.temporaryDirectory
            let file = folder.appendingPathComponent(UUID().uuidString)
            try await self.client.download(path, to: file)
            completionHandler(file, self.item(for: entry), nil)
        }
    }

    func createItem(basedOn itemTemplate: NSFileProviderItem, fields: NSFileProviderItemFields, contents url: URL?, options: NSFileProviderCreateItemOptions = [], request: NSFileProviderRequest, completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        run(completionHandler: { completionHandler(nil, [], false, $0) }) {
            if Self.isLocalOnly(itemTemplate.filename) { throw NSFileProviderError(.excludedFromSync) }
            guard let parent = self.ids.path(for: itemTemplate.parentItemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
            // The top holds the folders the phone shares, and nothing can be put next to them.
            guard parent != "/" else { throw CocoaError(.fileWriteNoPermission) }
            let path = Self.join(parent, itemTemplate.filename)
            if itemTemplate.contentType == .folder {
                do {
                    try await self.client.makeFolder(path)
                } catch DavError.exists where options.contains(.mayAlreadyExist) {}
            } else if itemTemplate.contentType == .symbolicLink || itemTemplate.contentType == .aliasFile {
                // A phone has no links, and an alias pointing into this Mac means nothing there.
                throw NSFileProviderError(.excludedFromSync)
            } else {
                try await self.upload(url, to: path)
            }
            completionHandler(self.item(for: try await self.client.stat(path)), [], false, nil)
        }
    }

    func modifyItem(_ item: NSFileProviderItem, baseVersion version: NSFileProviderItemVersion, changedFields: NSFileProviderItemFields, contents newContents: URL?, options: NSFileProviderModifyItemOptions = [], request: NSFileProviderRequest, completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        run(completionHandler: { completionHandler(nil, [], false, $0) }) {
            guard var path = self.ids.path(for: item.itemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
            if changedFields.contains(.filename) || changedFields.contains(.parentItemIdentifier) {
                guard let parent = self.ids.path(for: item.parentItemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
                guard parent != "/", Self.parent(of: path) != "/" else { throw CocoaError(.fileWriteNoPermission) }
                let target = Self.join(parent, item.filename)
                if target != path {
                    do {
                        try await self.client.move(path, to: target)
                    } catch DavError.exists {
                        throw NSFileProviderError(.filenameCollision)
                    }
                    self.ids.moved(from: path, to: target)
                    path = target
                }
            }
            if changedFields.contains(.contents), let newContents {
                try await self.upload(newContents, to: path)
            }
            // Dates, tags and the like have no place on the phone: what it says is what stays.
            completionHandler(self.item(for: try await self.client.stat(path)), [], false, nil)
        }
    }

    func deleteItem(identifier: NSFileProviderItemIdentifier, baseVersion version: NSFileProviderItemVersion, options: NSFileProviderDeleteItemOptions = [], request: NSFileProviderRequest, completionHandler: @escaping (Error?) -> Void) -> Progress {
        run(completionHandler: completionHandler) {
            guard let path = self.ids.path(for: identifier) else {
                completionHandler(nil)
                return
            }
            guard Self.parent(of: path) != "/" else { throw NSFileProviderError(.deletionRejected) }
            do {
                // The server takes a folder with everything in it, so a folder that is not empty is only taken when that was asked.
                if !options.contains(.recursive), try await self.client.stat(path).isDirectory, !(try await self.client.list(path).isEmpty) {
                    throw NSFileProviderError(.directoryNotEmpty)
                }
                try await self.client.delete(path)
            } catch DavError.notFound {}
            self.ids.removed(path)
            completionHandler(nil)
        }
    }

    // MARK: Folders

    func enumerator(for containerItemIdentifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest) throws -> NSFileProviderEnumerator {
        if containerItemIdentifier == .workingSet {
            return WorkingSet(extension: self)
        }
        guard containerItemIdentifier != .trashContainer, let path = ids.path(for: containerItemIdentifier) else {
            throw NSFileProviderError(.noSuchItem)
        }
        return Folder(extension: self, path: path)
    }

    /// The items of a folder, from the phone.
    fileprivate func items(in path: String) async throws -> [NSFileProviderItem] {
        let entries = try await client.list(path).filter { !Self.isLocalOnly($0.name) }
        seen.saw(path, ids: entries.map(\.path))
        defer { ids.save() }
        return entries.map { item(for: $0) }
    }

    /// What changed in the folders that were opened, for the working set: the system only asks there.
    fileprivate func changes() async throws -> (updated: [NSFileProviderItem], deleted: [NSFileProviderItemIdentifier]) {
        var updated: [NSFileProviderItem] = []
        var deleted: [NSFileProviderItemIdentifier] = []
        for (folder, before) in seen.folders() {
            let entries: [DavEntry]
            do {
                entries = try await client.list(folder).filter { !Self.isLocalOnly($0.name) }
            } catch DavError.notFound {
                entries = []
            }
            let now = Set(entries.map(\.path))
            for gone in before where !now.contains(gone) {
                if let id = ids.knownID(for: gone) { deleted.append(id) }
                ids.removed(gone)
            }
            updated += entries.map { item(for: $0) }
            seen.saw(folder, ids: entries.map(\.path))
        }
        ids.save()
        return (updated, deleted)
    }

    private func item(for entry: DavEntry) -> Item {
        Item(entry: entry, identifier: ids.id(for: entry.path), parent: ids.id(for: Self.parent(of: entry.path)), topLevel: Self.parent(of: entry.path) == "/")
    }

    private func upload(_ file: URL?, to path: String) async throws {
        if let file {
            try await client.upload(from: file, to: path)
        } else {
            let empty = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try Data().write(to: empty)
            defer { try? FileManager.default.removeItem(at: empty) }
            try await client.upload(from: empty, to: path)
        }
    }

    // MARK: Helpers

    /// Files that Finder and other Macs make for themselves, which have no use on a phone.
    static func isLocalOnly(_ name: String) -> Bool {
        name == ".DS_Store" || name.hasPrefix("._") || name == ".localized" || name == "Icon\r"
    }

    static func join(_ folder: String, _ name: String) -> String {
        folder == "/" ? "/" + name : folder + "/" + name
    }

    static func parent(of path: String) -> String {
        guard let slash = path.lastIndex(of: "/"), slash != path.startIndex else { return "/" }
        return String(path[..<slash])
    }

    /// Runs the work and hands its failure to the system in the words of the File Provider. The progress can be cancelled.
    fileprivate func run(completionHandler fail: @escaping (Error) -> Void, _ work: @escaping () async throws -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let task = Task {
            do {
                try await work()
            } catch {
                fail(Self.fileProviderError(error))
            }
            progress.completedUnitCount = 1
        }
        progress.cancellationHandler = { task.cancel() }
        return progress
    }

    static func fileProviderError(_ error: Error) -> Error {
        if error is CancellationError { return CocoaError(.userCancelled) }
        guard let dav = error as? DavError else { return error }
        switch dav {
        case .unreachable: return NSFileProviderError(.serverUnreachable)
        case .unauthorized: return NSFileProviderError(.notAuthenticated)
        case .notFound: return NSFileProviderError(.noSuchItem)
        case .exists: return NSFileProviderError(.filenameCollision)
        case .forbidden: return CocoaError(.fileWriteNoPermission)
        case .insufficientStorage: return NSFileProviderError(.insufficientQuota)
        case .status, .badReply: return NSFileProviderError(.cannotSynchronize)
        }
    }
}

// MARK: - Where the app and the extension meet

/// The folder of the app group, which the app and this extension both reach. The app writes the address and password
/// of each phone's server there (`<domain>.json`), and removes it when it stops serving, so a missing file means the
/// phone cannot be reached right now.
struct SharedPlace {
    let credentialsFile: URL?
    let identitiesFile: URL?

    init(domain: String) {
        let extensionInfo = Bundle.main.object(forInfoDictionaryKey: "NSExtension") as? [String: Any]
        let group = extensionInfo?["NSExtensionFileProviderDocumentGroup"] as? String ?? ""
        let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent("Files", isDirectory: true)
        credentialsFile = folder?.appendingPathComponent("\(domain).json")
        identitiesFile = folder?.appendingPathComponent("\(domain).ids.json")
    }

    func credentials() -> DavCredentials? {
        guard let file = credentialsFile, let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(DavCredentials.self, from: data)
    }
}

// MARK: - Identities

/// The system wants an item to keep its identifier when it is renamed or moved, and the phone only knows paths. So each
/// path gets an identifier of its own, kept in a file, and a move changes the path behind it.
final class Identities {
    private let file: URL?
    private let lock = NSLock()
    private var pathByID: [String: String] = [:]
    private var idByPath: [String: String] = [:]
    private var dirty = false

    init(file: URL?) {
        self.file = file
        if let file, let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([String: String].self, from: data) {
            pathByID = saved
            for (id, path) in saved { idByPath[path] = id }
        }
    }

    func path(for identifier: NSFileProviderItemIdentifier) -> String? {
        if identifier == .rootContainer { return "/" }
        return lock.withLock { pathByID[identifier.rawValue] }
    }

    func knownID(for path: String) -> NSFileProviderItemIdentifier? {
        lock.withLock { idByPath[path].map { NSFileProviderItemIdentifier($0) } }
    }

    func id(for path: String) -> NSFileProviderItemIdentifier {
        if path == "/" { return .rootContainer }
        return lock.withLock {
            if let id = idByPath[path] { return NSFileProviderItemIdentifier(id) }
            let id = UUID().uuidString
            idByPath[path] = id
            pathByID[id] = path
            dirty = true
            return NSFileProviderItemIdentifier(id)
        }
    }

    /// A folder takes everything in it along.
    func moved(from old: String, to new: String) {
        lock.withLock {
            for (path, id) in idByPath where path == old || path.hasPrefix(old + "/") {
                let moved = new + path.dropFirst(old.count)
                idByPath[path] = nil
                // Whatever had the new name before is gone now: it was overwritten.
                if let replaced = idByPath[moved] { pathByID[replaced] = nil }
                idByPath[moved] = id
                pathByID[id] = moved
            }
            dirty = true
        }
        save()
    }

    func removed(_ old: String) {
        lock.withLock {
            for (path, id) in idByPath where path == old || path.hasPrefix(old + "/") {
                idByPath[path] = nil
                pathByID[id] = nil
            }
            dirty = true
        }
        save()
    }

    func save() {
        let snapshot: [String: String]? = lock.withLock {
            guard dirty else { return nil }
            dirty = false
            return pathByID
        }
        guard let snapshot, let file, let data = try? JSONEncoder().encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }
}

/// The folders that were opened while this extension runs, with what was in them, so the working set can tell what
/// changed on the phone when the app asks for a look. The most recent ones only: each is a request to the phone.
final class SeenFolders {
    private let lock = NSLock()
    private var order: [String] = []
    private var content: [String: [String]] = [:]
    private let limit = 40

    func saw(_ folder: String, ids paths: [String]) {
        lock.withLock {
            order.removeAll { $0 == folder }
            order.append(folder)
            content[folder] = paths
            while order.count > limit { content[order.removeFirst()] = nil }
        }
    }

    func folders() -> [(String, [String])] {
        lock.withLock { order.map { ($0, content[$0] ?? []) } }
    }
}

// MARK: - Items and enumerators

final class Item: NSObject, NSFileProviderItem {
    let itemIdentifier: NSFileProviderItemIdentifier
    let parentItemIdentifier: NSFileProviderItemIdentifier
    let filename: String
    let contentType: UTType
    let capabilities: NSFileProviderItemCapabilities
    let documentSize: NSNumber?
    let contentModificationDate: Date?
    let itemVersion: NSFileProviderItemVersion

    init(entry: DavEntry, identifier: NSFileProviderItemIdentifier, parent: NSFileProviderItemIdentifier, topLevel: Bool) {
        itemIdentifier = identifier
        parentItemIdentifier = parent
        filename = entry.name
        if entry.isDirectory {
            contentType = .folder
            // The folders the phone shares are what it shares: they cannot be renamed, moved or removed from here.
            capabilities = topLevel
                ? [.allowsReading, .allowsContentEnumerating, .allowsAddingSubItems]
                : [.allowsReading, .allowsContentEnumerating, .allowsAddingSubItems, .allowsRenaming, .allowsReparenting, .allowsDeleting]
            documentSize = nil
        } else {
            contentType = UTType(filenameExtension: (entry.name as NSString).pathExtension) ?? .data
            capabilities = [.allowsReading, .allowsWriting, .allowsRenaming, .allowsReparenting, .allowsDeleting]
            documentSize = NSNumber(value: entry.size)
        }
        contentModificationDate = entry.modified
        // The server's tag is made of the size and the time of the change, which is what tells a new version from an old one.
        let content = Data((entry.etag ?? "\(entry.size)-\(entry.modified?.timeIntervalSince1970 ?? 0)").utf8)
        itemVersion = NSFileProviderItemVersion(contentVersion: content, metadataVersion: content + Data(entry.name.utf8))
    }

    private init(rootNamed name: String) {
        itemIdentifier = .rootContainer
        parentItemIdentifier = .rootContainer
        filename = name
        contentType = .folder
        capabilities = [.allowsReading, .allowsContentEnumerating]
        documentSize = nil
        contentModificationDate = nil
        itemVersion = NSFileProviderItemVersion(contentVersion: Data("root".utf8), metadataVersion: Data("root".utf8))
    }

    static func root(named name: String) -> Item { Item(rootNamed: name) }
}

/// One folder, listed when it is opened. Its changes are told through the working set, where the system asks for them.
final class Folder: NSObject, NSFileProviderEnumerator {
    private let owner: TandemFilesExtension
    private let path: String

    init(extension owner: TandemFilesExtension, path: String) {
        self.owner = owner
        self.path = path
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        Task {
            do {
                let items = try await owner.items(in: path)
                // Big folders (a camera roll) go in parts, which is what the observer can take at once.
                let size = max(observer.suggestedPageSize ?? 500, 100)
                for start in stride(from: 0, to: items.count, by: size) {
                    observer.didEnumerate(Array(items[start..<min(start + size, items.count)]))
                }
                observer.finishEnumerating(upTo: nil)
            } catch {
                observer.finishEnumeratingWithError(TandemFilesExtension.fileProviderError(error))
            }
        }
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from anchor: NSFileProviderSyncAnchor) {
        observer.finishEnumeratingChanges(upTo: anchor, moreComing: false)
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        completionHandler(WorkingSet.anchorNow())
    }
}

/// Where the system asks what changed. Nothing is listed up front (a phone is too big for that); a look again, which
/// the app asks for when the phone comes back, goes over the folders that were opened.
final class WorkingSet: NSObject, NSFileProviderEnumerator {
    private let owner: TandemFilesExtension

    init(extension owner: TandemFilesExtension) {
        self.owner = owner
    }

    static func anchorNow() -> NSFileProviderSyncAnchor {
        NSFileProviderSyncAnchor(withUnsafeBytes(of: Date().timeIntervalSince1970) { Data($0) })
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        observer.finishEnumerating(upTo: nil)
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from anchor: NSFileProviderSyncAnchor) {
        Task {
            do {
                let (updated, deleted) = try await owner.changes()
                if !deleted.isEmpty { observer.didDeleteItems(withIdentifiers: deleted) }
                if !updated.isEmpty { observer.didUpdate(updated) }
                observer.finishEnumeratingChanges(upTo: Self.anchorNow(), moreComing: false)
            } catch {
                observer.finishEnumeratingWithError(TandemFilesExtension.fileProviderError(error))
            }
        }
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        completionHandler(Self.anchorNow())
    }
}
