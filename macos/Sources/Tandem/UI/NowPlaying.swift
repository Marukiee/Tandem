import SwiftUI
import TandemCore

/// What a phone is playing, with the buttons to control it. Players that play what this Mac already
/// plays are left out: a phone that only remote controls the Mac's Spotify is not a second player.
struct NowPlayingCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    /// The player the person picked from the small rows, until it goes away.
    @LocalState private var chosen: String?

    var body: some View {
        let players = model.reach(of: device).reachable ? model.visibleMedia(for: device.id) : []
        // One player gets the whole card: the one that was picked, otherwise what plays, otherwise the first. The
        // others are small rows in one card below it, so three players take little more room than one.
        let main = players.first { $0.id == chosen } ?? players.first { $0.playing } ?? players.first
        let others = players.filter { $0.id != main?.id }
        // The model changes outside any animation, so the card brings its own: it slides in when something starts
        // to play and out when it stops.
        Group {
            if let main {
                VStack(spacing: 10) {
                    PlayerCard(device: device, player: main)
                    if !others.isEmpty {
                        OtherPlayers(device: device, players: others) { picked in withAnimation(.tandem) { chosen = picked.id } }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(.tandem, value: players.map(\.id))
    }
}

/// The players that are not in front, one line each: a small cover, what plays, and the play button. A tap on the
/// line brings that player to the front.
private struct OtherPlayers: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let players: [TandemMediaPlayer]
    let pick: (TandemMediaPlayer) -> Void

    var body: some View {
        Card(radius: Metrics.card, padding: 8) {
            VStack(spacing: 0) {
                ForEach(players, id: \.id) { player in
                    HStack(spacing: 10) {
                        PlayerCover(player: player, size: 36)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(player.title.isEmpty ? player.app : player.title).font(.callout.weight(.medium)).lineLimit(1)
                            Text([player.artist, player.app].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Button {
                            model.sendMedia(.toggle, player: player, to: device.id)
                        } label: {
                            Image(systemName: player.playing ? "pause.fill" : "play.fill")
                                .contentTransition(.symbolEffect(.replace))
                                .frame(width: 12, height: 12)
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .controlSize(.regular)
                        .help(player.playing ? "Pause" : "Play")
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                    .onTapGesture { pick(player) }
                }
            }
        }
    }
}

private struct PlayerCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer

    private var subtitle: String { [player.artist, player.app].filter { !$0.isEmpty }.joined(separator: " · ") }

    var body: some View {
        Card(radius: Metrics.card, padding: 16, tint: player.art == 0 ? nil : model.mediaTint[player.art].map { Color(nsColor: $0) }) {
            CoverBeside(spacing: 14) {
                PlayerCover(player: player, size: nil)
                VStack(alignment: .leading, spacing: 8) {
                    // What plays on the left, the buttons at the top right, and the bar below both, the width of the card.
                    HStack(alignment: .top, spacing: 12) {
                        PlayerTitle(title: player.title, subtitle: subtitle, titleFont: .headline, subtitleFont: .callout, spacing: 2)
                        Spacer(minLength: 8)
                        PlayerButtons(device: device, player: player, size: .regular)
                    }
                    PlayerProgress(device: device, player: player)
                }
            }
        }
        .animation(.tandem, value: player.art)
    }
}

/// What plays. A new track slides its text in from below and the old one up and out, so a skip is seen.
private struct PlayerTitle: View {
    let title: String
    let subtitle: String
    let titleFont: Font
    let subtitleFont: Font
    let spacing: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            Text(title).font(titleFont).lineLimit(1)
            Text(subtitle).font(subtitleFont).foregroundStyle(.secondary).lineLimit(1)
        }
        .id(title + "\u{1F}" + subtitle)
        .transition(.asymmetric(
            insertion: .opacity.combined(with: .offset(y: 8)),
            removal: .opacity.combined(with: .offset(y: -8))
        ))
        .animation(.tandem, value: title + "\u{1F}" + subtitle)
        .clipped()
    }
}

/// A cover at the left and the text beside it. The cover is a square as tall as the text, so it starts where the
/// text starts and ends where it ends: the card has the same space above it as below. The text takes the rest of the width.
private struct CoverBeside: Layout {
    var spacing: CGFloat
    /// Never smaller than this, for a player that has no bar and so only a line or two of text.
    var minimum: CGFloat = 56

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let width = proposal.width ?? 420
        // The text hardly changes height with its width (every line is one line long), so it is measured with the
        // cover at its smallest, and then given what is left once the cover has its real size.
        let probe = max(width - spacing - minimum, 0)
        let height = max(minimum, subviews[1].sizeThatFits(ProposedViewSize(width: probe, height: nil)).height)
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let side = bounds.height
        subviews[0].place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: side, height: side))
        let rest = max(bounds.width - side - spacing, 0)
        subviews[1].place(
            at: CGPoint(x: bounds.minX + side + spacing, y: bounds.minY),
            anchor: .topLeading,
            proposal: ProposedViewSize(width: rest, height: side)
        )
    }
}

struct PlayerCover: View {
    @Environment(EngineModel.self) private var model
    let player: TandemMediaPlayer
    /// A side, or nil to fill the square it is given.
    var size: CGFloat?

    /// The last picture shown for this player, kept while the next one is on its way, so a new key never blinks the cover.
    @LocalState private var held: NSImage?

