import Foundation
import TandemCore
import UniformTypeIdentifiers

/// Opening files to send and placing files that arrived.
final class MacFiles: TandemFiles, @unchecked Sendable {
    func openRead(source: String) throws -> Int32 {
        let descriptor = open(source, O_RDONLY)
        guard descriptor >= 0 else {
            throw TandemError.Failed(message: String(cString: strerror(errno)))
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

/// Where received files go. Chosen in Settings, `~/Downloads/Tandem` by default.
enum DownloadFolder {
    static let key = "downloadFolderPath"

    static var url: URL {
        get {
            if let path = UserDefaults.standard.string(forKey: key), !path.isEmpty {
                return URL(fileURLWithPath: path)
            }
            let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
                ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
            return downloads.appendingPathComponent("Tandem")
        }
        set { UserDefaults.standard.set(newValue.path, forKey: key) }
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
