import Foundation

/// A file name that another app suggests, made safe to write in a folder of ours: only its last part, never `.` or `..`, no slash, and
/// the extension of the real file when the name has none.
public enum SafeName {
    public static func file(suggested: String?, real: String) -> String {
        let fallback = (real as NSString).lastPathComponent
        guard let suggested else { return fallback }
        var name = (suggested.replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent
        name = name.replacingOccurrences(of: ":", with: "-").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/") else { return fallback }
        let ext = (fallback as NSString).pathExtension
        if (name as NSString).pathExtension.isEmpty, !ext.isEmpty { name += ".\(ext)" }
        return name
    }
}
