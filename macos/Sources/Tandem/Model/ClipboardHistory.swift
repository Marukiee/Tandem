import AppKit
import CryptoKit
import Foundation
import ImageIO
import Observation
import UniformTypeIdentifiers

/// One thing that was copied: some text, a link or a picture.
struct ClipItem: Identifiable, Codable, Equatable {
    enum Kind: String, Codable { case text, link, image }

    var id: UUID
    var kind: Kind
    /// The start of the text, for the list and for searching.
    var preview: String
    /// The whole text when it is short. Longer text lives in a file of its own, so the index stays small.
    var text: String?
    var characters: Int
    var lines: Int
    var imageWidth: Int
    var imageHeight: Int
    var imageBytes: Int
    var date: Date
    var pinned: Bool
    /// The app that was in front when it was copied, by bundle id and by name.
    var app: String?
    var appName: String?
    /// The device it came from, when it did not come from this Mac.
    var device: String?
    var devicePlatform: String?
    /// A fingerprint of the content, so copying the same thing again moves it up instead of adding it twice.
    var hash: String

    var isImage: Bool { kind == .image }
}

/// Everything copied on this Mac and everything that arrived from a device, kept on this Mac.
///
/// Light on purpose: a small index file, longer texts and pictures in files next to it, and nothing read into memory
/// that the list is not showing. What may be kept is bounded (a number of items, a number of days, a total size for
/// pictures), and pinned items never expire.
@MainActor
@Observable
final class ClipboardHistory {
    static let shared = ClipboardHistory()

    enum Keys {
        static let enabled = "clipboardHistoryEnabled"
        static let limit = "clipboardHistoryLimit"
        static let days = "clipboardHistoryDays"
        static let images = "clipboardHistoryImages"
        static let ignored = "clipboardHistoryIgnoredApps"
        static let hotkeyOn = "clipboardHotkeyOn"
        static let hotkey = "clipboardHotkey"
        static let paste = "clipboardPasteDirectly"
    }

    /// Password managers and the like. They mark what they copy as secret, and this is the second lock on that door.
    static let defaultIgnoredApps = [
        "com.agilebits.onepassword7", "com.1password.1password", "com.bitwarden.desktop", "org.keepassxc.keepassxc",
        "com.lastpass.LastPass", "com.dashlane.dashlane", "com.apple.keychainaccess", "com.apple.Passwords",
        "in.sinew.Enpass-Desktop", "com.nordsec.nordpass",
    ]

    /// Items newer first.
    private(set) var items: [ClipItem] = []

