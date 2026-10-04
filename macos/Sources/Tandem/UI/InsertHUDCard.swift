import AppKit
import SwiftUI
import TandemCore

/// The card itself. Its glass is one shape that grows and shrinks between states; what is inside
/// swaps with a short fade, so the eye follows the shape and not the contents.
///
/// The radii nest. The card is 40 points round; what sits inside it at 14 points from the edge is
/// 26 round, and what sits at 16 is a circle of 24, so every curve inside runs parallel to the one
/// around it. Everything that can be pressed and is not a tile is fully round.
struct InsertHUDCard: View {
    let model: InsertFromPhone
    let onSize: (CGSize) -> Void

    static let width: CGFloat = 400
    static let radius: CGFloat = 40

    var body: some View {
        let phase = model.phase
        content(for: phase)
            .frame(width: Self.width)
            .hudSurface(radius: Self.radius)
            .shadow(color: .black.opacity(0.18), radius: 26, y: 12)
            .animation(.spring(response: 0.5, dampingFraction: 0.82), value: phase.cardKey)
            .onGeometryChange(for: CGSize.self) { $0.size } action: { onSize($0) }
    }

    @ViewBuilder
    private func content(for phase: InsertFromPhone.Phase) -> some View {
        Group {
            switch phase {
            case .idle:
                Color.clear.frame(height: 1)
            case let .choosing(kind, device):
                if let kind, device == nil {
                    WhichPhone(kind: kind, model: model)
                } else {
                    ChooseKind(device: device, model: model)
                }
            case let .waiting(session):
                Waiting(session: session, model: model)
            case let .receiving(session, name, done, total):
                Receiving(session: session, name: name, done: done, total: total)
            case let .inserted(_, pasted, app):
                Inserted(pasted: pasted, app: app, model: model)
            case let .failed(failure, _, _):
                Failed(failure: failure, model: model)
            }
        }
        .id(phase.cardKey)
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}

private extension InsertFromPhone.Phase {
    /// A change of this value is a change of what the card is about, and the contents swap.
    var cardKey: String {
        switch self {
        case .idle: "idle"
        case let .choosing(kind, device): "choosing-\(kind?.rawValue ?? "any")-\(device == nil ? "nodevice" : "device")"
        case .waiting: "waiting"
        case .receiving: "receiving"
        case let .inserted(_, pasted, _): pasted ? "inserted" : "ready"
        case .failed: "failed"
        }
    }
}

// MARK: Hover

/// What a control does when the pointer (or the keyboard) is on it: it tells the window where it is,
/// and while it is lit it rises and swells on a spring.
private struct HUDLit: ViewModifier {
    let id: Int
    var scale: CGFloat = 1.05
    var lift: CGFloat = 0
    @Environment(HUDInteraction.self) private var interaction
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let lit = interaction.highlighted == id
        content
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HUDSpace.name)) } action: { interaction.frames[id] = $0 }
            .scaleEffect(lit && !reduceMotion ? scale : 1)
            .offset(y: lit && !reduceMotion ? -lift : 0)
            .animation(.spring(response: 0.38, dampingFraction: 0.62), value: lit)
    }
}

private extension View {
    func hudLit(_ id: Int, scale: CGFloat = 1.05, lift: CGFloat = 0) -> some View {
        modifier(HUDLit(id: id, scale: scale, lift: lift))
    }
}

/// Shrinks a little under the finger and springs back.
private struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
    }
}

// MARK: Pieces

/// The round mark that leads a row: a ring that counts down or fills while something is going on,
/// a disc inside it, and the symbol in the disc at about half its width.
private struct StatusDisc<Content: View>: View {
    var tint: Color = Palette.indigo
    /// How much of the ring is drawn, or nil for no ring.
    var progress: Double?
    var pulses = false
    var size: CGFloat = 48
    @ViewBuilder var content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let ring: CGFloat = 3
        ZStack {
            if pulses && !reduceMotion {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                    ZStack {
                        ForEach(0..<2, id: \.self) { index in
                            let raw = (t / 2.6 + Double(index) * 0.5).truncatingRemainder(dividingBy: 1)
                            let eased = 1 - pow(1 - raw, 2.4)
                            Circle()
                                .stroke(tint.opacity((1 - eased) * 0.45), lineWidth: 1.5)
                                .scaleEffect(1 + eased * 0.32)
                        }
                    }
                }
            }
            if let progress {
                Circle().stroke(tint.opacity(0.16), lineWidth: ring).padding(ring / 2)
                Circle()
                    .trim(from: 0, to: max(0.004, progress))
                    .stroke(tint, style: StrokeStyle(lineWidth: ring, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(ring / 2)
            }
            Circle().fill(tint.opacity(0.16)).padding(progress == nil ? 0 : ring + 2.5)
            content
        }
        .frame(width: size, height: size)
    }
}

