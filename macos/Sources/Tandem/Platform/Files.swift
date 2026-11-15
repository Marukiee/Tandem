import CoreServices
import Foundation
import TandemCore
import UniformTypeIdentifiers

/// Opening files to send and placing files that arrived.
final class MacFiles: TandemFiles, @unchecked Sendable {
    func openRead(source: String) throws -> Int32 {
        let descriptor = open(source, O_RDONLY)
        guard descriptor >= 0 else {
            throw TandemError.Failed(reason: String(cString: strerror(errno)))
        }
        return descriptor
    }

    func storeDownload(tempPath: String, name: String, mime: String) throws -> String {
        let folder = DownloadFolder.url
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = uniqueURL(in: folder, name: sanitize(name))
        let source = URL(fileURLWithPath: tempPath)
        do {
            try FileManager.default.moveItem(at: source, to: target)
        } catch {
            // Another volume: copy, then drop the private temp file.
            try FileManager.default.copyItem(at: source, to: target)
            try? FileManager.default.removeItem(at: source)
        }
        Quarantine.mark(target)
        return target.path
    }

    private func sanitize(_ name: String) -> String {
        var cleaned = name.map { ["/", "\\", ":"].contains($0) || $0.isNewline ? "_" : $0 }
            .map(String.init).joined()
        cleaned = cleaned.trimmingCharacters(in: .whitespaces)
        while cleaned.hasPrefix(".") { cleaned.removeFirst() }
        return cleaned.isEmpty ? "file" : String(cleaned.prefix(180))
    }

    private func uniqueURL(in folder: URL, name: String) -> URL {
        var candidate = folder.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let stem = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var counter = 1
        repeat {
            let numbered = ext.isEmpty ? "\(stem) (\(counter))" : "\(stem) (\(counter)).\(ext)"
            candidate = folder.appendingPathComponent(numbered)
            counter += 1
        } while FileManager.default.fileExists(atPath: candidate.path)
        return candidate
    }
}

/// The mark macOS gives a download, on everything that lands from another device. Gatekeeper then asks before an app or a script
/// from it runs, as it does for one from a browser: a `photo.command` no longer starts without a question.
enum Quarantine {
    static func mark(_ url: URL) {
        let properties: [String: Any] = [
            kLSQuarantineAgentNameKey as String: "Tandem",
            kLSQuarantineTypeKey as String: kLSQuarantineTypeOtherDownload as String,
        ]
        try? (url as NSURL).setResourceValue(properties, forKey: .quarantinePropertiesKey)
    }
}

/// Where received files go. Chosen in Settings, `~/Downloads` by default: a file that
/// arrives should be where the person looks for downloads, not in a folder of its own.
enum DownloadFolder {
    static let key = "downloadFolderPath"

    static var defaultURL: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
    }

    static var isDefault: Bool { UserDefaults.standard.string(forKey: key)?.isEmpty ?? true }

    static var url: URL {
        get {
            if let path = UserDefaults.standard.string(forKey: key), !path.isEmpty {
                return URL(fileURLWithPath: path)
            }
            return defaultURL
        }
        set { UserDefaults.standard.set(newValue.path, forKey: key) }
    }

    static func reset() {
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// What the core takes as "cannot be read" (`FREE_STORAGE_UNKNOWN`): it takes an old number away, and nothing is refused for lack
    /// of room.
    static let unknownFreeSpace = UInt64.max

    /// The room left on the disk the files land on, not on the disk of the system. A folder that is not there may be on a disk that is
    /// not plugged in, and the disk below it would be another one. Can wait on a slow disk, so not for the main thread.
    static func freeSpace() -> UInt64 {
        let folder = url
        guard FileManager.default.fileExists(atPath: folder.path),
              let values = try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        else { return unknownFreeSpace }
        // The figure that counts what the system can purge is the one for APFS; other volumes give 0 for it, and then the plain
        // figure is the one. A disk that says 0 both ways tells nothing, like on Linux and Windows.
        if let important = values.volumeAvailableCapacityForImportantUsage, important > 0 { return UInt64(important) }
        if let plain = values.volumeAvailableCapacity, plain > 0 { return UInt64(plain) }
        return unknownFreeSpace
    }
}

enum FileKind {
    static func mimeType(for url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    /// A folder cannot be sent as it is, so it travels as a zip.
    static func prepareForSending(_ url: URL) throws -> URL {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return url
        }
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("tandem-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
        let zip = temp.appendingPathComponent(url.lastPathComponent + ".zip")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = ["-c", "-k", "--keepParent", url.path, zip.path]
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw CocoaError(.fileWriteUnknown)
        }
        return zip
    }
}
