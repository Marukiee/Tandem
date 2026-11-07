import AppKit
import SwiftUI

/// "Tandem" in the Share menu of Finder and other apps. It shows the devices in its own sheet, like AirDrop, and hands the files and the
/// chosen device to the app, which sends them without coming to the front. The extension is sandboxed and cannot see the circle: the app
/// leaves the list of devices, and a key that shows a hand-off really comes from here, in the app group they share (`ShareTargets` in the
/// app). Without that list (a build without the group) it hands the files over as before and the app asks which device.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private let files = SharedFiles()
    private var shared: ShareList?

    override func loadView() {
        files.load(from: extensionContext)
        shared = ShareList.read()
        guard let shared else {
            view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
            return
        }
        let picker = SharePicker(list: shared, files: files, send: { [weak self] ids in self?.send(to: ids) }, cancel: { [weak self] in self?.cancel() })
        let host = NSHostingView(rootView: picker)
        host.sizingOptions = [.preferredContentSize]
        view = host
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        // No list: the app asks.
        if shared == nil { send(to: []) }
    }

    private func send(to ids: [String]) {
        files.whenLoaded { [weak self] paths in
            guard let self else { return }
            guard !paths.isEmpty else { return self.cancel() }
            var parts = URLComponents()
            parts.scheme = "tandem"
            parts.host = "send"
            var items: [URLQueryItem] = []
            if let key = self.shared?.key, !ids.isEmpty {
                items.append(URLQueryItem(name: "key", value: key))
                items += ids.map { URLQueryItem(name: "to", value: $0) }
            }
            items += paths.map { URLQueryItem(name: "file", value: $0) }
            parts.queryItems = items
            if let url = parts.url {
                // The person chose in the sheet, so the app stays where it is. Without a choice it comes forward to ask.
                let configuration = NSWorkspace.OpenConfiguration()
                configuration.activates = ids.isEmpty
                NSWorkspace.shared.open(url, configuration: configuration)
            }
            self.extensionContext?.completeRequest(returningItems: nil)
        }
    }

    private func cancel() {
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
}

/// The paths of what the person shared. Read once, then handed to whoever waits for them.
final class SharedFiles: ObservableObject, @unchecked Sendable {
    private let lock = NSLock()
    private var paths: [String] = []
    private var loaded = false
    private var waiting: [([String]) -> Void] = []
    private(set) var count = 0
    /// The name of the first file, once it is read.
    @Published private(set) var firstName: String?

    func load(from context: NSExtensionContext?) {
        let providers = (context?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
            .filter { $0.hasItemConformingToTypeIdentifier("public.file-url") }
        count = providers.count
        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { [self] item, _ in
                defer { group.leave() }
                var url: URL?
                if let data = item as? Data { url = URL(dataRepresentation: data, relativeTo: nil) } else { url = item as? URL }
                if let url, url.isFileURL {
                    lock.lock(); paths.append(url.path); lock.unlock()
                }
            }
        }
        group.notify(queue: .main) { [self] in
            lock.lock()
            loaded = true
            let done = waiting, found = paths
            waiting = []
            lock.unlock()
            firstName = found.first.map { ($0 as NSString).lastPathComponent }
            done.forEach { $0(found) }
        }
    }

    /// On the main queue, at once when the files are read already.
    func whenLoaded(_ block: @escaping ([String]) -> Void) {
        lock.lock()
        if loaded {
            let found = paths
            lock.unlock()
            block(found)
        } else {
            waiting.append(block)
            lock.unlock()
        }
    }
}

/// What the app leaves in the app group (`share.json`): its devices, whether it runs, and the key of this run.
struct ShareList: Decodable {
    struct Device: Decodable, Identifiable {
        let id: String
        let name: String
        let symbol: String
        let online: Bool
    }

    let key: String
    var running: Bool
    let devices: [Device]