private extension StatusDisc where Content == AnyView {
    init(symbol: String, tint: Color = Palette.indigo, progress: Double? = nil, pulses: Bool = false) {
        self.init(tint: tint, progress: progress, pulses: pulses) {
            AnyView(
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(tint)
            )
        }
    }
}

/// Texts that lead a card: a title and a line under it.
private struct Heading: View {
    let title: String
    let detail: String?
    var detailLines = 2

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: title)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if let detail {
                Text(verbatim: detail)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .lineLimit(detailLines)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A key on the keyboard, small and round or wide enough for what is on it.
private struct Keycap: View {
    let text: String
    var lit = false
    var round = true

    var body: some View {
        Text(verbatim: text)
            .font(.system(size: 11, weight: .bold, design: .rounded))
            .foregroundStyle(lit ? Color.white : Color.secondary)
            .frame(minWidth: 22, minHeight: 22)
            .padding(.horizontal, round ? 0 : 4)
            .background(Capsule().fill(lit ? Palette.indigo : Color.primary.opacity(0.09)))
            .animation(.tandemFade, value: lit)
    }
}

/// A round pill of text with a symbol, for the name of a phone.
private struct DeviceChip: View {
    let name: String
    var menu = false

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "iphone").font(.system(size: 11, weight: .semibold))
            Text(verbatim: name).font(.system(size: 12, weight: .medium)).lineLimit(1)
            if menu {
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.7)
            }
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 11)
        .frame(height: 28)
        .background(Capsule().fill(Color.primary.opacity(0.08)))
    }
}

// MARK: Choosing

private struct ChooseKind: View {
    let device: String?
    let model: InsertFromPhone

    var body: some View {
        let phones = model.phones
        let current = phones.first { $0.id == device } ?? phones.first
        let name = current?.name ?? InsertHUDText.debugPhone
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                Text("Insert from phone")
                    .font(.system(size: 15, weight: .semibold))
                Spacer(minLength: 8)
                if phones.count > 1 {
                    Menu {
                        ForEach(phones, id: \.id) { phone in
                            Button {
                                model.begin(kind: nil, device: phone.id)
                            } label: {
                                Label(phone.name, systemImage: phone.id == current?.id ? "checkmark" : "iphone")
                            }
                        }
                    } label: {
                        DeviceChip(name: name, menu: true)
                    }
                    .menuStyle(.button)
                    .menuIndicator(.hidden)
                    .buttonStyle(.plain)
                    .fixedSize()
                } else {
                    DeviceChip(name: name)
                }
            }
            .padding(.leading, 10)
            .padding(.trailing, 2)

            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    ForEach(Array(InsertKind.allCases.enumerated()), id: \.element.id) { index, kind in
                        KindTile(kind: kind, index: index) {
                            model.choose(kind: kind, device: current?.id ?? device ?? "")
                        }
                    }
                }
            }
        }
        .padding(14)
    }
}

/// One of the three things to ask for. The symbol sits in the corner where the eye starts, the name at
/// the opposite one, and under the pointer the tile rises, the symbol swells and fills.
private struct KindTile: View {
    let kind: InsertKind
    let index: Int
    let action: () -> Void
    @Environment(HUDInteraction.self) private var interaction
    @LocalState private var arrived = false

    static let radius: CGFloat = InsertHUDCard.radius - 14

