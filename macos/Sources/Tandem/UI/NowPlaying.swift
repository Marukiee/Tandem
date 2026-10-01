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
/// The bar can be dragged: it follows the pointer, shows where it would land, and jumps there when let
/// go, and holds that place until the phone reports the new position. A click is a drag of nothing.
struct PlayerProgress: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer
    var compact = false

    /// Where the pointer is while it is down, as a fraction of the bar.
    @LocalState private var scrub: Double?
    /// Where it was let go, until the phone says where the music really is.
    @LocalState private var held: (fraction: Double, since: Date, report: Date)?
    @LocalState private var hovering = false

    var body: some View {
        if let start = player.positionMs, let duration = player.durationMs, duration > 0 {
            let report = model.remoteMedia[device.id]?.at ?? Date()
            TimelineView(.periodic(from: .now, by: 0.5)) { context in
                let fraction = shown(start: start, duration: duration, report: report, now: context.date)
                VStack(spacing: 3) {
                    bar(fraction: fraction, duration: duration, report: report)
                    if !compact {
                        HStack {
                            Text(Self.time(UInt64(fraction * Double(duration))))
                            Spacer()
                            Text(Self.time(duration))
                        }
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func shown(start: UInt64, duration: UInt64, report: Date, now: Date) -> Double {
        if let scrub { return scrub }
        // A jump is held for a moment, or until the phone reports again, so the bar does not spring back.
        if let held, held.report == report, now.timeIntervalSince(held.since) < 2.5 { return held.fraction }
        let moved = player.playing ? UInt64(max(0, now.timeIntervalSince(report)) * 1000) : 0
        return min(1, Double(start + moved) / Double(duration))
    }

    private func bar(fraction: Double, duration: UInt64, report: Date) -> some View {
        let active = player.canSeek && (scrub != nil || hovering)
        let thickness: CGFloat = active ? 10 : (compact ? 4 : 6)
        return GeometryReader { proxy in
            let width = max(proxy.size.width, 1)
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.1))
                Capsule()
                    .fill(Palette.indigo.gradient)
                    .frame(width: max(thickness, width * fraction))
                if active {
                    Circle()
                        .fill(.white)
                        .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                        .frame(width: 16, height: 16)
                        .offset(x: min(max(0, width * fraction - 8), width - 16))
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(height: thickness)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard player.canSeek else { return }
                        scrub = min(1, max(0, value.location.x / width))
                    }
                    .onEnded { value in
                        guard player.canSeek else { return }
                        let target = min(1, max(0, value.location.x / width))
                        scrub = nil
                        held = (target, Date(), report)
                        model.sendMedia(.seek, player: player, to: device.id, positionMs: UInt64(target * Double(duration)))
                    }
            )
            .onHover { hovering = $0 }
            .animation(.tandemSpringy, value: active)
        }
        .frame(height: 16)
    }

    static func time(_ ms: UInt64) -> String {
        let total = Int(ms / 1000)
        let (h, m, s) = (total / 3600, (total / 60) % 60, total % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
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
                    PlayerProgress(device: device, player: player, compact: true)
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
