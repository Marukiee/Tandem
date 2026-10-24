import AppKit
import TandemCore
import UniformTypeIdentifiers

/// The clipboard for pictures and files, next to the one for text (`ClipboardMonitor`). What is copied goes to the devices that share
/// the clipboard as files with the origin `clipboard`, and what comes in that way lands on the pasteboard here, not in Downloads. Small
/// enough only: a picture up to 15 MB and files up to 50 MB together, so a copy never starts a large transfer by itself.
@MainActor
final class ClipboardFiles {
    static let shared = ClipboardFiles()

    static let pictureLimit = 15 * 1024 * 1024
    static let filesLimit = 50 * 1024 * 1024

    /// Offers that came in as a clipboard, by device, and the files of each that are in so far.
    private var offers: [UInt64: (device: String, files: [URL])] = [:]
    /// Where pictures that are sent are written, kept until the next one.
    private lazy var outbox: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("TandemClipboard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    // MARK: Sending

    /// The pasteboard changed and holds no text: files copied in Finder, or a picture. True when it was one of those and was sent.
    func copied(_ pasteboard: NSPasteboard) -> Bool {
        let model = EngineModel.shared
        let targets = model.devices.filter { $0.online && $0.clipboardEnabled }.map(\.id)
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            guard !targets.isEmpty else { return true }
            let size = urls.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.totalFileSizeKey, .isDirectoryKey]))?.totalFileSize ?? 0) }
            // A folder has no size of its own here, and could be anything: not sent by a copy.
            let folders = urls.contains { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
            guard !folders, size <= Self.filesLimit else { return true }
            model.send(urls: urls, to: targets, origin: .clipboard)
            return true
        }
        guard let image = NSImage(pasteboard: pasteboard) else { return false }
        guard !targets.isEmpty, let png = Self.png(image), png.count <= Self.pictureLimit else { return true }
        let file = outbox.appendingPathComponent(String(localized: "Picture") + ".png")
        try? FileManager.default.removeItem(at: file)
        guard (try? png.write(to: file)) != nil else { return true }
        model.send(urls: [file], to: targets, origin: .clipboard)
        return true
    }

    private static func png(_ image: NSImage) -> Data? {
        guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }

    // MARK: Receiving

    /// An offer with the origin `clipboard`. Taken without asking from a device that shares the clipboard with this Mac, when it is no
    /// larger than a copy here may be; anything else is asked about like other files.
    func take(offer: UInt64, from device: String, size: UInt64) -> Bool {
        let model = EngineModel.shared
        guard size <= UInt64(Self.filesLimit), model.device(device)?.clipboardEnabled == true, let engine = model.tandem else { return false }
        guard (try? engine.acceptOffer(from: device, offer: offer)) != nil else { return false }
        offers[offer] = (device, [])
        if offers.count > 20, let oldest = offers.keys.min() { offers.removeValue(forKey: oldest) }
        return true
    }

    /// A file of such an offer is in: everything that came so far goes on the pasteboard, so several files end up there together.
    func finished(offer: UInt64, from device: String, location: String?, error: String?) -> Bool {
        guard var entry = offers[offer], entry.device == device else { return false }
        guard error == nil, let location else { return true }
        entry.files.append(URL(fileURLWithPath: location))
        offers[offer] = entry
        let files = entry.files
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if files.count == 1, let type = UTType(filenameExtension: files[0].pathExtension), type.conforms(to: .image), let image = NSImage(contentsOf: files[0]) {
            // A picture as a picture, so it pastes into a message or a document, and as its file, so it pastes into Finder.
            pasteboard.writeObjects([image, files[0] as NSURL])
        } else {
            pasteboard.writeObjects(files.map { $0 as NSURL })
        }
        EngineModel.shared.clipboardWritten()
        let name = EngineModel.shared.device(device)?.name ?? "?"
        EngineModel.shared.showToast(files.count == 1
            ? String(localized: "\(files[0].lastPathComponent) from \(name) is on your clipboard")
            : String(localized: "\(files.count) files from \(name) are on your clipboard"))
        return true
    }
}
