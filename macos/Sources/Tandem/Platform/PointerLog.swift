import Foundation

/// A small log of where the pointer went and why it came back, kept next to the data of the app (`pointer-share.log`). When the pointer
/// jumps or does not come back, this says what each side did and when.
enum PointerLog {
    private static let log = FileLog(name: "pointer-share.log")

    static func write(_ line: String) { log.write(line) }
}

/// A small log of why the hotspot of which phone was asked, or none, and what came of it, kept next to the data of the app
/// (`hotspot.log`). The Mac asked the Sony while the Oppo lay beside it, and nothing said why (2026-11-27). Never a password, and never the
/// name of a hotspot: only the phones, what this Mac knew of them, and what they answered.
enum HotspotLog {
    private static let log = FileLog(name: "hotspot.log")

    static func write(_ line: String) { log.write(line) }
}

/// One log file next to the data of the app, written off the main thread, one line at a time with the time in front. It stays small: the
/// old half goes when it grows past 200 KB.
struct FileLog {
    let name: String

    private static let queue = DispatchQueue(label: "nl.markmaaktmedia.tandem.file-log")
    private static let limit = 200_000

    func write(_ line: String) {
        let stamp = Date()
        let name = name
        Self.queue.async {
            let folder = AppIdentity.dataDirectory()
            let file = folder.appendingPathComponent(name)
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let text = "\(formatter.string(from: stamp)) \(line)\n"
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            if let handle = try? FileHandle(forWritingTo: file) {
                defer { try? handle.close() }
                let size = (try? handle.seekToEnd()) ?? 0
                if size > UInt64(Self.limit), let all = try? Data(contentsOf: file) {
                    let kept = all.suffix(Self.limit / 2)
                    try? kept.write(to: file)
                    if let again = try? FileHandle(forWritingTo: file) {
                        defer { try? again.close() }
                        _ = try? again.seekToEnd()
                        try? again.write(contentsOf: Data(text.utf8))
                    }
                } else {
                    try? handle.write(contentsOf: Data(text.utf8))
                }
            } else {
                try? Data(text.utf8).write(to: file)
            }
        }
    }
}