    // Settings. Each one writes itself to the defaults, so a backup and the settings view see the same thing.
    var enabled: Bool = UserDefaults.standard.object(forKey: Keys.enabled) as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Keys.enabled)
            ClipboardRecorder.shared.refresh()
        }
    }
    var limit: Int = UserDefaults.standard.object(forKey: Keys.limit) as? Int ?? 500 {
        didSet { UserDefaults.standard.set(limit, forKey: Keys.limit); prune() }
    }
    var days: Int = UserDefaults.standard.object(forKey: Keys.days) as? Int ?? 30 {
        didSet { UserDefaults.standard.set(days, forKey: Keys.days); prune() }
    }
    var recordsImages: Bool = UserDefaults.standard.object(forKey: Keys.images) as? Bool ?? true {
        didSet { UserDefaults.standard.set(recordsImages, forKey: Keys.images) }
    }
    var ignoredApps: [String] = UserDefaults.standard.stringArray(forKey: Keys.ignored) ?? ClipboardHistory.defaultIgnoredApps {
        didSet { UserDefaults.standard.set(ignoredApps, forKey: Keys.ignored) }
    }
    var hotkeyOn: Bool = UserDefaults.standard.object(forKey: Keys.hotkeyOn) as? Bool ?? true {
        didSet { UserDefaults.standard.set(hotkeyOn, forKey: Keys.hotkeyOn); ClipboardPanelController.shared.registerHotkey() }
    }
    var hotkey: Shortcut = Shortcut.stored() {
        didSet { hotkey.store(); ClipboardPanelController.shared.registerHotkey() }
    }
    var pastesDirectly: Bool = UserDefaults.standard.object(forKey: Keys.paste) as? Bool ?? true {
        didSet { UserDefaults.standard.set(pastesDirectly, forKey: Keys.paste) }
    }

    @ObservationIgnored private let directory: URL
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var names: [String: String] = [:]

    private static let inlineLimit = 16_384
    private static let previewLength = 2_000
    /// Pictures take space; past this the oldest unpinned ones go, whatever the count.
    private static let imageBudget = 300_000_000

    init() {
        directory = AppIdentity.dataDirectory().appendingPathComponent("ClipboardHistory", isDirectory: true)
        for sub in ["", "texts", "images", "thumbs"] {
            try? FileManager.default.createDirectory(at: directory.appendingPathComponent(sub, isDirectory: true), withIntermediateDirectories: true)
        }
        if let data = try? Data(contentsOf: indexURL), let stored = try? JSONDecoder.history.decode([ClipItem].self, from: data) {
            items = stored.sorted { $0.date > $1.date }
        }
        prune()
    }

    /// Reads the settings again, after a backup was imported.
    func reloadSettings() {
        let defaults = UserDefaults.standard
        enabled = defaults.object(forKey: Keys.enabled) as? Bool ?? true
        limit = defaults.object(forKey: Keys.limit) as? Int ?? 500
        days = defaults.object(forKey: Keys.days) as? Int ?? 30
        recordsImages = defaults.object(forKey: Keys.images) as? Bool ?? true
        hotkeyOn = defaults.object(forKey: Keys.hotkeyOn) as? Bool ?? true
        pastesDirectly = defaults.object(forKey: Keys.paste) as? Bool ?? true
    }

    /// For the debug harness: puts a prepared item in the list.
    func debugInsert(_ item: ClipItem) {
        items.append(item)
        items.sort { $0.date > $1.date }
    }

    // MARK: Files

    private var indexURL: URL { directory.appendingPathComponent("index.json") }
    private func textURL(_ id: UUID) -> URL { directory.appendingPathComponent("texts/\(id.uuidString).txt") }
    func imageURL(_ id: UUID) -> URL { directory.appendingPathComponent("images/\(id.uuidString).png") }
    func thumbnailURL(_ id: UUID) -> URL { directory.appendingPathComponent("thumbs/\(id.uuidString).jpg") }

    /// The whole text of an item.
    func fullText(of item: ClipItem) -> String? {
        if let text = item.text { return text }
        return try? String(contentsOf: textURL(item.id), encoding: .utf8)
    }

    /// Bytes on disk, for the settings view.
    var diskUsage: Int {
        items.reduce(0) { $0 + $1.imageBytes + ($1.text == nil && !$1.isImage ? $1.characters : 0) }
    }

    // MARK: Recording

    /// Something was copied on this Mac, or arrived from a device (`device`).
    func record(text: String, kind: ClipItem.Kind? = nil, app: String?, appName: String?, device: String? = nil, devicePlatform: String? = nil) {
        guard enabled, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let hash = Self.digest(Data(text.utf8))
        if bump(hash: hash) { return }

        let id = UUID()
        let inline = text.utf8.count <= Self.inlineLimit
        if !inline { try? text.write(to: textURL(id), atomically: true, encoding: .utf8) }
        let item = ClipItem(
            id: id,
            kind: kind ?? (ClipboardMonitor.looksLikeURL(text) ? .link : .text),
            preview: String(text.prefix(inline ? Self.inlineLimit : Self.previewLength)),
            text: inline ? text : nil,
            characters: text.count,
            lines: text.reduce(1) { $1.isNewline ? $0 + 1 : $0 },
            imageWidth: 0, imageHeight: 0, imageBytes: 0,
            date: Date(), pinned: false,
            app: app, appName: appName, device: device, devicePlatform: devicePlatform,
            hash: hash
        )
        insert(item)
    }

    /// A picture was copied. Its files are written in the background; the item is in the list at once.
    func record(image png: Data, thumbnail: Data?, width: Int, height: Int, hash: String, app: String?, appName: String?) {
        guard enabled, recordsImages else { return }
        if bump(hash: hash) { return }
        let id = UUID()
        let item = ClipItem(
            id: id, kind: .image, preview: "", text: nil, characters: 0, lines: 0,
            imageWidth: width, imageHeight: height, imageBytes: png.count,
            date: Date(), pinned: false,
            app: app, appName: appName, device: nil, devicePlatform: nil, hash: hash
        )
        let image = imageURL(id), thumb = thumbnailURL(id)
        Task.detached(priority: .utility) {
            try? png.write(to: image, options: .atomic)
            if let thumbnail { try? thumbnail.write(to: thumb, options: .atomic) }
        }
        insert(item)
    }

    /// Copying what is already in the list moves it to the top. Returns whether it was there.
    private func bump(hash: String) -> Bool {
        guard let index = items.firstIndex(where: { $0.hash == hash }) else { return false }
        var item = items.remove(at: index)
        item.date = Date()
        items.insert(item, at: 0)
        scheduleSave()
        return true
    }

    private func insert(_ item: ClipItem) {
        items.insert(item, at: 0)
        prune()
        scheduleSave()
    }

    // MARK: Changing

    func togglePin(_ id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        items[index].pinned.toggle()
        scheduleSave()
    }

    func delete(_ ids: Set<UUID>) {
        let gone = items.filter { ids.contains($0.id) }
        items.removeAll { ids.contains($0.id) }
        discardFiles(of: gone)
        scheduleSave()
    }

    func clear(keepingPinned: Bool) {
        let gone = items.filter { !(keepingPinned && $0.pinned) }
        items.removeAll { !(keepingPinned && $0.pinned) }
        discardFiles(of: gone)
        scheduleSave()
    }

    /// Takes the item to the top of the list: what was just used is what is wanted again.
    func touch(_ id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }), index != 0 else { return }
        var item = items.remove(at: index)
        item.date = Date()
        items.insert(item, at: 0)
        scheduleSave()
    }

    // MARK: Limits

    func prune() {
        var gone: [ClipItem] = []
        let cutoff = Date().addingTimeInterval(-Double(days) * 86_400)
        // Newest first, so what is past the count or the budget is what comes last.
        var keptUnpinned = 0
        var pictureBytes = 0
        items = items.filter { item in
            if item.pinned { return true }
            if item.date < cutoff { gone.append(item); return false }
            if keptUnpinned >= limit { gone.append(item); return false }
            if item.isImage, pictureBytes + item.imageBytes > Self.imageBudget { gone.append(item); return false }
            keptUnpinned += 1
            if item.isImage { pictureBytes += item.imageBytes }
            return true
        }
        if !gone.isEmpty {
            discardFiles(of: gone)
            scheduleSave()
        }
    }

    private func discardFiles(of gone: [ClipItem]) {
        guard !gone.isEmpty else { return }
        let urls = gone.flatMap { [textURL($0.id), imageURL($0.id), thumbnailURL($0.id)] }
        Task.detached(priority: .utility) {
            for url in urls { try? FileManager.default.removeItem(at: url) }
        }
    }

    // MARK: Saving

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Writes the index now. Called when the app quits, so a copy made a moment before is not lost.
    func flush() {
        saveTask?.cancel()
        guard let data = try? JSONEncoder.history.encode(items) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }

    // MARK: Apps

    /// What an app is called, from its bundle id, or the id itself when it is not on this Mac.
    func name(ofApp bundle: String) -> String {
        if let known = names[bundle] { return known }
        var name = bundle
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundle) {
            name = FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
        }
        names[bundle] = name
        return name
    }

    func ignore(app bundle: String) {
        guard !ignoredApps.contains(bundle) else { return }
        ignoredApps.append(bundle)
    }

    func stopIgnoring(app bundle: String) {
        ignoredApps.removeAll { $0 == bundle }
    }

    nonisolated static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

