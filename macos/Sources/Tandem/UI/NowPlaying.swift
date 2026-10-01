import SwiftUI
import TandemCore

/// What a phone is playing, with the buttons to control it. Players that play what this Mac already
/// plays are left out: a phone that only remote controls the Mac's Spotify is not a second player.
struct NowPlayingCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let players = (device.online || device.ble) ? model.visibleMedia(for: device.id) : []
        if !players.isEmpty {
            VStack(spacing: 10) {
                ForEach(players, id: \.id) { PlayerCard(device: device, player: $0) }
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

private struct PlayerCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer

    var body: some View {
        Card(radius: Metrics.card, padding: 16) {
            HStack(spacing: 14) {
                PlayerCover(player: player, size: 64)
                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(player.title).font(.headline).lineLimit(1)
                        Text([player.artist, player.app].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    PlayerProgress(device: device, player: player)
                }
                PlayerButtons(device: device, player: player, size: .regular)
            }
        }
    }
}

struct PlayerCover: View {
    @Environment(EngineModel.self) private var model
    let player: TandemMediaPlayer
    var size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Color.primary.opacity(0.07))
            if let image = model.mediaArt[player.art], player.art != 0 {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: size * 0.4)).foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }
}

/// The position is only sent when something changes, so it is counted on from there while it plays.
/// A click on the bar jumps to that place, when the app allows it.
private struct PlayerProgress: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer

    var body: some View {
        if let start = player.positionMs, let duration = player.durationMs, duration > 0 {
            let at = model.remoteMedia[device.id]?.at ?? Date()
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let moved = player.playing ? UInt64(max(0, context.date.timeIntervalSince(at)) * 1000) : 0
                let fraction = min(1, Double(start + moved) / Double(duration))
                ProgressCapsule(fraction: fraction)
                    .frame(height: 6)
                    .contentShape(Rectangle())
                    .overlay {
                        GeometryReader { proxy in
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture(coordinateSpace: .local) { point in
                                    guard player.canSeek, proxy.size.width > 0 else { return }
                                    let target = UInt64(Double(duration) * min(1, max(0, point.x / proxy.size.width)))
                                    model.sendMedia(.seek, player: player, to: device.id, positionMs: target)
                                }
                        }
                    }
            }
        }
    }
}

struct PlayerButtons: View {
    enum Size { case regular, small }

    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer
    var size: Size

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 6) {
                button("backward.fill", enabled: player.canPrev, help: "Previous") { model.sendMedia(.previous, player: player, to: device.id) }
                button(player.playing ? "pause.fill" : "play.fill", enabled: true, help: player.playing ? "Pause" : "Play") {
                    model.sendMedia(.toggle, player: player, to: device.id)
                }
                button("forward.fill", enabled: player.canNext, help: "Next") { model.sendMedia(.next, player: player, to: device.id) }
            }
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(size == .regular ? .large : .regular)
    }

    private func button(_ symbol: String, enabled: Bool, help: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: size == .regular ? 16 : 13, height: size == .regular ? 16 : 13)
        }
        .hoverGrey()
        .hoverSwell(1.1)
        .disabled(!enabled)
        .help(help)
    }
}

/// The first thing that plays on any phone, as a line in the small menu: what it is and the buttons.
struct MenuNowPlaying: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        if let (device, player) = current {
            HStack(spacing: 10) {
                PlayerCover(player: player, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(player.title).font(.callout.weight(.semibold)).lineLimit(1)
                    Text([player.artist, player.app].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                PlayerButtons(device: device, player: player, size: .small)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 18, style: .continuous))
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    /// Something that plays comes before something that is paused.
    private var current: (TandemDevice, TandemMediaPlayer)? {
        let all = model.devices
            .filter { $0.online || $0.ble }
            .flatMap { device in model.visibleMedia(for: device.id).map { (device, $0) } }
        return all.first { $0.1.playing } ?? all.first
    }
}
