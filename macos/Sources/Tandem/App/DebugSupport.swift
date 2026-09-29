import AppKit

/// Development helper. With `TANDEM_DEBUG_DIR` set, the app writes its pairing link
/// there and saves its own windows as PNG whenever it receives SIGUSR1. That is how
/// the interface gets checked without needing screen-recording permission.
@MainActor
enum DebugSupport {
    private static var signalSource: DispatchSourceSignal?

    static var directory: URL? {
        ProcessInfo.processInfo.environment["TANDEM_DEBUG_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
    }

    static func install() {
        guard let directory else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        signal(SIGUSR1, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
        source.setEventHandler { snapshot(into: directory) }
        source.resume()
        signalSource = source
    }

    static func write(_ text: String, named name: String) {
        guard let directory else { return }
        try? text.write(to: directory.appendingPathComponent(name), atomically: true, encoding: .utf8)
    }

    static func snapshot(into directory: URL) {
        for (index, window) in NSApp.windows.enumerated() where window.isVisible && window.contentView != nil {
            guard let view = window.contentView?.superview ?? window.contentView,
                  let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)
            else { continue }
            view.cacheDisplay(in: view.bounds, to: rep)
            if let png = rep.representation(using: .png, properties: [:]) {
                try? png.write(to: directory.appendingPathComponent("window-\(index).png"))
            }
        }
    }
}
