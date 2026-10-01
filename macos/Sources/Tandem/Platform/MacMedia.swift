import AppKit
import ApplicationServices
import Foundation
import Observation
import TandemCore

/// The music apps on this Mac that say what they play and can be controlled from outside: Spotify
/// and Music. Both announce every new track and every play or pause to anyone who listens, without
/// a permission. Pressing their buttons is the part that asks, once, under Privacy and Security,
/// Automation. Anything else (a browser tab, a video app) cannot be read: macOS keeps it from apps.
///
/// The covers are not in what the apps announce. A Spotify cover is looked up at Spotify by the id of the track, which
/// needs no permission; a cover from Music is asked of Music, and only once the person has allowed that for the buttons.
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

    /// Covers by key. A key names a cover, not a track, so a track that comes back is not fetched again.
    @ObservationIgnored private var covers: [UInt64: Data] = [:]
    @ObservationIgnored private var coverOrder: [UInt64] = []
    @ObservationIgnored private var fetching: Set<UInt64> = []
    /// Covers that could not be had, and when, so a track with none (a local file, an ad) is not asked for at every pause.
    @ObservationIgnored private var failed: [UInt64: Date] = [:]
    /// The key of the cover of what each app plays right now.
    @ObservationIgnored private var currentKeys: [String: UInt64] = [:]

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
        // The key goes on the player only once the cover is in hand, so a phone is never told to expect one that is
        // not coming. When it arrives the player is sent again with its key.
        var art: UInt64 = 0
        if let cover = coverRequest(source, info) {
            currentKeys[source.bundleID] = cover.key
            if covers[cover.key] != nil { art = cover.key } else { fetchCover(cover) }
        } else {
            currentKeys[source.bundleID] = nil
        }
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
            art: art
        )
        players.removeAll { $0.id == source.bundleID }
        players.insert(player, at: 0)
        onChange?()
    }

    private func remove(_ bundleID: String) {
        currentKeys[bundleID] = nil
        guard players.contains(where: { $0.id == bundleID }) else { return }
        players.removeAll { $0.id == bundleID }
        onChange?()
    }

    // MARK: Covers

    private enum CoverSource {
        case spotify(uri: String)
        case music
    }

    private struct CoverRequest {
        let key: UInt64
        let source: CoverSource
    }

    /// The cover of what a message announces, or nothing when there is no way to ask for it.
    private func coverRequest(_ source: Source, _ info: [AnyHashable: Any]) -> CoverRequest? {
        if source.bundleID == "com.spotify.client" {
            // A local file has no cover at Spotify.
            guard let uri = info["Track ID"] as? String, uri.hasPrefix("spotify:"), !uri.hasPrefix("spotify:local") else { return nil }
            return CoverRequest(key: Self.key(uri), source: .spotify(uri: uri))
        }
        // Every track of an album shares its cover, so the album is what names it.
        let album = info["Album"] as? String ?? ""
        let artist = info["Album Artist"] as? String ?? info["Artist"] as? String ?? ""
        let name = album.isEmpty ? (info["Name"] as? String ?? "") : album
        guard !name.isEmpty else { return nil }
        return CoverRequest(key: Self.key("music:\(artist)|\(name)"), source: .music)
    }

    private func fetchCover(_ request: CoverRequest) {
        guard !fetching.contains(request.key) else { return }
        if let when = failed[request.key], Date().timeIntervalSince(when) < 300 { return }
        fetching.insert(request.key)
        Task { [weak self] in
            let data: Data?
            switch request.source {
            case let .spotify(uri): data = await Self.spotifyCover(uri)
            case .music: data = await Self.musicCover()
            }
            self?.coverArrived(request.key, data)
        }
    }

    private func coverArrived(_ key: UInt64, _ data: Data?) {
        fetching.remove(key)
        guard let data else {
            failed[key] = Date()
            return
        }
        failed[key] = nil
        covers[key] = data
        coverOrder.append(key)
        if coverOrder.count > 16 { covers[coverOrder.removeFirst()] = nil }
        // What shows this cover now says so, and goes out again with the key.
        var changed = false
        for index in players.indices where currentKeys[players[index].id] == key && players[index].art != key {
            players[index].art = key
            changed = true
        }
        if changed { onChange?() }
    }

    /// The JPEG of a cover that is in hand, for sending.
    func cover(for key: UInt64) -> Data? { covers[key] }

    private static func key(_ text: String) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in text.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        return hash == 0 ? 1 : hash
    }

    /// Asks Spotify's public embed endpoint for the cover of a track or episode: no account, no permission. Only an
    /// image from Spotify's own servers is taken, since the answer comes from the network.
    nonisolated private static func spotifyCover(_ uri: String) async -> Data? {
        let parts = uri.split(separator: ":")
        guard parts.count == 3, parts[1] == "track" || parts[1] == "episode" else { return nil }
        var components = URLComponents(string: "https://open.spotify.com/oembed")
        components?.queryItems = [URLQueryItem(name: "url", value: "https://open.spotify.com/\(parts[1])/\(parts[2])")]
        guard let endpoint = components?.url else { return nil }
        struct Embed: Decodable { let thumbnail_url: String? }
        do {
            let (page, response) = try await URLSession.shared.data(for: URLRequest(url: endpoint, timeoutInterval: 8))
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let thumbnail = try? JSONDecoder().decode(Embed.self, from: page).thumbnail_url,
                  let image = URL(string: thumbnail), image.scheme == "https",
                  let host = image.host, host.hasSuffix("spotifycdn.com") || host.hasSuffix("scdn.co")
            else { return nil }
            let (jpeg, answer) = try await URLSession.shared.data(for: URLRequest(url: image, timeoutInterval: 8))
            guard (answer as? HTTPURLResponse)?.statusCode == 200, jpeg.count < 400_000, NSImage(data: jpeg) != nil else { return nil }
            return jpeg
        } catch {
            return nil
        }
    }

    /// Asks Music for the cover of the track it plays, as a small JPEG. Only when the person has already allowed
    /// Tandem to control Music: this never opens the permission question by itself.
    nonisolated private static func musicCover() async -> Data? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let bundleID = "com.apple.Music"
                guard !NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).isEmpty,
                      let address = NSAppleEventDescriptor(bundleIdentifier: bundleID).aeDesc,
                      AEDeterminePermissionToAutomateTarget(address, AEEventClass(typeWildCard), AEEventID(typeWildCard), false) == noErr
                else { return continuation.resume(returning: nil) }
                var error: NSDictionary?
                let script = NSAppleScript(source: """
                    tell application id "com.apple.Music"
                        if (count of artworks of current track) is 0 then return missing value
                        return data of artwork 1 of current track
                    end tell
                    """)
                let result = script?.executeAndReturnError(&error)
                guard error == nil, let raw = result?.data, let jpeg = Self.smallJPEG(raw, longest: 320) else {
                    return continuation.resume(returning: nil)
                }
                continuation.resume(returning: jpeg)
            }
        }
    }

    /// Any picture as a JPEG of at most `longest` points on its long side. Music hands out TIFF, which is far too big
    /// to send and which the phone cannot read.
    nonisolated private static func smallJPEG(_ data: Data, longest: CGFloat) -> Data? {
        guard let image = NSImage(data: data) else { return nil }
        // In pixels, from the biggest representation: the size in points depends on the resolution the file claims.
        let pixels = image.representations
            .filter { $0.pixelsWide > 0 && $0.pixelsHigh > 0 }
            .map { CGSize(width: CGFloat($0.pixelsWide), height: CGFloat($0.pixelsHigh)) }
            .max { $0.width < $1.width }
        guard let pixels else { return nil }
        let scale = min(CGFloat(1), longest / max(pixels.width, pixels.height))
        let width = max(1, Int((pixels.width * scale).rounded()))
        let height = max(1, Int((pixels.height * scale).rounded()))
        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4,
            hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ), let context = NSGraphicsContext(bitmapImageRep: bitmap) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        image.draw(in: CGRect(x: 0, y: 0, width: width, height: height), from: .zero, operation: .copy, fraction: 1)
        NSGraphicsContext.restoreGraphicsState()
        return bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.8])
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
