import AppKit
import SwiftUI
import TandemCore

/// The card itself. Its glass is one shape that grows and shrinks between states; what is inside
/// swaps with a short fade, so the eye follows the shape and not the contents.
struct InsertHUDCard: View {
    let model: InsertFromPhone
    let onSize: (CGSize) -> Void

    static let width: CGFloat = 392
    static let radius: CGFloat = 32
    static let inset: CGFloat = 14

    var body: some View {
        let phase = model.phase
        content(for: phase)
            .padding(Self.inset)
            .frame(width: Self.width)
            .glassEffect(.regular, in: .rect(cornerRadius: Self.radius, style: .continuous))
            .shadow(color: .black.opacity(0.16), radius: 24, y: 10)
            .animation(.tandem, value: phase.cardKey)
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
        .transition(.opacity.combined(with: .scale(scale: 0.97)))
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

// MARK: Pieces

/// A round tinted disc with something in it, the way the rows of the app lead.
private struct LeadingDisc<Content: View>: View {
    var tint: Color = Palette.indigo
    var size: CGFloat = 46
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Circle().fill(tint.opacity(0.16))
            content
        }
        .frame(width: size, height: size)
    }
}

/// The phone, with rings that leave it while the Mac waits. Driven by the clock so the system draws
/// it and the app does not redraw on every frame of a long animation.
private struct PhoneBeacon: View {
    var symbol: String
    var size: CGFloat = 46
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { context in
            let t = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
            ZStack {
                ForEach(0..<2, id: \.self) { index in
                    let raw = (t / 2.6 + Double(index) * 0.5).truncatingRemainder(dividingBy: 1)
                    let eased = 1 - pow(1 - raw, 2.4)
                    Circle()
                        .stroke(Palette.indigo.opacity((1 - eased) * 0.5), lineWidth: 1.5)
                        .scaleEffect(1 + eased * 0.55)
                }
                Circle().fill(Palette.indigo.opacity(0.16))
                Image(systemName: symbol)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(Palette.indigo)
            }
        }
        .frame(width: size, height: size)
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
                .font(.headline)
                .lineLimit(1)
            if let detail {
                Text(verbatim: detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(detailLines)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The time the phone has left, as a thin line that runs down.
private struct Countdown: View {
    let since: Date
    let total: TimeInterval

    var body: some View {
        TimelineView(.periodic(from: since, by: 0.5)) { context in
            let left = max(0, 1 - context.date.timeIntervalSince(since) / total)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    Capsule()
                        .fill(Palette.indigo.opacity(0.75))
                        .frame(width: max(4, proxy.size.width * left))
                        .animation(.tandem, value: left)
                }
            }
        }
        .frame(height: 4)
    }
}

private struct Keycap: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(.system(size: 11, weight: .semibold, design: .rounded))
            .foregroundStyle(.secondary)
            .frame(minWidth: 18, minHeight: 18)
            .padding(.horizontal, 3)
            .background(Color.primary.opacity(0.08), in: .rect(cornerRadius: 6, style: .continuous))
    }
}

// MARK: Choosing

private struct ChooseKind: View {
    let device: String?
    let model: InsertFromPhone

    var body: some View {
        let phones = model.phones
        let current = phones.first { $0.id == device } ?? phones.first
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                Text("Insert from phone")
                    .font(.headline)
                Spacer(minLength: 8)
                if phones.count > 1, let current {
                    Menu {
                        ForEach(phones, id: \.id) { phone in
                            Button {
                                model.begin(kind: nil, device: phone.id)
                            } label: {
                                Label(phone.name, systemImage: phone.id == current.id ? "checkmark" : "iphone")
                            }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Text(verbatim: current.name).lineLimit(1)
                            Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .bold))
                        }
                        .font(.subheadline.weight(.medium))
                    }
                    .menuStyle(.button)
                    .menuIndicator(.hidden)
                    .buttonStyle(.glass)
                    .controlSize(.small)
                    .fixedSize()
                } else if let current {
                    Text(verbatim: current.name)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text(verbatim: InsertHUDText.debugPhone).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 2)

            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    ForEach(Array(InsertKind.allCases.enumerated()), id: \.element.id) { index, kind in
                        KindTile(kind: kind, number: index + 1) {
                            model.choose(kind: kind, device: current?.id ?? device ?? "")
                        }
                    }
                }
            }
        }
    }
}

private struct KindTile: View {
    let kind: InsertKind
    let number: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: kind.symbol)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Palette.indigo)
                    .frame(height: 26)
                Text(verbatim: kind.title)
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .overlay(alignment: .topTrailing) {
                Keycap(text: "\(number)").scaleEffect(0.85).padding(.top, -2).padding(.trailing, -2)
            }
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.roundedRectangle(radius: Metrics.inner(InsertHUDCard.radius, inset: InsertHUDCard.inset)))
        .hoverSwell(1.03)
    }
}

