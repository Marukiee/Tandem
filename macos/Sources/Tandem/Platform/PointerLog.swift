import Foundation

/// A small log of where the pointer went and why it came back, kept next to the data of the app (`pointer-share.log`). When the pointer
/// jumps or does not come back, this says what each side did and when. It stays small: the old half goes when it grows past 200 KB.
enum PointerLog {
    private static let queue = DispatchQueue(label: "nl.markmaaktmedia.tandem.pointer-log")
    private static let limit = 200_000

    static func write(_ line: String) {
        let stamp = Date()
        queue.async {
            let folder = AppIdentity.dataDirectory()
            let file = folder.appendingPathComponent("pointer-share.log")
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let text = "\(formatter.string(from: stamp)) \(line)\n"
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            if let handle = try? FileHandle(forWritingTo: file) {
                defer { try? handle.close() }
                let size = (try? handle.seekToEnd()) ?? 0
                if size > UInt64(limit), let all = try? Data(contentsOf: file) {
                    let kept = all.suffix(limit / 2)
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
