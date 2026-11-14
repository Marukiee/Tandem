import Foundation

/// Compares versions the way a person reads them, so "1.10.0" beats "1.9.0". A suffix
/// after the numbers ("-rc1") counts as older than the plain release.
public enum VersionComparator {
    /// The newest release that has `file`. A release can stand there without it, while it is being built or because its build failed.
    /// That is no news, and it must not hide the release before it.
    public static func newest(in releases: [[String: Any]], with file: String) -> [String: Any]? {
        var newest: [String: Any]?
        var newestTag = ""
        for release in releases {
            guard release["draft"] as? Bool != true, release["prerelease"] as? Bool != true,
                  let tag = release["tag_name"] as? String,
                  let assets = release["assets"] as? [[String: Any]],
                  assets.contains(where: { $0["name"] as? String == file })
            else { continue }
            if newest == nil || isNewer(tag, than: newestTag) {
                newest = release
                newestTag = tag
            }
        }
        return newest
    }

    public static func normalise(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("v") || text.hasPrefix("V") { text.removeFirst() }
        return text
    }

    public static func isNewer(_ candidate: String, than current: String) -> Bool {
        compare(candidate, current) > 0
    }

    public static func compare(_ left: String, _ right: String) -> Int {
        let a = numbers(left), b = numbers(right)
        for index in 0 ..< max(a.count, b.count) {
            let x = index < a.count ? a[index] : 0
            let y = index < b.count ? b[index] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        let s1 = suffix(left), s2 = suffix(right)
        if s1 == s2 { return 0 }
        if s1.isEmpty { return 1 }
        if s2.isEmpty { return -1 }
        return s1 < s2 ? -1 : 1
    }

    private static func numbers(_ raw: String) -> [Int] {
        normalise(raw).split(separator: "-", maxSplits: 1).first.map(String.init)?
            .split(separator: ".").map { Int($0.prefix(while: \.isNumber)) ?? 0 } ?? []
    }

    private static func suffix(_ raw: String) -> String {
        let text = normalise(raw)
        guard let dash = text.firstIndex(of: "-") else { return "" }
        return String(text[text.index(after: dash)...]).lowercased()
    }
}

/// What an update has to pass before it replaces the app (R-B11). Each check fails closed: what cannot be checked is not installed.
public enum UpdateRules {
    /// The Ed25519 key compiled into the app, as its 32 raw bytes. Missing, empty, not base64 or the wrong length: nil, and then nothing
    /// is installed, rather than a download whose signature was never looked at.
    public static func publicKey(_ text: String?) -> Data? {
        guard let text, !text.isEmpty, let raw = Data(base64Encoded: text), raw.count == 32 else { return nil }
        return raw
    }

    /// The unpacked app is the version the release says. A signed older build offered under a newer tag is refused.
    public static func sameVersion(unpacked: String?, release: String) -> Bool {
        guard let unpacked, !unpacked.isEmpty else { return false }
        return VersionComparator.compare(unpacked, release) == 0
    }
}
