import AppKit
import Observation
import TandemCore

/// Focus the same on every device (see `focus.rs` in the core), on this Mac through the Tandem Focus shortcut ([FocusShortcut]). The
/// Mac says what its Focus is now and the core works out what is news for the others: a change that only follows another device does
/// not go back. Nothing tells an app when a Focus changes, so the shortcut is asked every few seconds while this Mac shares.
@MainActor
@Observable
final class FocusShare {
    static let shared = FocusShare()
    static let shareKey = "focusShare"
    static let followOnlyKey = "focusFollowOnly"

    /// The Tandem Focus shortcut is in the library of this Mac, as last looked.
    private(set) var installed = false
    @ObservationIgnored private var engine: TandemEngine?
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var watchingAdd: Task<Void, Never>?

    var share: Bool { UserDefaults.standard.object(forKey: Self.shareKey) as? Bool ?? true }
    var followOnly: Bool { UserDefaults.standard.bool(forKey: Self.followOnlyKey) }

    func start(engine: TandemEngine) {
        self.engine = engine
        loop?.cancel()
        loop = Task { [weak self] in
            var round = 0
            while !Task.isCancelled {
                guard let self else { return }
                if self.share {
                    // The library changes only when the person adds or removes the shortcut: looked at once a minute.
                    if round % 4 == 0 { await self.refresh() }
                    await self.read()
                    round += 1
                }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    /// Says to the core what this Mac does now. It follows whenever sharing is on, so that a Focus that arrives before the shortcut
    /// is there is the moment to say so; it only sends what the shortcut can read.
    func configure() {
        engine?.setFocusSharing(send: share && !followOnly && installed, follow: share)
    }

    func refresh() async {
        installed = await FocusShortcut.isInstalled()
        configure()
    }

    /// The Focus of this Mac now, to the core. Also while nobody is connected: a change made then is the newest one when they are back.
    private func read() async {
        guard share, !followOnly, installed, let engine, let name = await FocusShortcut.current() else { return }
        await engine.focusLocal(on: !name.isEmpty, name: name)
    }

    /// Another device turned its Focus on or off.
    func follow(origin: String, on: Bool) {
        guard share else { return }
        guard installed else {
            if on { askToAdd(device: EngineModel.shared.device(origin)?.name ?? String(localized: "Another device")) }
            return
        }
        Task {
            _ = await FocusShortcut.set(on)
            // At once rather than at the next round, so the change is known to be a follow before anything else is read.
            await read()
        }
    }

    /// Adds the shortcut: Shortcuts asks the person, and the shortcut is looked for every few seconds until it is there.
    func add() async -> FocusShortcut.Added {
        let added = await FocusShortcut.add()
        if added == .opened {
            watchingAdd?.cancel()
            watchingAdd = Task { [weak self] in
                for _ in 0..<60 where !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(2))
                    guard let self else { return }
                    await self.refresh()
                    if self.installed { return }
                }
            }
        }
        return added
    }

    /// Adds the shortcut, or opens the place to sign in to iCloud when signing it needs that. For a click on the notification below and
    /// for the button another device shows.
    func addOrSignIn() {
        Task {
            if await add() == .needsICloud { Self.openAppleAccount() }
        }
    }

    /// Where the person signs in to iCloud, which signing the shortcut needs.
    static func openAppleAccount() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.systempreferences.AppleIDSettings") { NSWorkspace.shared.open(url) }
    }

    static let notificationCategory = "tandem.focus"

    private func askToAdd(device: String) {
        let key = "focusAskedAt"
        let last = UserDefaults.standard.double(forKey: key)
        guard Date().timeIntervalSince1970 - last > 12 * 60 * 60 else { return }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: key)
        Notifier.shared.post(
            id: "focus-add",
            title: String(localized: "\(device) turned on Focus"),
            body: String(localized: "Click to add the Tandem Focus shortcut, so this Mac follows."),
            category: Self.notificationCategory
        )
    }
}
