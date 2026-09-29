import Foundation

/// Tells the installed app apart from a development build.
///
/// A debug build carries the bundle id `nl.markmaaktmedia.Tandem.dev`, so macOS gives it
/// its own preferences and permissions. Its data folder must differ too, otherwise a test
/// run would read and rewrite the identity and pairings of the Tandem that is installed.
enum AppIdentity {
    static var isDevelopmentBuild: Bool {
        Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true
    }

    /// The folder for this app's data. `TANDEM_DATA_DIR` overrides it for tests.
    static func dataDirectory() -> URL {
        if let override = ProcessInfo.processInfo.environment["TANDEM_DATA_DIR"], !override.isEmpty {
            return URL(fileURLWithPath: override, isDirectory: true)
        }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(isDevelopmentBuild ? "Tandem-Dev" : "Tandem", isDirectory: true)
    }
}
