import Foundation
import SwiftUI
import TandemCore

/// What is underway and incoming (P3b): every transfer that runs, waits for a yes, waits in the queue for a device that is away (P3), or
/// failed and can be sent again, read from the journal of the core. Transfers that stopped halfway are `HalfwayTransfers`. See
/// docs/TRANSFERS.md, which the other apps follow.
@MainActor
@Observable
final class UnderwayTransfers {
    static let shared = UnderwayTransfers()

    enum Kind: Hashable {
        /// Outgoing: the device is away, the offer goes when it is back.
        case queued
        /// Outgoing: waits for a yes on the other device. Incoming: waits for a yes here.
        case waiting
        case running
        /// Outgoing: files that did not arrive and can be sent again.
        case failed
    }

    /// One transfer with one device: an outgoing offer has a row per receiver, so each can be stopped on its own.
    struct Row: Identifiable, Hashable {
        let transfer: TandemTransfer
        let peer: TandemTransferPeer
        let kind: Kind
        var id: String { "\(transfer.incoming ? "in" : "out")-\(transfer.offer)-\(peer.device)" }
        var incoming: Bool { transfer.incoming }
    }

    private(set) var all: [TandemTransfer] = []
    /// Failed rows the person sent again or put away.
    private var putAway: Set<String> = []
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var lastRefresh = Date.distantPast

    var outgoing: [Row] { rows.filter { !$0.incoming } }
    var incoming: [Row] { rows.filter(\.incoming) }

    private var rows: [Row] {
        all.flatMap { transfer in
            transfer.peers.compactMap { peer -> Row? in
                guard let kind = Self.kind(transfer, peer) else { return nil }
                let row = Row(transfer: transfer, peer: peer, kind: kind)
                return putAway.contains(row.id) ? nil : row
            }
        }
    }

    private static func kind(_ transfer: TandemTransfer, _ peer: TandemTransferPeer) -> Kind? {
        // Taken by the clipboard, a drop or Insert from phone: those show themselves, and a question here would only puzzle.
        if transfer.incoming {
            switch transfer.origin {
            case .clipboard, .drag, .capture: return nil
            default: break
            }
        }
        if transfer.halfway { return nil }
        let open = peer.items.contains { [.waiting, .running, .interrupted].contains($0.state) }
        switch peer.stage {
        case .queued: return .queued
        case .offered: return .waiting
        case .accepted where open: return .running
        default: break
        }
        // Only what was sent from here can be sent again; a file that came in failed is on the list below with its reason.
        let failed = peer.items.contains { $0.state == .failed }
        return !transfer.incoming && failed ? .failed : nil
    }

    /// After the start, on every event about a transfer and when a device comes or goes. Progress comes ten times a second per file,
    /// so it reads at most every half second; the end of a file reads at once.
    func refresh(soon: Bool = false) {
        if soon {
            guard !refreshing else { return }
            let wait = max(0, 0.5 - Date().timeIntervalSince(lastRefresh))
            refreshing = true
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                self?.refreshing = false
                self?.refresh()
            }
            return
        }
        guard let engine = EngineModel.shared.tandem else { return }
        lastRefresh = Date()
        let found = engine.transfers()
        if found != all {
            withAnimation(.tandem) { all = found }
        }
        DebugSupport.write(
            rows.map { "\($0.id) \($0.kind) \(title($0)) \(bytes($0).done)/\(bytes($0).total)" }.joined(separator: "\n") + "\n",
            named: "underway.txt"
        )
    }

    func name(_ row: Row) -> String {
        EngineModel.shared.device(row.peer.device)?.name ?? String(localized: "the other device")
    }

    /// The first file that has not ended (or, for a failed row, the first that failed), and how many more there are.
    func title(_ row: Row) -> String {
        let indices = row.peer.items.indices.filter { index in
            row.kind == .failed ? row.peer.items[index].state == .failed : !Self.ended(row.peer.items[index].state)
        }
        let shown = indices.isEmpty ? Array(row.transfer.items.indices) : indices
        guard let first = shown.first, first < row.transfer.items.count else { return "" }
        let name = row.transfer.items[first].name
        return shown.count > 1 ? String(localized: "\(name) and \(shown.count - 1) more") : name
    }

    func bytes(_ row: Row) -> (done: UInt64, total: UInt64) {
        zip(row.transfer.items, row.peer.items).reduce((0, 0)) { sum, pair in
            let (item, progress) = pair
            let done = progress.state == .done ? item.size : min(progress.done, item.size)
            return (sum.0 + done, sum.1 + item.size)
        }
    }

    /// The reason of the first file that failed, in words.
    func reason(_ row: Row) -> String {
        let error = row.peer.items.first { $0.state == .failed }?.error
        return TransferReason.text(error, incoming: row.incoming, peerName: name(row))
    }

    // MARK: Answers

    /// Stops it for that one device; the others an outgoing offer went to keep going (R-B8).
    func stop(_ row: Row) {
        guard let engine = EngineModel.shared.tandem else { return }
        Task {
            try? await engine.cancelTransfer(peer: row.peer.device, offer: row.transfer.offer, incoming: row.incoming)
            refresh()
        }
    }

    func accept(_ row: Row) {
        let model = EngineModel.shared
        guard let engine = model.tandem else { return }
        do {
            try engine.acceptOffer(from: row.peer.device, offer: row.transfer.offer)
            Notifier.shared.remove(id: "offer.\(row.peer.device)")
        } catch {
            model.showToast(error.localizedDescription)
        }
        refresh()
    }

    func decline(_ row: Row) {
        guard let engine = EngineModel.shared.tandem else { return }
        Notifier.shared.remove(id: "offer.\(row.peer.device)")
        Task {
            try? await engine.declineOffer(from: row.peer.device, offer: row.transfer.offer)
            refresh()
        }
    }

    /// Sends the files that failed once more, as a new offer to the same device.
    func sendAgain(_ row: Row) {
        let urls = zip(row.transfer.items, row.peer.items)
            .filter { $0.1.state == .failed && !$0.0.source.isEmpty }
            .map { URL(fileURLWithPath: $0.0.source) }
        let missing = urls.filter { !FileManager.default.fileExists(atPath: $0.path) }
        guard missing.isEmpty, !urls.isEmpty else {
            EngineModel.shared.showToast(String(localized: "The file is no longer there"))
            return
        }
        EngineModel.shared.send(urls: urls, to: [row.peer.device])
        putAwayRow(row)
    }

    func putAwayRow(_ row: Row) {
        _ = withAnimation(.tandem) { putAway.insert(row.id) }
    }

    private static func ended(_ state: TandemItemState) -> Bool {
        ![.waiting, .running, .interrupted].contains(state)
    }
}
