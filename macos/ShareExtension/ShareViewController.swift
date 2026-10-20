import AppKit

/// "Tandem" in the Share menu of Finder and other apps. It does nothing but hand the files to the app: it asks which device in Tandem itself,
/// where the devices are known. The extension is sandboxed and cannot see the circle, the app is not and can read what the person shared.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let group = DispatchGroup()
        let lock = NSLock()
        var paths: [String] = []
        for provider in providers where provider.hasItemConformingToTypeIdentifier("public.file-url") {
            group.enter()
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, _ in
                defer { group.leave() }
                var url: URL?
                if let data = item as? Data { url = URL(dataRepresentation: data, relativeTo: nil) } else { url = item as? URL }
                if let url, url.isFileURL {
                    lock.lock(); paths.append(url.path); lock.unlock()
                }
            }
        }
        group.notify(queue: .main) { [weak self] in
            if !paths.isEmpty {
                var parts = URLComponents()
                parts.scheme = "tandem"
                parts.host = "send"
                parts.queryItems = paths.map { URLQueryItem(name: "file", value: $0) }
                if let url = parts.url { NSWorkspace.shared.open(url) }
                self?.extensionContext?.completeRequest(returningItems: nil)
            } else {
                self?.extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
            }
        }
    }
}
