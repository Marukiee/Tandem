import AppKit
import Observation
import SwiftUI
import TandemCore

/// Quick Share on this Mac (see docs/QUICKSHARE.md): other devices with Quick Share can send files here, and this Mac can send to
/// them, without Google services and without an account. Off until it is turned on, because while it is on this Mac can be found by
/// everyone on the same network.
@MainActor @Observable
final class QuickShare {
    static let shared = QuickShare()

    private static let enabledKey = "quickShareEnabled"

    struct Peer: Identifiable, Equatable {
        let id: String
        let name: String
        let kind: TandemQsKind
    }

    struct Incoming: Identifiable {
        let id: UInt64
        let sender: String
        let pin: String
        let files: [TandemQsFile]
        var texts: [TandemQsTextInfo] = []
        var accepted = false
        var done: UInt64 = 0
        var saved: [String]?
        /// The texts that came in, once the transfer is done: they are on the clipboard.
        var receivedTexts: [TandemQsText] = []
        var failure: String?

        var total: UInt64 { files.reduce(0) { $0 + $1.size } }

        /// The link that came in, when there is one.
        var link: URL? {
            receivedTexts.first { $0.kind == .url }.flatMap { URL(string: $0.text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        }
    }

    struct Outgoing: Identifiable {
        enum State: Equatable { case sending, sent, refused, failed(String) }

        let id: UInt64
        let peerName: String
        var pin: String?
        var done: UInt64 = 0
        var total: UInt64 = 0
        var state = State.sending
    }

    var enabled: Bool = UserDefaults.standard.bool(forKey: QuickShare.enabledKey) {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.enabledKey)
            if enabled { start() } else { stop() }
        }
    }

    private(set) var peers: [Peer] = []
    private(set) var incoming: [Incoming] = []
    private(set) var outgoing: [Outgoing] = []
    /// Why it could not start, in words.
    private(set) var problem: String?

    @ObservationIgnored private var service: TandemQuickShare?
    @ObservationIgnored private var sink: Sink?

    private var ownName: String { EngineModel.shared.myName }

    func startIfWanted() {
        if enabled && service == nil { start() }
    }

