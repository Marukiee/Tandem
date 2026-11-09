import SwiftUI
import TandemCore

/// The parts of Health with a page of their own, and the settings behind the gear.
enum HealthPart: Hashable {
    case sleep, activity, heart, settings
}

/// A circle with soft waves around its edge, after the shapes of Material You: a few deep lobes make a clover, many shallow ones a
/// cookie. The same shapes as on the phone, so Health looks like one thing on every device.
struct WavyCircle: Shape {
    var lobes: Int
    var depth: CGFloat

    func path(in rect: CGRect) -> Path {
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        let outer = min(rect.width, rect.height) / 2
        var path = Path()
        for step in 0...240 {
            let angle = CGFloat(step) / 240 * 2 * .pi - .pi / 2
            let radius = outer * (1 - depth / 2 + depth / 2 * cos(CGFloat(lobes) * angle))
            let point = CGPoint(x: centre.x + radius * cos(angle), y: centre.y + radius * sin(angle))
            if step == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}

/// Where Health opens: last night large with its score on a cookie, then a card per part with what matters most of it, each opening the
/// whole of that part. Only the parts with something in them: a card that says nothing makes the page look broken.
struct HealthOverview: View {
    let insights: TandemHealthInsights
    let records: [TandemHealthRecord]
    let goal: Int?
    let onOpen: (HealthPart) -> Void

    private var night: TandemHealthRecord? { records.first { $0.kind == .sleep } }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let night { NightHero(night: night, insight: insights.latestNight?.id == night.id ? insights.latestNight : nil) { onOpen(.sleep) } }
            sleepCard
            activityCard
            heartCard
        }
    }

    @ViewBuilder private var sleepCard: some View {
        let week = insights.week
        if let minutes = insights.thisWeek.meanSleepMinutes ?? night?.sleepMinutes {
            PartCard(
                symbol: "moon.fill", title: "Sleep", value: minutesText(Int(minutes)),
                caption: insights.thisWeek.meanSleepMinutes != nil ? String(localized: "A night on average") : String(localized: "Last night"),
                tint: Palette.indigo, action: { onOpen(.sleep) }
            ) {
                if !week.isEmpty {
                    WeekBars(values: week.map { $0.sleepMinutes.map { Double($0) } }, days: week, picked: .constant(nil), height: 44)
                }
            }
        }
    }

    @ViewBuilder private var activityCard: some View {
        let week = insights.week
        // A computer sees whole days: only the phone hears the watch during the day.
        if let day = week.last(where: { $0.steps != nil }), let steps = day.steps {
            PartCard(
                symbol: "figure.walk", title: "Activity", value: Int(steps).formatted(),
                caption: goal.map { String(localized: "of \($0.formatted()) steps") } ?? dayName(day.startMs),
                tint: Palette.rose, action: { onOpen(.activity) }
            ) {
                if let goal, goal > 0 {
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.primary.opacity(0.1))
                            Capsule().fill(Palette.rose).frame(width: max(12, geometry.size.width * min(1, CGFloat(steps) / CGFloat(goal))))
                        }
                    }
                    .frame(height: 12)
                }
            }
        }
    }

    @ViewBuilder private var heartCard: some View {
        let curve = insights.latestDayHeart?.curve ?? []
        if let resting = insights.resting {
            PartCard(
                symbol: "heart.fill", title: "Heart", value: String(localized: "\(Int(resting.latest)) bpm"),
                caption: resting.usual.map { String(localized: "Usually \(Int($0))") } ?? String(localized: "\(Int(resting.nightsNeeded)) more nights to show this"),
                tint: nil, action: { onOpen(.heart) }
            ) {
                if curve.filter({ $0 > 0 }).count > 1 { Sparkline(curve: curve).frame(height: 44) }
            }
        } else if let last = curve.last(where: { $0 > 0 }) {
            PartCard(symbol: "heart.fill", title: "Heart", value: String(localized: "\(Int(last)) bpm"), caption: String(localized: "Heart rate"), tint: nil, action: { onOpen(.heart) }) {
                Sparkline(curve: curve).frame(height: 44)
            }
        }
    }
}

