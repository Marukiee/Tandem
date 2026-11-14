import AppKit
import TandemLogic

/// Reads what a drag carries over the edge. Which kind it is comes from the types alone (`kind`, cheap enough for the event tap); the
/// contents are read once, after the crossing and outside the tap (`read`): a picture is turned into a file off the main thread, and
/// files that an app only promises (Photos, Mail, Safari) are asked for into a folder of their own (R-D5, R-D6, R-D17).
@MainActor
enum DragReading {
    enum Carried {
        case files([URL])
        case text(String)
    }

    private static var board: NSPasteboard { NSPasteboard(name: .drag) }
    private static let promiseTypes = Set(NSFilePromiseReceiver.readableDraggedTypes)

    static func kind() -> DragKind? {
        DragKind.choose(types: board.types?.map(\.rawValue) ?? [], promiseTypes: promiseTypes)
    }

    /// Real files in the drag, read at once (a drag that the computer using this Mac carries into the edge).
    static func files() -> [URL] {
        guard kind() == .files else { return [] }
        return board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
    }

    static func read(_ kind: DragKind) async -> Carried? {
        switch kind {
        case .files:
            let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
            return urls.isEmpty ? nil : .files(urls)
        case .promised:
            let urls = await receivePromises()
            if !urls.isEmpty { return .files(urls) }
            // Nothing came of the promise in time: a picture that came with it still goes.
            return await read(.picture)
        case .picture:
            guard let data = board.data(forType: .png) ?? board.data(forType: .tiff) else { return await read(.text) }
            let file = await Task.detached(priority: .userInitiated) { writePicture(data) }.value
            return file.map { .files([$0]) }
        case .text:
            if let text = board.string(forType: .string), !text.isEmpty { return .text(text) }
            return await read(.link)
        case .link:
            if let url = board.string(forType: .URL), !url.isEmpty { return .text(url) }
            return nil
        }
    }

    /// The folder for what a drag needs written first. Old ones are cleared each time, so it does not grow.
    nonisolated static func freshFolder() -> URL? {
        let manager = FileManager.default
        let root = manager.temporaryDirectory.appendingPathComponent("Tandem drag", isDirectory: true)
        let dayAgo = Date().addingTimeInterval(-86_400)
        for old in (try? manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            let date = (try? old.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if date < dayAgo { try? manager.removeItem(at: old) }
        }
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return (try? manager.createDirectory(at: folder, withIntermediateDirectories: true)) == nil ? nil : folder
    }

    nonisolated private static func writePicture(_ data: Data) -> URL? {
        let png: Data?
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) {
            png = data
        } else {
            png = NSBitmapImageRep(data: data)?.representation(using: .png, properties: [:])
        }
        guard let png, let folder = freshFolder() else { return nil }
        let file = folder.appendingPathComponent(String(localized: "Picture") + " \(Int(Date().timeIntervalSince1970)).png")
        return (try? png.write(to: file)) == nil ? nil : file
    }

    /// Asks the app the drag came from to write the files it promised. Waits at most 20 seconds: a video from Photos can take a while,
    /// but the drag on this Mac is held at the edge until then.
    private static func receivePromises() async -> [URL] {
        guard let receivers = board.readObjects(forClasses: [NSFilePromiseReceiver.self]) as? [NSFilePromiseReceiver], !receivers.isEmpty,
              let folder = freshFolder()
        else { return [] }
        let expected = receivers.reduce(0) { $0 + max($1.fileNames.count, 1) }
        let queue = OperationQueue()
        queue.qualityOfService = .userInitiated
        let collected = PromiseCollector(expected: expected)
        for receiver in receivers {
            receiver.receivePromisedFiles(atDestination: folder, options: [:], operationQueue: queue) { url, error in
                collected.add(error == nil ? url : nil)
            }
        }
        return await collected.wait(seconds: 20)
    }
}

/// Gathers the files of promises as they are written, on the queue AppKit calls back on.
private final class PromiseCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [URL] = []
    private var answered = 0
    private let expected: Int
    private var waiting: CheckedContinuation<[URL], Never>?

    init(expected: Int) { self.expected = expected }

    func add(_ url: URL?) {
        lock.lock()
        if let url { urls.append(url) }
        answered += 1
        let done = answered >= expected ? waiting : nil
        if done != nil { waiting = nil }
        let result = urls
        lock.unlock()
        done?.resume(returning: result)
    }

    func wait(seconds: Double) async -> [URL] {
        await withCheckedContinuation { continuation in
            lock.lock()
            if answered >= expected {
                let result = urls
                lock.unlock()
                continuation.resume(returning: result)
                return
            }
            waiting = continuation
            lock.unlock()
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { [weak self] in self?.timeOut() }
        }
    }

    private func timeOut() {
        lock.lock()
        let pending = waiting
        waiting = nil
        let result = urls
        lock.unlock()
        pending?.resume(returning: result)
    }
}