    private func start() {
        guard service == nil else { return }
        let sink = Sink(owner: self)
        do {
            service = try TandemQuickShare.start(deviceName: ownName, kind: .laptop, sink: sink)
            self.sink = sink
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }

    private func stop() {
        service?.stop()
        service = nil
        sink = nil
        peers = []
        incoming = []
        QuickSharePanel.shared.refresh()
    }

    // MARK: What the person does

    func accept(_ id: UInt64) {
        guard let index = incoming.firstIndex(where: { $0.id == id }) else { return }
        incoming[index].accepted = true
        service?.respond(id: id, folder: DownloadFolder.url.path)
    }

    func decline(_ id: UInt64) {
        service?.respond(id: id, folder: nil)
        incoming.removeAll { $0.id == id }
        QuickSharePanel.shared.refresh()
    }

    func dismiss(_ id: UInt64) {
        incoming.removeAll { $0.id == id }
        outgoing.removeAll { $0.id == id }
        QuickSharePanel.shared.refresh()
    }

    /// Sends a link or a note to a device that was found.
    func sendText(_ text: String, to peer: Peer) {
        guard let service, !text.isEmpty else { return }
        do {
            let id = try service.sendText(peerId: peer.id, ownName: ownName, ownKind: .laptop, text: text)
            outgoing.append(Outgoing(id: id, peerName: peer.name, total: UInt64(text.utf8.count)))
        } catch {
            FloatingToast.show(error.localizedDescription, symbol: "exclamationmark.circle.fill")
        }
    }

    /// Sends what is on the clipboard, when that is text.
    func sendClipboard(to peer: Peer) {
        if let text = NSPasteboard.general.string(forType: .string), !text.isEmpty {
            sendText(text, to: peer)
        } else {
            FloatingToast.show(String(localized: "There is no text on the clipboard"), symbol: "exclamationmark.circle.fill")
        }
    }

    /// Sends these files to a device that was found.
    func send(_ urls: [URL], to peer: Peer) {
        guard let service, !urls.isEmpty else { return }
        do {
            let id = try service.send(peerId: peer.id, ownName: ownName, ownKind: .laptop, paths: urls.map(\.path))
            let total = urls.reduce(UInt64(0)) { $0 + UInt64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
            outgoing.append(Outgoing(id: id, peerName: peer.name, total: total))
        } catch {
            FloatingToast.show(error.localizedDescription, symbol: "exclamationmark.circle.fill")
        }
    }

    func pickAndSend(to peer: Peer) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { send(panel.urls, to: peer) }
    }

    /// Two cards, for the snapshot harness: one waiting for an answer and one being received.
    func debugShow() {
        let files = [
            TandemQsFile(name: "IMG_20261005_141201.jpg", mime: "image/jpeg", size: 4_200_000),
            TandemQsFile(name: "Menu.pdf", mime: "application/pdf", size: 880_000),
        ]
        incoming = [
            Incoming(id: 9001, sender: "Pixel 9", pin: "4821", files: files),
            Incoming(id: 9002, sender: "Galaxy S26", pin: "1093", files: files, accepted: true, done: 3_000_000),
        ]
        peers = [Peer(id: "abcd", name: "Pixel 9", kind: .phone), Peer(id: "efgh", name: "Windows pc", kind: .laptop)]
        incoming.append(Incoming(id: 9003, sender: "Pixel 9", pin: "7710", files: [], texts: [TandemQsTextInfo(kind: .url, title: "https://tandem.markmaaktmedia.nl")]))
        QuickSharePanel.shared.refresh()
    }

    // MARK: What the service says

    fileprivate func found(_ peer: TandemQsPeer) {
        // A device that does not say who it is is not visible to everyone, and nothing can be sent to it.
        guard !peer.name.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let new = Peer(id: peer.id, name: peer.name, kind: peer.kind)
        if let index = peers.firstIndex(where: { $0.id == new.id }) { peers[index] = new } else { peers.append(new) }
    }

    fileprivate func lost(_ id: String) {
        peers.removeAll { $0.id == id }
    }

    fileprivate func asked(id: UInt64, sender: String, pin: String, files: [TandemQsFile], texts: [TandemQsTextInfo]) {
        incoming.append(Incoming(id: id, sender: sender, pin: pin, files: files, texts: texts))
        QuickSharePanel.shared.refresh()
        if !NSApp.isActive { NSSound(named: "Glass")?.play() }
    }

    fileprivate func progressed(id: UInt64, done: UInt64, total: UInt64) {
        if let index = incoming.firstIndex(where: { $0.id == id }) { incoming[index].done = done }
        if let index = outgoing.firstIndex(where: { $0.id == id }) {
            outgoing[index].done = done
            outgoing[index].total = total
        }
    }

    fileprivate func received(id: UInt64, paths: [String], texts: [TandemQsText]) {
        guard let index = incoming.firstIndex(where: { $0.id == id }) else { return }
        incoming[index].saved = paths
        incoming[index].receivedTexts = texts
        incoming[index].done = incoming[index].total
        // What was sent as text is put on the clipboard, so it can be pasted at once.
        if let last = texts.last {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(last.text, forType: .string)
        }
        QuickSharePanel.shared.refresh()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(12))
            if let index = self.incoming.firstIndex(where: { $0.id == id }), self.incoming[index].saved != nil { self.dismiss(id) }
        }
    }

    fileprivate func showedPin(id: UInt64, pin: String) {
        if let index = outgoing.firstIndex(where: { $0.id == id }) { outgoing[index].pin = pin }
    }

    fileprivate func finished(id: UInt64, refused: Bool) {
        guard let index = outgoing.firstIndex(where: { $0.id == id }) else { return }
        outgoing[index].state = refused ? .refused : .sent
        scheduleRemoval(of: id)
    }

    fileprivate func failed(id: UInt64, reason: String) {
        if let index = incoming.firstIndex(where: { $0.id == id }) {
            incoming[index].failure = reason
            QuickSharePanel.shared.refresh()
            scheduleRemoval(of: id)
        }
        if let index = outgoing.firstIndex(where: { $0.id == id }) {
            outgoing[index].state = .failed(reason)
            scheduleRemoval(of: id)
        }
        // A transfer that broke before anything was offered has no card: say it, or the sender is left wondering why nothing came.
        if !incoming.contains(where: { $0.id == id }) && !outgoing.contains(where: { $0.id == id }) {
            FloatingToast.show(String(localized: "A Quick Share transfer failed before anything was offered (\(reason))"), symbol: "exclamationmark.triangle.fill")
        }
    }

    private func scheduleRemoval(of id: UInt64) {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(8))
            self.dismiss(id)
        }
    }
}

/// Carries what the service says to the main actor.
private final class Sink: TandemQuickShareSink, @unchecked Sendable {
    private weak var owner: QuickShare?

    init(owner: QuickShare) { self.owner = owner }

    func peerFound(peer: TandemQsPeer) { Task { @MainActor in owner?.found(peer) } }
    func peerLost(id: String) { Task { @MainActor in owner?.lost(id) } }
    func incoming(id: UInt64, sender: String, pin: String, files: [TandemQsFile], texts: [TandemQsTextInfo]) {
        Task { @MainActor in owner?.asked(id: id, sender: sender, pin: pin, files: files, texts: texts) }
    }
    func progress(id: UInt64, done: UInt64, total: UInt64) { Task { @MainActor in owner?.progressed(id: id, done: done, total: total) } }
    func received(id: UInt64, paths: [String], texts: [TandemQsText]) { Task { @MainActor in owner?.received(id: id, paths: paths, texts: texts) } }
    func pin(id: UInt64, pin: String) { Task { @MainActor in owner?.showedPin(id: id, pin: pin) } }
    func sent(id: UInt64, refused: Bool) { Task { @MainActor in owner?.finished(id: id, refused: refused) } }
    func failed(id: UInt64, reason: String) { Task { @MainActor in owner?.failed(id: id, reason: reason) } }
}

extension TandemQsKind {
    var symbol: String {
        switch self {
        case .phone: "iphone.gen3"
        case .tablet: "ipad"
        case .laptop: "laptopcomputer"
        case .unknown: "desktopcomputer"
        }
    }
}