    static func read() -> ShareList? {
        guard let bundle = Bundle.main.bundleIdentifier, bundle.hasSuffix(".Share") else { return nil }
        let app = String(bundle.dropLast(".Share".count))
        guard let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "X8Y2M32MNQ.\(app)"),
              let data = try? Data(contentsOf: folder.appendingPathComponent("share.json"))
        else { return nil }
        guard var list = try? JSONDecoder().decode(ShareList.self, from: data) else { return nil }
        // After a crash the list still says it runs.
        if NSRunningApplication.runningApplications(withBundleIdentifier: app).isEmpty { list.running = false }
        return list
    }
}

struct SharePicker: View {
    let list: ShareList
    @ObservedObject var files: SharedFiles
    let send: ([String]) -> Void
    let cancel: () -> Void
    @State private var why: String?
    @State private var chosen: String?

    private var online: [ShareList.Device] { list.running ? list.devices.filter(\.online) : [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1).truncationMode(.middle)
                Text(String(localized: "Choose a device")).font(.subheadline).foregroundStyle(.secondary)
            }
            if list.devices.isEmpty {
                Text(String(localized: "No devices yet. Pair one in Tandem first."))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 60)
            } else {
                VStack(spacing: 4) {
                    ForEach(list.devices) { device in
                        row(device)
                    }
                    if online.count > 1 {
                        Divider().padding(.vertical, 2).padding(.horizontal, 10)
                        DeviceRow(symbol: "square.stack.3d.up.fill", name: String(localized: "All devices"), detail: nil, enabled: true, busy: chosen == "all") {
                            chosen = "all"
                            send(online.map(\.id))
                        }
                    }
                }
                // The icons line up with the title; only the hover background reaches past it.
                .padding(.horizontal, -10)
            }
            if let why {
                Label(why, systemImage: "info.circle")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack {
                Spacer()
                Button(String(localized: "Cancel"), role: .cancel) { cancel() }
                    .keyboardShortcut(.cancelAction)
                    .controlSize(.large)
            }
        }
        .padding(20)
        .frame(width: 340)
        .animation(.easeInOut(duration: 0.2), value: why)
        .animation(.easeInOut(duration: 0.2), value: chosen)
    }

    private var title: String {
        if files.count == 1 { return files.firstName.map { String(localized: "Send “\($0)”") } ?? String(localized: "Send a file") }
        return String(localized: "Send \(files.count) files")
    }

    @ViewBuilder
    private func row(_ device: ShareList.Device) -> some View {
        let can = list.running && device.online
        DeviceRow(symbol: device.symbol, name: device.name, detail: can ? String(localized: "Connected") : String(localized: "Not connected"), enabled: can, busy: chosen == device.id) {
            if can {
                chosen = device.id
                send([device.id])
            } else if !list.running {
                why = String(localized: "Tandem is not running on this Mac. Open it first, then share again.")
            } else {
                why = String(localized: "\(device.name) is not connected right now. Open Tandem on it, or bring it closer.")
            }
        }
    }
}

/// One device: an icon in a circle, its name and whether it is there. Grey when it cannot take the files, and pressing it then says why.
struct DeviceRow: View {
    let symbol: String
    let name: String
    let detail: String?
    let enabled: Bool
    let busy: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(enabled ? Color.white : Color.secondary)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(enabled ? Color.accentColor : Color.secondary.opacity(0.18)))
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.body.weight(.medium)).lineLimit(1)
                    if let detail {
                        Text(detail).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                if busy {
                    ProgressView().controlSize(.small).transition(.opacity)
                } else if enabled {
                    Image(systemName: "paperplane.fill")
                        .foregroundStyle(.secondary)
                        .opacity(hover ? 1 : 0)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.primary.opacity(hover && enabled ? 0.08 : 0))
            )
            .opacity(enabled ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.easeInOut(duration: 0.15), value: hover)
        .help(enabled ? String(localized: "Send to \(name)") : "")
    }
}
