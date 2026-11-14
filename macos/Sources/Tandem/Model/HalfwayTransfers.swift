import Foundation
import SwiftUI
import TandemCore

/// Incoming transfers that stopped halfway before this start (P2). Nothing goes on by itself: each waits on the page Shared and in a
/// notification until the person says go on or throw away. See docs/TRANSFERS.md, which the other apps follow.
@MainActor
@Observable
final class HalfwayTransfers {
    static let shared = HalfwayTransfers()
    static let notificationCategory = "tandem.halfway"
    static let goOnAction = "goon"
    static let throwAwayAction = "throwaway"

    private(set) var waiting: [TandemResumable] = []
    /// Thrown away by the person: its files end as cancelled, which needs no row of its own.
    @ObservationIgnored private var thrownAway: Set<String> = []
    /// Asked about once per start in a notification; the page keeps asking.
    @ObservationIgnored private var told: Set<String> = []

    private static func key(_ from: String, _ offer: UInt64) -> String { "\(from)-\(offer)" }

    /// After the start and whenever a device connects, so Go on is ready as soon as the sender is.
    func refresh() {
        guard let engine = EngineModel.shared.tandem else { return }
        let found = engine.resumableTransfers()
        if found != waiting {
            withAnimation(.tandem) { waiting = found }
        }
        for transfer in found where told.insert(Self.key(transfer.from, transfer.offer)).inserted {
            notify(transfer)
        }
        DebugSupport.write(found.map { "\($0.from) \($0.offer) \(title($0)) \(bytes($0).done)/\(bytes($0).total)" }.joined(separator: "\n") + "\n", named: "halfway.txt")
    }

    func goOn(_ transfer: TandemResumable) {
        let model = EngineModel.shared
        guard let engine = model.tandem else { return }
        // The button is grey while the sender is away, and says why when it is used.
        guard model.device(transfer.from)?.online == true else {
            model.showToast(String(localized: "\(senderName(transfer)) is not connected. Open Tandem there to go on."))
            return
        }
        do {
            try engine.resumeTransfer(from: transfer.from, offer: transfer.offer)
            settle(transfer)
        } catch {
            model.showToast(error.localizedDescription)
            refresh()
        }
    }

    func throwAway(_ transfer: TandemResumable) {
        guard let engine = EngineModel.shared.tandem else { return }
        thrownAway.insert(Self.key(transfer.from, transfer.offer))
        settle(transfer)
        Task {
            try? await engine.discardTransfer(from: transfer.from, offer: transfer.offer)
            refresh()
        }
    }

    /// A file of a transfer that was thrown away ended: the person chose that, so it gets no row on the page Shared.
    func swallows(offer: UInt64, from: String) -> Bool {
        thrownAway.contains(Self.key(from, offer))
    }

    /// What the notification answered: a button, or a click, which opens the page Shared where the question is.
    func answer(from: String, offer: UInt64, action: String) {
        guard let transfer = waiting.first(where: { $0.from == from && $0.offer == offer }) else {
            AppScenes.showMain(.shared)
            return
        }
        switch action {
        case Self.goOnAction: goOn(transfer)
        case Self.throwAwayAction: throwAway(transfer)
        default: AppScenes.showMain(.shared)
        }
    }

    func senderName(_ transfer: TandemResumable) -> String {
        EngineModel.shared.device(transfer.from)?.name ?? String(localized: "the other device")
    }

    /// The first file that did not come in whole, and how many more there are.
    func title(_ transfer: TandemResumable) -> String {
        let open = transfer.items.filter { !$0.ended }
        guard let first = open.first ?? transfer.items.first else { return "" }
        return open.count > 1 ? String(localized: "\(first.name) and \(open.count - 1) more") : first.name
    }

    func bytes(_ transfer: TandemResumable) -> (done: UInt64, total: UInt64) {
        transfer.items.reduce((0, 0)) { ($0.0 + min($1.done, $1.size), $0.1 + $1.size) }
    }

    private func settle(_ transfer: TandemResumable) {
        Notifier.shared.remove(id: notificationID(transfer))
        withAnimation(.tandem) { waiting.removeAll { $0.from == transfer.from && $0.offer == transfer.offer } }
    }

    private func notificationID(_ transfer: TandemResumable) -> String {
        "halfway.\(Self.key(transfer.from, transfer.offer))"
    }

    private func notify(_ transfer: TandemResumable) {
        Notifier.shared.post(
            id: notificationID(transfer),
            title: String(localized: "A transfer from \(senderName(transfer)) stopped halfway"),
            body: String(localized: "\(title(transfer)). Go on or throw away?"),
            category: Self.notificationCategory,
            userInfo: ["from": transfer.from, "offer": String(transfer.offer)]
        )
    }
}
