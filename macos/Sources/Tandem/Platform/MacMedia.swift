import AppKit
import Foundation
import Observation
import TandemCore

/// The music apps on this Mac that say what they play and can be controlled from outside: Spotify
/// and Music. Both announce every new track and every play or pause to anyone who listens, without
/// a permission. Pressing their buttons is the part that asks, once, under Privacy and Security,
/// Automation. Anything else (a browser tab, a video app) cannot be read: macOS keeps it from apps.
@MainActor
@Observable
final class MacMedia {
    struct Source {
        let bundleID: String
        let name: String
        /// The names the app posts its changes under.
        let notifications: [String]
        /// What the length and the position are called in what it posts, if it says.
        let durationKey: String
        let positionKey: String?
    }

    static let sources = [
        Source(
            bundleID: "com.spotify.client", name: "Spotify",
            notifications: ["com.spotify.client.PlaybackStateChanged"],
            durationKey: "Duration", positionKey: "Playback Position"
        ),
        Source(
            bundleID: "com.apple.Music", name: "Music",
            notifications: ["com.apple.Music.playerInfo", "com.apple.iTunes.playerInfo"],
            durationKey: "Total Time", positionKey: nil
        ),
    ]

    /// What plays right now, newest change first.
    private(set) var players: [TandemMediaPlayer] = []

    @ObservationIgnored var onChange: (() -> Void)?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var started = false

    func start() {
        guard !started else { return }
        started = true
        let distributed = DistributedNotificationCenter.default()
        for source in Self.sources {
            for name in source.notifications {
                let token = distributed.addObserver(forName: NSNotification.Name(name), object: nil, queue: .main) { [weak self] note in
                    let info = note.userInfo ?? [:]
                    MainActor.assumeIsolated { self?.ingest(source, info) }
                }
                observers.append(token)
            }
        }
        // An app that quits does not say it stopped.
        let quit = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didTerminateApplicationNotification, object: nil, queue: .main
        ) { [weak self] note in
            let id = (note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.bundleIdentifier
            MainActor.assumeIsolated { if let id { self?.remove(id) } }
        }
        observers.append(quit)
    }

    func stop() {
        started = false
        let distributed = DistributedNotificationCenter.default()
        for token in observers {
            distributed.removeObserver(token)
            NSWorkspace.shared.notificationCenter.removeObserver(token)
        }
        observers.removeAll()
        if !players.isEmpty {
            players = []
            onChange?()
        }
    }

    // MARK: Reading

    private func ingest(_ source: Source, _ info: [AnyHashable: Any]) {
        let state = (info["Player State"] as? String)?.lowercased() ?? ""
        // "Stopped" comes without a track. There is nothing left to show.
        guard state == "playing" || state == "paused", let title = info["Name"] as? String, !title.isEmpty else {
            remove(source.bundleID)
            return
        }
        let duration = (info[source.durationKey] as? NSNumber)?.uint64Value
        let position = source.positionKey
            .flatMap { info[$0] as? NSNumber }
            .map { UInt64(max(0, $0.doubleValue) * 1000) }
        let player = TandemMediaPlayer(
            id: source.bundleID,
            app: source.name,
            title: title,
            artist: info["Artist"] as? String ?? "",
            album: info["Album"] as? String ?? "",
            playing: state == "playing",
            positionMs: position,
            durationMs: duration.flatMap { $0 > 0 ? $0 : nil },
            canPrev: true,
            canNext: true,
            canSeek: true,
            art: 0
        )
        players.removeAll { $0.id == source.bundleID }
        players.insert(player, at: 0)
        onChange?()
    }

    private func remove(_ bundleID: String) {
        guard players.contains(where: { $0.id == bundleID }) else { return }
        players.removeAll { $0.id == bundleID }
        onChange?()
    }

    // MARK: Controlling

    enum Outcome { case done, notRunning, notAllowed, failed }

    /// Presses a button in one of the players above. Runs the script off the main thread, because
    /// the first call waits for the person to answer the permission question.
    func perform(_ action: TandemMediaAction, player: String, positionMs: UInt64?, completion: @escaping @MainActor (Outcome) -> Void) {
        guard Self.sources.contains(where: { $0.bundleID == player }) else { return completion(.failed) }
        guard !NSRunningApplication.runningApplications(withBundleIdentifier: player).isEmpty else { return completion(.notRunning) }
        let verb: String
        switch action {
        case .play: verb = "play"
        case .pause: verb = "pause"
        case .toggle: verb = "playpause"
        case .next: verb = "next track"
        case .previous: verb = "previous track"
        case .seek:
            guard let positionMs else { return completion(.failed) }
            verb = "set player position to \(Double(positionMs) / 1000)"
        }
        // `tell application id` would launch a closed app: the check above makes sure it is open.
        let source = "tell application id \"\(player)\" to \(verb)"
        DispatchQueue.global(qos: .userInitiated).async {
            var error: NSDictionary?
            NSAppleScript(source: source)?.executeAndReturnError(&error)
            let code = error?[NSAppleScript.errorNumber] as? Int
            let outcome: Outcome = error == nil ? .done : (code == -1743 ? .notAllowed : .failed)
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(outcome) } }
        }
    }
}