    var body: some View {
        let lit = interaction.highlighted == index
        let shape = RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    ZStack {
                        Circle().fill(lit ? Palette.indigo : Palette.indigo.opacity(0.15))
                        Image(systemName: kind.symbol)
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(lit ? Color.white : Palette.indigo)
                    }
                    .frame(width: 40, height: 40)
                    .scaleEffect(lit ? 1.14 : 1, anchor: .topLeading)
                    .animation(.spring(response: 0.36, dampingFraction: 0.55), value: lit)
                    Spacer(minLength: 0)
                    Keycap(text: "\(index + 1)", lit: lit)
                }
                Spacer(minLength: 6)
                Text(verbatim: kind.title)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .frame(height: 114)
            .hudTile(shape, lit: lit)
            .contentShape(shape)
        }
        .buttonStyle(PressStyle())
        .hudLit(index, scale: 1.035, lift: 3)
        .opacity(arrived ? 1 : 0)
        .scaleEffect(arrived ? 1 : 0.86)
        .task {
            try? await Task.sleep(for: .milliseconds(70 + index * 55))
            withAnimation(.spring(response: 0.46, dampingFraction: 0.66)) { arrived = true }
        }
        .accessibilityLabel(Text(verbatim: kind.title))
    }
}

/// Several phones and a kind already picked: which one.
private struct WhichPhone: View {
    let kind: InsertKind
    let model: InsertFromPhone
    @Environment(HUDInteraction.self) private var interaction

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Text("Which phone?")
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
                Button { model.cancel() } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).frame(width: 28, height: 28)
                }
                .hudButton()
                .buttonBorderShape(.circle)
                .hudLit(10)
            }
            .padding(.leading, 10)
            GlassEffectContainer(spacing: 8) {
                VStack(spacing: 8) {
                    ForEach(Array(model.phones.enumerated()), id: \.element.id) { index, phone in
                        let id = 11 + index
                        let lit = interaction.highlighted == id
                        let shape = RoundedRectangle(cornerRadius: InsertHUDCard.radius - 14, style: .continuous)
                        Button {
                            model.choose(kind: kind, device: phone.id)
                        } label: {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle().fill(lit ? Palette.indigo : Palette.indigo.opacity(0.15))
                                    Image(systemName: kind.symbol)
                                        .font(.system(size: 17, weight: .semibold))
                                        .foregroundStyle(lit ? Color.white : Palette.indigo)
                                }
                                .frame(width: 36, height: 36)
                                Text(verbatim: phone.name).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                                Spacer()
                            }
                            .padding(10)
                            .hudTile(shape, lit: lit)
                            .contentShape(shape)
                        }
                        .buttonStyle(PressStyle())
                        .hudLit(id, scale: 1.02)
                    }
                }
            }
        }
        .padding(14)
    }
}

// MARK: Waiting and receiving

private struct Waiting: View {
    let session: InsertFromPhone.Session
    let model: InsertFromPhone
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 14) {
            // The ring runs down over the two minutes the phone gets.
            TimelineView(.animation(minimumInterval: reduceMotion ? 1 : 1.0 / 20.0)) { context in
                let left = max(0, 1 - context.date.timeIntervalSince(session.started) / InsertFromPhone.timeout)
                StatusDisc(symbol: "iphone", progress: left, pulses: true)
            }
            Heading(title: String(localized: "Waiting for your phone"), detail: "\(session.deviceName) \u{00B7} \(session.kind.instruction)", detailLines: 1)
            Button { model.cancel() } label: {
                Text("Cancel").font(.system(size: 14, weight: .semibold)).padding(.horizontal, 6)
            }
            .hudButton()
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .hudLit(10)
        }
        .padding(16)
    }
}

private struct Receiving: View {
    let session: InsertFromPhone.Session
    let name: String
    let done: UInt64
    let total: UInt64

    var body: some View {
        let fraction = total == 0 ? 0 : min(1, Double(done) / Double(total))
        HStack(spacing: 14) {
            StatusDisc(progress: fraction) {
                PillSpinner(size: 22)
            }
            Heading(
                title: session.kind.receiving,
                detail: total > 0 ? String(localized: "\(formatBytes(done)) of \(formatBytes(total))") : name,
                detailLines: 1
            )
        }
        .padding(16)
        .animation(.tandem, value: fraction)
    }
}

// MARK: Results

private struct Inserted: View {
    let pasted: Bool
    let app: String?
    let model: InsertFromPhone
    @LocalState private var bounced = false

