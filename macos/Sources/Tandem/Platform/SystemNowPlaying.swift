import AppKit
import MediaPlayer
import TandemCore

/// Puts the music of a phone into the system's Now Playing, as if this Mac played it: the media keys of the keyboard
/// and of headphones, Control Center, and every app that shows what plays (a notch app, say) then see it and control
/// it. Tandem plays nothing itself. The buttons that come in are sent on to the phone.
///
/// macOS accepts an app that plays no audio as a Now Playing app as long as it says what plays and handles the remote
/// commands, so there is no sound and no hidden player here. What plays is only published while the phone has a
/// player worth showing, and a paused one is let go after a few minutes, so the play key goes back to whatever this
/// Mac played last.
@MainActor
final class SystemNowPlaying {
    struct Item: Equatable {
        var deviceID: String
        var playerID: String
        var title: String
        var artist: String
        var album: String
        var playing: Bool
        var durationMs: UInt64?
        /// Where the music is, in seconds, at the moment this was made.
        var elapsed: Double?
        var canPrevious: Bool
        var canNext: Bool
        var canSeek: Bool
        var art: UInt64

        init(deviceID: String, player: TandemMediaPlayer, elapsed: Double?) {
            self.deviceID = deviceID
            playerID = player.id
            title = player.title
            artist = player.artist
            album = player.album
            playing = player.playing
            durationMs = player.durationMs
            self.elapsed = elapsed
            canPrevious = player.canPrev
            canNext = player.canNext
            canSeek = player.canSeek
            art = player.art
        }
    }

    /// A button pressed on the keyboard, on headphones or in Control Center, for the phone's player.
    var onCommand: ((_ action: TandemMediaAction, _ positionMs: UInt64?) -> Void)?

    private let center = MPNowPlayingInfoCenter.default()
    private let commands = MPRemoteCommandCenter.shared()
    private var targets: [(command: MPRemoteCommand, token: Any)] = []

    private var published: Item?
    private var publishedAt = Date.distantPast
    private var publishedCover: NSImage?
    private var pausedSince: Date?
    private var pauseTimer: Task<Void, Never>?
    /// A player that stayed paused is let go; it comes back when it plays again.
    private var released = false

    /// How long a paused player keeps the media keys.
    private static let pauseLimit: Duration = .seconds(300)

    /// What to show now, or nil when there is nothing (the phone is gone, plays nothing, or the person turned this off).
    func update(_ item: Item?, cover: NSImage?) {
        guard let item else {
            released = false
            clear()
            return
        }
        if item.playing {
            released = false
            pausedSince = nil
            pauseTimer?.cancel()
        } else {
            if released { return }
            if pausedSince == nil {
                pausedSince = Date()
                pauseTimer?.cancel()
                pauseTimer = Task { @MainActor [weak self] in
                    try? await Task.sleep(for: Self.pauseLimit)
                    guard !Task.isCancelled, let self else { return }
                    self.released = true
                    self.clear()
                }
            }
        }
        if !needsPublishing(item, cover: cover) { return }
        install()
        publish(item, cover: cover)
    }

    /// Whether this says anything the system does not already know. The position counts as known while it is where
    /// the system, which counts it on by itself, would have it.
    private func needsPublishing(_ item: Item, cover: NSImage?) -> Bool {
        guard let published else { return true }
        var left = published
        var right = item
        left.elapsed = nil
        right.elapsed = nil
        if left != right { return true }
        if (publishedCover == nil) != (cover == nil) { return true }
        guard let was = published.elapsed, let now = item.elapsed else { return false }
        let expected = was + (published.playing ? Date().timeIntervalSince(publishedAt) : 0)
        return abs(now - expected) > 2
    }

    private func publish(_ item: Item, cover: NSImage?) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.title,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: item.playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if !item.artist.isEmpty { info[MPMediaItemPropertyArtist] = item.artist }
        if !item.album.isEmpty { info[MPMediaItemPropertyAlbumTitle] = item.album }
        if let duration = item.durationMs, duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = Double(duration) / 1000 }
        if let elapsed = item.elapsed { info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed }
        if let cover {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: cover.size) { _ in cover }
        }
        center.nowPlayingInfo = info
        center.playbackState = item.playing ? .playing : .paused

        commands.playCommand.isEnabled = true
        commands.pauseCommand.isEnabled = true
        commands.togglePlayPauseCommand.isEnabled = true
        commands.previousTrackCommand.isEnabled = item.canPrevious
        commands.nextTrackCommand.isEnabled = item.canNext
        commands.changePlaybackPositionCommand.isEnabled = item.canSeek

        published = item
        publishedAt = Date()
        publishedCover = cover
    }

    /// Lets go of the system's Now Playing: nothing is shown and the media keys are not ours.
    func clear() {
        pauseTimer?.cancel()
        pauseTimer = nil
        pausedSince = nil
        guard published != nil || !targets.isEmpty else { return }
        for command in [
            commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand,
            commands.previousTrackCommand, commands.nextTrackCommand, commands.changePlaybackPositionCommand,
        ] {
            command.isEnabled = false
        }
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
        published = nil
        publishedCover = nil
    }

    // MARK: Commands

    private func install() {
        guard targets.isEmpty else { return }
        add(commands.playCommand) { _ in .play }
        add(commands.pauseCommand) { _ in .pause }
        add(commands.togglePlayPauseCommand) { _ in .toggle }
        add(commands.nextTrackCommand) { _ in .next }
        add(commands.previousTrackCommand) { _ in .previous }
        add(commands.changePlaybackPositionCommand) { _ in .seek }
    }

    private func add(_ command: MPRemoteCommand, _ action: @escaping (MPRemoteCommandEvent) -> TandemMediaAction) {
        let token = command.addTarget { [weak self] event in
            let wanted = action(event)
            let position = (event as? MPChangePlaybackPositionCommandEvent).map { UInt64(max(0, $0.positionTime) * 1000) }
            // The system calls this on a queue of its own.
            Task { @MainActor in self?.onCommand?(wanted, position) }
            return .success
        }
        targets.append((command, token))
    }
}
