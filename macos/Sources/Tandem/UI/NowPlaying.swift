import SwiftUI
import TandemCore

/// What a device plays, with the buttons to control it, on a card of its own beside the head of its page, or under it where the
/// page is narrow. Also when this Mac shows the same track (Spotify on a computer shows the track of the account wherever it
/// plays). The card is the plain card of the page, and what is in it is one row: the cover, what plays, and the three controls.
/// With more than one player the apps are named at its top left, above the cover, small, and the one in front is in the accent.
struct PlayersCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    /// The player the person picked, until it goes away.
    @LocalState private var chosen: String?

    var body: some View {
        let players = model.reach(of: device).reachable ? model.visibleMedia(for: device.id) : []
        // The one that was picked, otherwise what plays, otherwise the first.
        let current = players.first { $0.id == chosen } ?? players.first { $0.playing } ?? players.first
        // The model changes outside any animation, so the card brings its own: it fades in when something starts to play and out
        // when it stops. The page makes room for it on the same change (DeviceDetail).
        Group {
            if let current {
                Card(radius: Metrics.card, padding: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        if players.count > 1 {
                            PlayerApps(players: players, current: current.id) { id in withAnimation(.tandem) { chosen = id } }
                                .transition(.opacity)
                        }
                        // Stacked, so the player that leaves and the one that comes cross-fade in the same place.
                        ZStack(alignment: .top) {
                            PagePlayer(device: device, player: current, namesApp: players.count == 1)
                                .id(current.id)
                                .transition(.opacity)
                        }
                    }
                    // Beside the head the card is as tall as the head, with the player in the middle.
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .transition(.opacity)
            }
        }
        .animation(.tandem, value: players.map(\.id))
        .animation(.tandem, value: current?.id)
    }
}

/// Which app's player is shown: the names as words, the one in front in the accent, with a speaker beside the one that plays.
/// Small on purpose: what plays is the content of the card, and the choice of app should not outweigh it.
private struct PlayerApps: View {
    let players: [TandemMediaPlayer]
    let current: String
    let pick: (String) -> Void

    var body: some View {
        HStack(spacing: 14) {
            ForEach(players, id: \.id) { player in
                PlayerAppName(player: player, on: player.id == current) { pick(player.id) }
            }
        }
    }
}

private struct PlayerAppName: View {
    let player: TandemMediaPlayer
    let on: Bool
    let action: () -> Void
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if player.playing {
                    Image(systemName: "speaker.wave.2.fill")
                        .font(.system(size: 9, weight: .semibold))
                        .transition(.scale.combined(with: .opacity))
                }
                Text(player.app.isEmpty ? player.title : player.app)
                    .font(.caption.weight(on ? .semibold : .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(on ? Palette.indigo : (hovering && hoverEnabled ? Color.primary : Color.secondary))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .animation(.hover(hovering), value: hovering)
        .animation(.tandem, value: player.playing)
        .animation(.tandemFade, value: on)
        .accessibilityLabel(Text(player.app.isEmpty ? player.title : player.app))
        .accessibilityValue(player.playing ? Text("Playing now") : Text(""))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// One player on the page of a device: the cover, what plays and the controls on one row, and the bar under them.
private struct PagePlayer: View {
    let device: TandemDevice
    let player: TandemMediaPlayer
    /// With more than one player the apps are named above it, and the line under the title is the artist alone.
    var namesApp = true

    private var subtitle: String {
        let parts = [player.artist, namesApp ? player.app : ""].filter { !$0.isEmpty }
        return parts.isEmpty ? player.app : parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                PlayerCover(player: player, size: 56)
                PlayerTitle(title: player.title, subtitle: subtitle, titleFont: .headline, subtitleFont: .callout, spacing: 2)
                Spacer(minLength: 4)
                PlayerButtons(device: device, player: player, size: .page)
            }
            PlayerProgress(device: device, player: player)
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
    var titleLines = 1

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            Text(title).font(titleFont).lineLimit(titleLines)
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
            // Without a cover: a tile in the tone of the app with a note on it, not a grey hole.
            RoundedRectangle(cornerRadius: side * 0.22, style: .continuous).fill(Tone.indigo.fill)
            if let image = (player.art == 0 ? nil : model.mediaArt[player.art] ?? held) {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: side * 0.4, weight: .medium)).foregroundStyle(Tone.indigo.ink)
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
                        .font(.caption.monospacedDigit())
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
    /// `page` is the player on the page of a device: play or pause is the one filled circle, the other two are their glyph alone.
    enum Size { case regular, small, page }

    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let player: TandemMediaPlayer
    var size: Size
    /// Shows the name of a button the moment the pointer is on it. Not in the menu bar panel, which draws no names.
    var names = true

    var body: some View {
        HStack(spacing: size == .page ? 2 : 6) {
            button("backward.fill", enabled: player.canPrev, help: "Previous") { model.sendMedia(.previous, player: player, to: device.id) }
            button(player.playing ? "pause.fill" : "play.fill", enabled: true, main: true, help: player.playing ? "Pause" : "Play") {
                model.sendMedia(.toggle, player: player, to: device.id)
            }
            button("forward.fill", enabled: player.canNext, help: "Next") { model.sendMedia(.next, player: player, to: device.id) }
        }
    }

    private func button(_ symbol: String, enabled: Bool, main: Bool = false, help: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Group {
            if size == .page {
                PlayerControl(symbol: symbol, main: main, action: action)
            } else {
                RoundIconButton(symbol: symbol, size: size == .regular ? 38 : 30, action: action)
            }
        }
        .disabled(!enabled)
        .modifier(ButtonName(name: help, instant: names))
    }
}

/// A control of the player on the page of a device. Three grey discs read as three buttons that are switched off, so only play
/// or pause has a circle, filled in the accent, and the other two are their glyph with a faint disc under the pointer.
private struct PlayerControl: View {
    let symbol: String
    let main: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let lit = hovering && hoverEnabled && isEnabled
        let side: CGFloat = main ? 38 : 32
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: main ? 15 : 14, weight: .semibold))
                .foregroundStyle(main ? Color.white : Color.primary)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: side, height: side)
                .background {
                    if main {
                        Circle().fill(Palette.indigo).brightness(lit ? 0.07 : 0)
                    } else {
                        Circle().fill(Color.primary.opacity(lit ? 0.09 : 0))
                    }
                }
                .contentShape(Circle())
                .opacity(isEnabled ? 1 : 0.35)
        }
        .buttonStyle(.bouncy)
        .onHover { hovering = $0 }
        .animation(.hover(hovering), value: hovering)
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
                        PlayerButtons(device: device, player: player, size: .small, names: false)
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

/// The name of a round button: at once under it, or as the tooltip of the system where there is no room for that.
private struct ButtonName: ViewModifier {
    let name: LocalizedStringKey
    let instant: Bool

    func body(content: Content) -> some View {
        if instant { content.instantName(name) } else { content.help(name) }
    }
}