    var body: some View {
        HStack(spacing: 14) {
            StatusDisc(tint: pasted ? .green : Palette.indigo) {
                Image(systemName: pasted ? "checkmark" : "doc.on.clipboard")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(pasted ? Color.green : Palette.indigo)
                    .symbolEffect(.bounce, value: bounced)
            }
            if pasted {
                Heading(
                    title: String(localized: "Inserted"),
                    detail: app.map { String(localized: "Pasted into \($0)") },
                    detailLines: 1
                )
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Ready to paste").font(.system(size: 15, weight: .semibold))
                    HStack(spacing: 5) {
                        Text("Press").font(.system(size: 13)).foregroundStyle(.secondary)
                        Keycap(text: "\u{2318}")
                        Keycap(text: "V")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button { model.askForPastePermission() } label: {
                    Text("Allow pasting").font(.system(size: 14, weight: .semibold)).padding(.horizontal, 6)
                }
                .hudButton()
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .hudLit(10)
            }
        }
        .padding(16)
        .onAppear { bounced = true }
    }
}

private struct Failed: View {
    let failure: InsertFromPhone.Failure
    let model: InsertFromPhone

    var body: some View {
        VStack(spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                StatusDisc(symbol: failure.isGentle ? "xmark" : "exclamationmark.triangle.fill", tint: failure.isGentle ? Palette.indigo : Palette.urgent)
                Heading(title: failure.title, detail: failure.detail, detailLines: 3)
                    .padding(.top, 4)
            }
            if !failure.isGentle {
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    Button { model.close() } label: {
                        Text("Close").font(.system(size: 14, weight: .semibold)).padding(.horizontal, 6)
                    }
                    .hudButton()
                    .buttonBorderShape(.capsule)
                    .hudLit(10)
                    Button { model.retry() } label: {
                        Text("Try again").font(.system(size: 14, weight: .semibold)).padding(.horizontal, 6)
                    }
                    .hudButton(prominent: true)
                    .buttonBorderShape(.capsule)
                    .hudLit(11)
                }
                .controlSize(.large)
            }
        }
        .padding(16)
    }
}

enum InsertHUDText {
    /// A phone name for the snapshot harness, where no phone exists.
    static let debugPhone = "Pixel 9"
    static let flat = ProcessInfo.processInfo.environment["TANDEM_DEBUG_DIR"] != nil
        && ProcessInfo.processInfo.environment["TANDEM_DEBUG_FLAT"] != nil
}

// MARK: Glass, or a flat stand-in for the snapshot harness

extension View {
    /// The card. With `TANDEM_DEBUG_FLAT` it is a plain fill, because glass cannot be drawn into the
    /// snapshots the harness makes and the layout would be invisible.
    @ViewBuilder
    fileprivate func hudSurface(radius: CGFloat) -> some View {
        if InsertHUDText.flat {
            background(Color(nsColor: .windowBackgroundColor), in: .rect(cornerRadius: radius, style: .continuous))
        } else {
            glassEffect(.regular, in: .rect(cornerRadius: radius, style: .continuous))
        }
    }

    /// A tile on the card: glass of its own, tinted indigo while it is lit.
    @ViewBuilder
    fileprivate func hudTile(_ shape: RoundedRectangle, lit: Bool) -> some View {
        if InsertHUDText.flat {
            background(lit ? Palette.indigo.opacity(0.22) : Color(nsColor: .quaternarySystemFill), in: shape)
        } else {
            glassEffect(lit ? Glass.regular.tint(Palette.indigo.opacity(0.20)) : Glass.regular, in: shape)
        }
    }

    @ViewBuilder
    fileprivate func hudButton(prominent: Bool = false) -> some View {
        if InsertHUDText.flat {
            buttonStyle(FlatGlassButtonStyle(prominent: prominent))
        } else if prominent {
            buttonStyle(.glassProminent).tint(Palette.indigo)
        } else {
            buttonStyle(.glass)
        }
    }
}

private struct FlatGlassButtonStyle: ButtonStyle {
    let prominent: Bool
    @Environment(\.controlSize) private var controlSize

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, controlSize == .small ? 8 : 12)
            .frame(minHeight: controlSize == .large ? 40 : 28)
            .foregroundStyle(prominent ? Color.white : Color.primary)
            .background(Capsule().fill(prominent ? Palette.indigo : Color(nsColor: .quaternarySystemFill)))
    }
}
