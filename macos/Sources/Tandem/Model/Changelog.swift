import Foundation

/// One release as it reads in What's new. Written by hand in changelog.json at the repo root,
/// which the build copies into the app, so there is one list for both apps and the release page.
struct ChangeEntry: Identifiable, Equatable {
    let version: String
    let date: String
    let title: String
    let new: [String]
    let better: [String]
    let fixed: [String]
    let highlight: Bool

    var id: String { version }
}

enum Changelog {
    /// Newest first, in the language the app is showing. Empty when the file is missing.
    static func load() -> [ChangeEntry] {
        guard
            let url = Bundle.main.url(forResource: "changelog", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        let language = Bundle.main.preferredLocalizations.first == "nl" ? "nl" : "en"
        return raw.compactMap { entry in
            guard
                let version = entry["version"] as? String,
                let text = (entry[language] ?? entry["en"]) as? [String: Any]
            else { return nil }
            return ChangeEntry(
                version: version,
                date: entry["date"] as? String ?? "",
                title: text["title"] as? String ?? "",
                new: text["new"] as? [String] ?? [],
                better: text["better"] as? [String] ?? [],
                fixed: text["fixed"] as? [String] ?? [],
                highlight: entry["highlight"] as? Bool ?? false
            )
        }
    }
}