    var body: some View {
        if let size {
            cover(side: size).frame(width: size, height: size)
        } else {
            GeometryReader { proxy in cover(side: min(proxy.size.width, proxy.size.height)) }
        }
    }

    private func cover(side: CGFloat) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: side * 0.22, style: .continuous).fill(Color.primary.opacity(0.07))
            if let image = (player.art == 0 ? nil : model.mediaArt[player.art] ?? held) {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: side * 0.4)).foregroundStyle(.secondary)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: side * 0.22, style: .continuous))
        // Paused, it sinks back a little, so the state reads at a glance.
        .scaleEffect(player.playing ? 1 : 0.93)
        .animation(.tandemSpringy, value: player.playing)
        .animation(.tandemFade, value: player.art)
        .onAppear { held = player.art == 0 ? nil : model.mediaArt[player.art] }
        .onChange(of: model.mediaArt[player.art]) { _, image in if let image { held = image } }
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
            // Once a second, and not at all while it is paused: the time beside the bar only has whole seconds, and every
            // redraw makes the window lay itself out again.
            TimelineView(.animation(minimumInterval: 1, paused: !player.playing)) { context in
                let fraction = shown(start: start, duration: duration, report: report, now: context.date)
                VStack(spacing: 3) {
                    bar(fraction: fraction, duration: duration, report: report)
                    if !compact {
                        HStack {
                            // While the bar is dragged this is where the music would jump to.
                            Text(Self.time(UInt64(fraction * Double(duration))))
                                .foregroundStyle(scrub == nil ? Color.secondary : Color.primary)
                            Spacer()
                            Text(Self.time(duration)).foregroundStyle(.secondary)
                        }
                        .font(.caption2.monospacedDigit())
                        .animation(.tandemFade, value: scrub == nil)
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

    /// A thin bar that grows under the pointer. No knob: a knob that zooms in under the pointer is more than a
    /// bar this small can carry, and the time beside it already says where a drag would land.
    private func bar(fraction: Double, duration: UInt64, report: Date) -> some View {
        let active = player.canSeek && (scrub != nil || hovering)
        let thickness: CGFloat = active ? (compact ? 6 : 8) : (compact ? 3 : 4)
        return GeometryReader { proxy in
            let width: CGFloat = max(proxy.size.width, 1)
            let filled: CGFloat = width * CGFloat(fraction)
            // Where along the bar an x is, as 0 to 1. Said in one place so the two gestures agree, and with the
            // conversion spelled out: older compilers will not turn a CGFloat into a Double on their own here.
            let along: (CGFloat) -> Double = { x in Double(min(CGFloat(1), max(CGFloat(0), x / width))) }
            // Only the two capsules: anything else in here with a size of its own stretches both of them.
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(active ? 0.16 : 0.10))
                Capsule()
                    .fill(Palette.indigo.gradient)
                    .frame(width: max(thickness, filled))
            }
            .frame(height: thickness)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard player.canSeek else { return }
                        scrub = along(value.location.x)
                    }
                    .onEnded { value in
                        guard player.canSeek else { return }
                        let target: Double = along(value.location.x)
                        scrub = nil
                        held = (target, Date(), report)
                        model.sendMedia(.seek, player: player, to: device.id, positionMs: UInt64(target * Double(duration)))
                    }
            )
            .onHover { hovering = $0 }
            .animation(.tandem, value: active)
        }
        .frame(height: compact ? 12 : 16)
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
        // No glass container around them: glass this close together blends into one blob under the pointer, and
        // a swelling button then no longer has its icon in the middle of its glass.
        HStack(spacing: 6) {
            button("backward.fill", enabled: player.canPrev, help: "Previous") { model.sendMedia(.previous, player: player, to: device.id) }
            button(player.playing ? "pause.fill" : "play.fill", enabled: true, help: player.playing ? "Pause" : "Play") {
                model.sendMedia(.toggle, player: player, to: device.id)
            }
            button("forward.fill", enabled: player.canNext, help: "Next") { model.sendMedia(.next, player: player, to: device.id) }
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(size == .regular ? .large : .regular)
    }

    private func button(_ symbol: String, enabled: Bool, help: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: size == .regular ? 16 : 13, height: size == .regular ? 16 : 13)
        }
        .hoverGrey()
        .hoverSwell(1.1)
        .disabled(!enabled)
        .help(help)
    }
}

/// The first thing that plays on any phone, in the small menu. Laid out like the player in the app: the cover at the
/// left, what plays with the buttons at its top right, and a thin bar under both.
struct MenuNowPlaying: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        if let (device, player) = model.activePhonePlayer {
            let tint: Color? = player.art == 0 ? nil : model.mediaTint[player.art].map { Color(nsColor: $0) }
            CoverBeside(spacing: 10, minimum: 48) {
                PlayerCover(player: player, size: nil)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .top, spacing: 6) {
                        PlayerTitle(
                            title: player.title,
                            subtitle: [player.artist, player.app].filter { !$0.isEmpty }.joined(separator: " · "),
                            titleFont: .callout.weight(.semibold),
                            subtitleFont: .caption,
                            spacing: 1
                        )
                        Spacer(minLength: 4)
                        PlayerButtons(device: device, player: player, size: .small)
                    }
                    PlayerProgress(device: device, player: player, compact: true)
                }
            }
            .padding(10)
            .background {
                let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
                shape.fill(Color.primary.opacity(0.05)).overlay { shape.fill((tint ?? Color.clear).opacity(0.10)) }
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}