/// Several phones and a kind already picked: which one.
private struct WhichPhone: View {
    let kind: InsertKind
    let model: InsertFromPhone

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: kind.symbol).foregroundStyle(Palette.indigo)
                Text("Which phone?").font(.headline)
                Spacer()
                Button { model.cancel() } label: { Image(systemName: "xmark").font(.system(size: 11, weight: .bold)) }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .controlSize(.small)
            }
            .padding(.horizontal, 6)
            GlassEffectContainer(spacing: 8) {
                VStack(spacing: 8) {
                    ForEach(model.phones, id: \.id) { phone in
                        Button {
                            model.choose(kind: kind, device: phone.id)
                        } label: {
                            HStack(spacing: 10) {
                                DeviceGlyph(platform: phone.platform, online: true, size: 30, deviceID: phone.id)
                                Text(verbatim: phone.name).font(.callout.weight(.medium)).lineLimit(1)
                                Spacer()
                            }
                            .padding(.vertical, 4)
                            .padding(.horizontal, 4)
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.roundedRectangle(radius: Metrics.inner(InsertHUDCard.radius, inset: InsertHUDCard.inset)))
                        .hoverSwell(1.02)
                    }
                }
            }
        }
        .environment(EngineModel.shared)
    }
}

// MARK: Waiting and receiving

private struct Waiting: View {
    let session: InsertFromPhone.Session
    let model: InsertFromPhone

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                PhoneBeacon(symbol: "iphone")
                Heading(title: String(localized: "Waiting for your phone"), detail: "\(session.deviceName) \u{00B7} \(session.kind.instruction)")
                Button("Cancel") { model.cancel() }
                    .buttonStyle(.glass)
                    .controlSize(.regular)
                    .hoverSwell(1.05)
            }
            Countdown(since: session.started, total: InsertFromPhone.timeout)
                .padding(.horizontal, 6)
        }
    }
}

private struct Receiving: View {
    let session: InsertFromPhone.Session
    let name: String
    let done: UInt64
    let total: UInt64

    var body: some View {
        let fraction = total == 0 ? 0 : min(1, Double(done) / Double(total))
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                LeadingDisc {
                    PillSpinner(size: 26)
                }
                Heading(
                    title: session.kind.receiving,
                    detail: total > 0 ? String(localized: "\(formatBytes(done)) of \(formatBytes(total))") : name
                )
            }
            ProgressCapsule(fraction: fraction)
                .frame(height: 5)
                .padding(.horizontal, 6)
        }
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
            LeadingDisc(tint: pasted ? .green : Palette.indigo) {
                Image(systemName: pasted ? "checkmark" : "doc.on.clipboard")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(pasted ? Color.green : Palette.indigo)
                    .symbolEffect(.bounce, value: bounced)
            }
            if pasted {
                Heading(
                    title: String(localized: "Inserted"),
                    detail: app.map { String(localized: "Pasted into \($0)") }
                )
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Ready to paste").font(.headline)
                    HStack(spacing: 4) {
                        Text("Press").font(.subheadline).foregroundStyle(.secondary)
                        Keycap(text: "\u{2318}")
                        Keycap(text: "V")
                        Text("where you want it.").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button("Allow pasting") { model.askForPastePermission() }
                    .buttonStyle(.glass)
                    .controlSize(.regular)
                    .hoverSwell(1.05)
            }
        }
        .onAppear { bounced = true }
    }
}

private struct Failed: View {
    let failure: InsertFromPhone.Failure
    let model: InsertFromPhone

    var body: some View {
        VStack(spacing: 12) {
            HStack(alignment: .top, spacing: 14) {
                LeadingDisc(tint: failure.isGentle ? Palette.indigo : Palette.urgent) {
                    Image(systemName: failure.isGentle ? "xmark" : "exclamationmark.triangle.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(failure.isGentle ? Palette.indigo : Palette.urgent)
                }
                Heading(title: failure.title, detail: failure.detail, detailLines: 3)
            }
            if !failure.isGentle {
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    Button("Close") { model.close() }
                        .buttonStyle(.glass)
                        .hoverSwell(1.05)
                    Button("Try again") { model.retry() }
                        .buttonStyle(.glassProminent)
                        .tint(Palette.indigo)
                        .hoverSwell(1.05)
                }
                .controlSize(.regular)
            }
        }
    }
}

enum InsertHUDText {
    /// A phone name for the snapshot harness, where no phone exists.
    static let debugPhone = "Pixel 9"
}