extension JSONEncoder {
    fileprivate static let history: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        return encoder
    }()
}

extension JSONDecoder {
    fileprivate static let history: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return decoder
    }()
}

// MARK: Reading the pasteboard

/// What the pasteboard holds, worked out away from the main thread: pictures are large.
enum ClipImage {
    struct Prepared: Sendable {
        let png: Data
        let thumbnail: Data?
        let width: Int
        let height: Int
        let hash: String
    }

    /// The bytes of a picture on the pasteboard, as PNG, with a thumbnail for the list.
    static func prepare(data: Data, isPNG: Bool) -> Prepared? {
        var png = data
        if !isPNG {
            guard let rep = NSBitmapImageRep(data: data), let converted = rep.representation(using: .png, properties: [:]) else { return nil }
            png = converted
        }
        guard png.count <= 25_000_000,
              let source = CGImageSourceCreateWithData(png as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        return Prepared(
            png: png,
            thumbnail: thumbnail(of: source, maxPixels: 200),
            width: width, height: height,
            hash: ClipboardHistory.digest(png)
        )
    }

    static func thumbnail(of source: CGImageSource, maxPixels: Int) -> Data? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    /// A picture file shrunk to fit, for the preview.
    static func downsampled(_ url: URL, maxPixels: Int) -> NSImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}
