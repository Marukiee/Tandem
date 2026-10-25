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

    /// A single picture that was copied waits in the core's own clipboard folder, not in Downloads (see `kept_for_clipboard`).
    private static func isWaitingForClipboard(_ file: URL) -> Bool {
        file.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "clipboard"
    }

    /// The picture goes on the pasteboard as a picture only, and its file goes at once: a screenshot from every device would otherwise
    /// pile up as files. The history keeps it the way it keeps any copied picture, with the device it came from.
    private func putPicture(_ file: URL, from device: String) -> Bool {
        guard let data = try? Data(contentsOf: file) else { return false }
        let isPNG = UTType(filenameExtension: file.pathExtension)?.conforms(to: .png) == true
        let pasteboard = NSPasteboard.general
        if isPNG {
            pasteboard.clearContents()
            pasteboard.setData(data, forType: .png)
        } else if let image = NSImage(data: data) {
            pasteboard.clearContents()
            pasteboard.writeObjects([image])
        } else {
            return false
        }
        try? FileManager.default.removeItem(at: file)
        try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        ClipboardRecorder.shared.ignoreCurrent()
        let source = EngineModel.shared.device(device)
        let deviceName = source?.name, symbol = source?.platform.symbol
        Task.detached(priority: .utility) {
            guard let prepared = ClipImage.prepare(data: data, isPNG: isPNG) else { return }
            await MainActor.run {
                ClipboardHistory.shared.record(
                    image: prepared.png, thumbnail: prepared.thumbnail, width: prepared.width, height: prepared.height, hash: prepared.hash,
                    app: nil, appName: nil, device: deviceName, devicePlatform: symbol
                )
            }
        }
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
        let name = EngineModel.shared.device(device)?.name ?? "?"
        if files.count == 1, Self.isWaitingForClipboard(files[0]) {
            // What cannot be read as a picture here goes as its file, which the core's sweep removes later.
            if !putPicture(files[0], from: device) {
                pasteboard.clearContents()
                pasteboard.writeObjects([files[0] as NSURL])
            }
        } else {
            pasteboard.clearContents()
            pasteboard.writeObjects(files.map { $0 as NSURL })
        }
        EngineModel.shared.clipboardWritten()
        EngineModel.shared.showToast(files.count == 1
            ? String(localized: "\(files[0].lastPathComponent) from \(name) is on your clipboard")
            : String(localized: "\(files.count) files from \(name) are on your clipboard"))
        return true
    }
}