/// Last night, large, on a tonal card: how long, the score on a cookie that rolls in once, and the few numbers of the night as pills. A
/// clover leans out of the corner, the playful side of Material You, quiet enough to leave the numbers in front.
private struct NightHero: View {
    let night: TandemHealthRecord
    let insight: TandemNightInsight?
    let action: () -> Void
    @State private var spin: Double = -50

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Image(systemName: "moon.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Palette.indigo)
                            .frame(width: 28, height: 28)
                            .background(Palette.indigo.opacity(0.18), in: .circle)
                        Text("Last night").font(.callout.weight(.semibold))
                    }
                    BigNumber(text: minutesText(Int(night.sleepMinutes)))
                    Text(Date(timeIntervalSince1970: Double(night.endMs) / 1000).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        NightPill(label: "Asleep", value: "\(night.efficiencyPercent)%")
                        NightPill(label: "Awake", value: minutesText(Int(night.awakeMinutes)))
                        if let low = night.heartRateLow { NightPill(label: "Lowest", value: String(localized: "\(Int(low)) bpm")) }
                    }
                    .padding(.top, 6)
                }
                Spacer(minLength: 8)
                if let insight {
                    VStack(spacing: 4) {
                        ZStack {
                            WavyCircle(lobes: 9, depth: 0.09)
                                .fill(Color(nsColor: .windowBackgroundColor).opacity(0.6))
                                .rotationEffect(.degrees(spin))
                            ScoreRing(score: insight.score)
                        }
                        .frame(width: 108, height: 108)
                        Text("Sleep score").font(.caption)
                    }
                }
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(alignment: .bottomTrailing) {
                WavyCircle(lobes: 4, depth: 0.32)
                    .fill(Color.primary.opacity(0.05))
                    .frame(width: 150, height: 150)
                    .rotationEffect(.degrees(45))
                    .offset(x: 34, y: 40)
            }
            .background(Palette.indigo.opacity(0.16), in: RoundedRectangle(cornerRadius: 30, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        }
        .buttonStyle(.plain)
        .onAppear { withAnimation(.spring(response: 1.2, dampingFraction: 0.55)) { spin = 0 } }
    }
}

private struct NightPill: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(spacing: 6) {
            Text(label).foregroundStyle(.secondary)
            Text(verbatim: value).fontWeight(.bold)
        }
        .font(.caption)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.6), in: .capsule)
    }
}

/// A card of a part of Health: its icon in a round, its name, the number large, a short word, and a little picture beside it.
private struct PartCard<Picture: View>: View {
    let symbol: String
    let title: LocalizedStringKey
    let value: String
    let caption: String
    let tint: Color?
    let action: () -> Void
    @ViewBuilder var picture: Picture

    var body: some View {
        Button(action: action) {
            Card(radius: Metrics.card, padding: 18, tint: tint) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        Image(systemName: symbol)
                            .font(.callout.weight(.semibold))
                            .frame(width: 34, height: 34)
                            .background(Color.primary.opacity(0.08), in: .circle)
                        Text(title).font(.headline)
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    }
                    HStack(alignment: .bottom, spacing: 16) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: value).font(.system(size: 26, weight: .bold)).lineLimit(1)
                            Text(verbatim: caption).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer(minLength: 8)
                        picture.frame(width: 196)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(RoundedRectangle(cornerRadius: Metrics.card, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// The heart rate of a day as a small line, with gaps where the watch was off the wrist.
private struct Sparkline: View {
    let curve: [UInt32]

    var body: some View {
        Canvas { context, size in
            let measured = curve.filter { $0 > 0 }.map { CGFloat($0) }
            guard measured.count > 1, curve.count > 1 else { return }
            let low = (measured.min() ?? 0) - 2
            let high = (measured.max() ?? 1) + 2
            var line = Path()
            var before = -100
            for (index, value) in curve.enumerated() where value > 0 {
                let x = CGFloat(index) / CGFloat(curve.count - 1) * size.width
                let y = (1 - (CGFloat(value) - low) / (high - low)) * size.height
                if index - before > 6 { line.move(to: CGPoint(x: x, y: y)) } else { line.addLine(to: CGPoint(x: x, y: y)) }
                before = index
            }
            context.stroke(line, with: .color(Palette.indigo), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
    }
}
