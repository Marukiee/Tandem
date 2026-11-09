import SwiftUI
import TandemCore

/// The nights and workouts of the watch, by way of the phone. Which devices get them is a switch on the phone: only that one has a watch.
struct HealthSettings: View {
    var body: some View {
        ScrollView {
            HealthSettingsContent()
                .padding(20)
        }
    }
}

/// What the health page holds, in Settings and as a page of the main window that the page of the watch opens.
struct HealthSettingsContent: View {
    @Environment(EngineModel.self) private var model
    @AppStorage("showHealth") private var showHealth = false

    private var hasWatch: Bool { model.devices.contains { $0.status.watch?.paired == true } }
    private var records: [TandemHealthRecord] { model.healthRecords.filter { $0.kind == .sleep || $0.kind == .workout } }
    private var emptyText: String {
        let phone = model.devices.first { $0.status.watch?.paired == true }?.name ?? String(localized: "your phone")
        return String(localized: "Nothing here yet. Turn on this Mac on \(phone): Tandem, Health, Share with your computers.")
    }

    var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                // Without a watch this is one more page that stays empty, unless someone asked for it.
                if !hasWatch {
                    Card(radius: Metrics.card, padding: 16) {
                        VStack(alignment: .leading, spacing: 4) {
                            Toggle("Show without a watch", isOn: $showHealth)
                            Text("This shows by itself once one of your phones has a watch.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if hasWatch || showHealth {
                    if let insights = model.healthInsights {
                        HealthPageContent(insights: insights, records: model.healthRecords)
                    }
                    if records.isEmpty {
                        // Off by default on the phone, for privacy: say where to turn it on, so an empty page is not a dead end.
                        Text(emptyText)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 4)
                    }
                    HealthChoicesCard()
                    Card(radius: Metrics.card, padding: 16) {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Export")
                                Text("All your nights, workouts and steps as a table for a spreadsheet.").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                            Button("Export") { model.exportHealth() }
                        }
                    }
                    .padding(.top, 8)
                }
            }
    }
}

/// Health as a page of the main window, opened from the page of the watch, with a way back to it. It was only in Settings, so the
/// card on the page of the watch opened another window.
struct HealthPage: View {
    let phone: TandemDevice
    let onBack: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Card(radius: Metrics.card, padding: 18, tint: Palette.indigo) {
                    HStack(spacing: 14) {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left").font(.body.weight(.semibold))
                        }
                        .buttonStyle(.icon(size: 34))
                        .help("Back to the watch")
                        Image(systemName: "heart.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Palette.indigo)
                            .frame(width: 44, height: 44)
                            .background(Palette.indigo.opacity(0.14), in: .circle)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Health").font(.title3.weight(.bold))
                            Text("Sleep, steps, heart rate and workouts").font(.callout).foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }
                HealthSettingsContent()
            }
            .pagePadding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
    }
}

/// What is set for the watch's measuring, as under the health page on the phone: waking gently, the heart rate all day and the step goal.
/// Kept on the phone that has the watch, which passes it on. The step goal opens a small window, so it is never changed in passing.
private struct HealthChoicesCard: View {
    @Environment(EngineModel.self) private var model
    @State private var editingGoal = false
    @State private var draft = 0

    private var phone: TandemDevice? { model.devices.first { $0.status.watch?.paired == true && $0.status.watch?.settings != nil } }

    var body: some View {
        if let phone, let settings = phone.status.watch?.settings {
            let why: String? = phone.online ? nil : String(localized: "Its phone is not connected")
            VStack(alignment: .leading, spacing: 8) {
                SectionTitle("Preferences")
                Card(radius: Metrics.card, padding: 6) {
                    VStack(spacing: 0) {
                        SettingRow(symbol: "alarm", title: "Wake me gently", subtitle: "Five minutes before your alarm your watch starts one long, quiet buzz that rises and falls a few times, a little more each time. It never gets strong: your alarm wakes you the rest of the way") {
                            Toggle("", isOn: Binding(get: { settings.smartWake }, set: { model.setWatchSettings(phone.id, TandemWatchSettingsChange(smartWake: $0)) }))
                                .controlSize(.mini)
                                .disabled(why != nil)
                                .help(why ?? "")
                        }
                        Divider().opacity(0.4).padding(.horizontal, 14)
                        SettingRow(symbol: "heart", title: "Heart rate all day", subtitle: "Half a minute in every five while you wear your watch. Off saves battery.") {
                            Toggle("", isOn: Binding(get: { settings.heartAllDay }, set: { model.setWatchSettings(phone.id, TandemWatchSettingsChange(heartAllDay: $0)) }))
                                .controlSize(.mini)
                                .disabled(why != nil)
                                .help(why ?? "")
                        }
                        Divider().opacity(0.4).padding(.horizontal, 14)
                        SettingRow(symbol: "figure.walk", title: "Step goal", subtitle: "\(Int(settings.stepGoal).formatted()) steps a day. Your watch shows it too.") {
                            Button("Change") {
                                draft = Int(settings.stepGoal)
                                editingGoal = true
                            }
                            .buttonStyle(.tandemGlass)
                            .disabled(why != nil)
                            .help(why ?? "")
                            .popover(isPresented: $editingGoal, arrowEdge: .bottom) { goalEditor(phone) }
                        }
                    }
                }
            }
            .padding(.top, 8)
        }
    }

    private func goalEditor(_ phone: TandemDevice) -> some View {
        VStack(spacing: 14) {
            Text("Step goal").font(.headline)
            HStack(spacing: 16) {
                Button { draft = max(Self.goals.lowerBound, draft - Self.step) } label: { Image(systemName: "minus") }
                    .help("Fewer steps")
                    .disabled(draft <= Self.goals.lowerBound)
                Text(verbatim: draft.formatted()).font(.title2.weight(.bold)).monospacedDigit().frame(minWidth: 90)
                Button { draft = min(Self.goals.upperBound, draft + Self.step) } label: { Image(systemName: "plus") }
                    .help("More steps")
                    .disabled(draft >= Self.goals.upperBound)
            }
            .buttonStyle(.tandemGlass)
            HStack {
                Button("Cancel", role: .cancel) { editingGoal = false }
                Spacer()
                Button("Save") {
                    model.setWatchSettings(phone.id, TandemWatchSettingsChange(stepGoal: UInt32(draft)))
                    editingGoal = false
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(18)
        .frame(width: 280)
    }

    /// The goals the phone offers, so both say the same.
    private static let goals = 2000...30000
    private static let step = 500
}

/// What the page shows. The sums are the core's, so this says the same as the phone.
struct HealthPageContent: View {
    let insights: TandemHealthInsights
    let records: [TandemHealthRecord]

    private var latest: TandemHealthRecord? { records.first { $0.kind == .sleep } }
    private var earlier: [TandemHealthRecord] {
        records.filter { ($0.kind == .sleep || $0.kind == .workout) && $0.id != latest?.id }.prefix(30).map { $0 }
    }

    var body: some View {
        if let above = insights.alertAboveUsual {
            Card(radius: Metrics.card, padding: 16, tint: Palette.indigo) {
                Label {
                    Text("Your resting heart rate has been \(Int(above)) beats above usual for two nights. That can mean your body is dealing with something, like a cold coming on. This is not a diagnosis.")
                } icon: {
                    Image(systemName: "heart")
                }
            }
        }
        if let night = latest { LatestNightCards(night: night, insight: insights.latestNight?.id == night.id ? insights.latestNight : nil) }
        DayCards(insights: insights)
        WeekCards(insights: insights)
        if !earlier.isEmpty {
            SectionTitle("Earlier")
            ForEach(earlier, id: \.id) { record in
                if record.kind == .workout { WorkoutCard(record: record) } else { EarlierNightCard(record: record) }
            }
        }
    }
}

// MARK: Building blocks

private struct SectionTitle: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }

    var body: some View {
        Text(title)
            .font(.callout.weight(.semibold))
            .foregroundStyle(Palette.indigo)
            .padding(.horizontal, 4)
            .padding(.top, 14)
    }
}

/// The digits large and the units small, so the number is what the eye finds first.
private struct BigNumber: View {
    let text: String

    var body: some View {
        var styled = AttributedString()
        for char in text {
            var piece = AttributedString(String(char))
            if char.isNumber || ".,:".contains(char) {
                piece.font = .system(size: 30, weight: .semibold)
            } else {
                piece.font = .callout.weight(.medium)
                piece.foregroundColor = .secondary
            }
            styled.append(piece)
        }
        return Text(styled).lineLimit(1).contentTransition(.numericText())
    }
}

/// A card with words behind it, for what a person could really wonder about. The title says that it opens.
private struct HealthCard<Content: View>: View {
    var title: LocalizedStringKey?
    var more: LocalizedStringKey?
    @ViewBuilder var content: Content
    @State private var open = false

    var body: some View {
        Card(radius: Metrics.card, padding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                if let title, more != nil {
                    Button {
                        withAnimation(.tandem) { open.toggle() }
                    } label: {
                        HStack(spacing: 4) {
                            Text(title).font(.callout.weight(.semibold))
                            Image(systemName: "chevron.down")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .rotationEffect(.degrees(open ? 180 : 0))
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                } else if let title {
                    Text(title).font(.callout.weight(.semibold))
                }
                content
                if open, let more {
                    Text(more)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The shape of most of this page: what it is, the number, and the week beside it. Pointing at a day shows it in the same place, so no
/// number needs a label of its own on the chart.
private struct WeekCard<Chart: View>: View {
    let title: LocalizedStringKey
    let number: String
    let caption: String
    let week: [TandemWeekDay]
    let dayNumbers: [String?]
    var more: LocalizedStringKey?
    @ViewBuilder var chart: (Binding<Int?>) -> Chart
    @State private var picked: Int?

    var body: some View {
        HealthCard(title: title, more: more) {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    BigNumber(text: picked.flatMap { dayNumbers[$0] } ?? (picked == nil ? number : "-"))
                    Text(picked.map { dayName(week[$0].startMs) } ?? caption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .animation(.tandemFade, value: picked)
                Spacer(minLength: 8)
                chart($picked).frame(width: 196)
            }
        }
    }
}

private struct DayLetters: View {
    let days: [TandemWeekDay]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                Text(verbatim: noonOf(day.startMs).formatted(.dateTime.weekday(.narrow)))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(width: 18, height: 18)
                    .background(index == days.count - 1 ? Color.secondary.opacity(0.18) : .clear, in: Circle())
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

/// Seven days as rounded bars. A day without a measurement gets a low grey stripe: not zero, because nothing is known about it.
private struct WeekBars: View {
    let values: [Double?]
    let days: [TandemWeekDay]
    @Binding var picked: Int?
    var height: CGFloat = 76
    @State private var reveal: CGFloat = 0

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geometry in
                let top = max(values.compactMap { $0 }.max() ?? 1, 1) * 1.08
                HStack(alignment: .bottom, spacing: 0) {
                    ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                        ZStack(alignment: .bottom) {
                            Color.clear
                            if let value {
                                let bar = max(value / top * geometry.size.height, 8) * reveal
                                RoundedRectangle(cornerRadius: min(9, bar / 2), style: .continuous)
                                    .fill(Palette.indigo.opacity(picked == nil || picked == index ? 1 : 0.3))
                                    .frame(width: 18, height: bar)
                            } else {
                                Capsule().fill(Color.secondary.opacity(0.2)).frame(width: 18, height: 5)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                        .onHover { inside in
                            if inside { picked = index } else if picked == index { picked = nil }
                        }
                    }
                }
            }
            .frame(height: height)
            DayLetters(days: days)
        }
        .animation(.tandemFade, value: picked)
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }
}

/// Seven days as a line, for what never comes near zero and so would say nothing as bars.
private struct WeekLine: View {
    let values: [Double?]
    let days: [TandemWeekDay]
    var usual: Double?
    @Binding var picked: Int?
    var height: CGFloat = 76
    @State private var reveal: CGFloat = 0

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geometry in
                let known = values.compactMap { $0 } + (usual.map { [$0] } ?? [])
                let low = (known.min() ?? 0) - 3
                let high = (known.max() ?? 1) + 3
                let slot = geometry.size.width / CGFloat(max(values.count, 1))
                let point = { (index: Int, value: Double) -> CGPoint in
                    CGPoint(
                        x: slot * CGFloat(index) + slot / 2,
                        y: 8 + (1 - (value - low) / (high - low)) * (geometry.size.height - 16)
                    )
                }
                ZStack {
                    if let usual {
                        Path { path in
                            path.move(to: CGPoint(x: 0, y: point(0, usual).y))
                            path.addLine(to: CGPoint(x: geometry.size.width, y: point(0, usual).y))
                        }
                        .stroke(Color.secondary.opacity(0.5), lineWidth: 1)
                    }
                    Path { path in
                        var started = false
                        for (index, value) in values.enumerated() {
                            guard let value else { continue }
                            if started { path.addLine(to: point(index, value)) } else { path.move(to: point(index, value)) }
                            started = true
                        }
                    }
                    .trim(from: 0, to: reveal)
                    .stroke(Palette.indigo, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                        if let value {
                            Circle()
                                .fill(Palette.indigo)
                                .frame(width: picked == index ? 10 : 7, height: picked == index ? 10 : 7)
                                .position(point(index, value))
                                .opacity(reveal)
                        }
                    }
                    HStack(spacing: 0) {
                        ForEach(values.indices, id: \.self) { index in
                            Color.clear.contentShape(Rectangle()).onHover { inside in
                                if inside { picked = index } else if picked == index { picked = nil }
                            }
                        }
                    }
                }
            }
            .frame(height: height)
            DayLetters(days: days)
        }
        .animation(.tandemFade, value: picked)
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }
}

/// From falling asleep to getting up, night by night: one look shows whether the nights start and end at the same time. Early at the top.
private struct SleepWindows: View {
    let days: [TandemWeekDay]
    var height: CGFloat = 112
    @State private var reveal: CGFloat = 0

    var body: some View {
        let beds = days.compactMap { $0.bedMinutes.map(Int.init) }
        let wakes = days.compactMap { $0.wakeMinutes.map(Int.init) }
        if let first = beds.min(), let last = wakes.max() {
            // Whole hours, so the lines fall on times that read easily.
            let from = Int((Double(first) / 60).rounded(.down)) * 60
            let until = Int((Double(last) / 60).rounded(.up)) * 60
            let marks = Array(Set([from, from + (until - from) / 120 * 60, until])).sorted()
            let span = CGFloat(max(until - from, 60))
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(marks.enumerated()), id: \.offset) { index, mark in
                        if index > 0 { Spacer(minLength: 0) }
                        Text(verbatim: clock(fromMidnight: mark)).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .frame(width: 40, height: height)
                VStack(spacing: 6) {
                    GeometryReader { geometry in
                        ZStack(alignment: .topLeading) {
                            ForEach(marks, id: \.self) { mark in
                                Rectangle()
                                    .fill(Color.secondary.opacity(0.18))
                                    .frame(height: 1)
                                    .offset(y: CGFloat(mark - from) / span * geometry.size.height)
                            }
                            let slot = geometry.size.width / CGFloat(max(days.count, 1))
                            ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                                if let bed = day.bedMinutes, let wake = day.wakeMinutes {
                                    Capsule()
                                        .fill(Palette.indigo)
                                        .frame(width: 18, height: max(CGFloat(Int(wake) - Int(bed)) / span * geometry.size.height * reveal, 18))
                                        .offset(
                                            x: slot * CGFloat(index) + (slot - 18) / 2,
                                            y: CGFloat(Int(bed) - from) / span * geometry.size.height
                                        )
                                }
                            }
                        }
                    }
                    .frame(height: height)
                    DayLetters(days: days)
                }
            }
            .onAppear { withAnimation(.tandem) { reveal = 1 } }
        }
    }
}

/// A day of heart rate from midnight on. A gap of more than half an hour stays a gap: the watch was off the wrist, and a line through it
/// would claim otherwise.
///
/// As the night can be: pointing at it shows the time and the heart rate there, a pinch on the trackpad zooms in on a part of the day and
/// dragging moves along it, so one moment can be looked at closely. The scale follows what is in view, so a small change shows when zoomed.
private struct DayHeartLine: View {
    let curve: [UInt32]
    var height: CGFloat = 96
    @State private var reveal: CGFloat = 0
    @State private var pointer: CGFloat?
    @State private var width: CGFloat = 1
    /// The part of the day in view: from [from], a share of the day, over 1 / [zoom] of it.
    @State private var zoom: CGFloat = 1
    @State private var from: CGFloat = 0
    @State private var pinchStart: (zoom: CGFloat, from: CGFloat)?
    @State private var dragStart: CGFloat?

    private static let maxZoom: CGFloat = 12
    private var last: Int { max(curve.count - 1, 1) }
    private var span: CGFloat { 1 / zoom }

    /// A time on the axis, on a round five minutes and the whole day 0:00 to 0:00, so the labels read 6:00 and not 5:58.
    private func minute(at share: CGFloat) -> Int { Int((share * 1440 / 5).rounded()) * 5 }

    /// The measured point nearest to a share of the day, within a quarter of an hour, or none where the watch was off.
    private func point(at share: CGFloat) -> Int? {
        let at = min(max(Int((share * CGFloat(last)).rounded()), 0), last)
        for distance in 0...3 {
            for index in [at - distance, at + distance] where index >= 0 && index <= last && curve[index] > 0 { return index }
        }
        return nil
    }

    private func share(of x: CGFloat) -> CGFloat { from + min(max(x / width, 0), 1) * span }

    private var heading: String {
        if let pointer {
            let at = share(of: pointer)
            if let index = point(at: at) {
                return clock(fromMidnight: index * 5) + " \u{00B7} " + String(localized: "\(Int(curve[index])) bpm")
            }
            return clock(fromMidnight: minute(at: at)) + " \u{00B7} " + String(localized: "Nothing measured")
        }
        if zoom > 1.01 {
            return String(localized: "\(clock(fromMidnight: minute(at: from))) to \(clock(fromMidnight: minute(at: from + span)))")
        }
        return String(localized: "Point at the line to see a moment. Pinch to zoom in.")
    }

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                // Figures of one width, so the time and the rate do not shift as the pointer moves.
                Text(verbatim: heading).font(.caption).monospacedDigit().foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 8)
                if zoom > 1.01 {
                    Button("Whole day") {
                        withAnimation(.tandem) {
                            zoom = 1
                            from = 0
                        }
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
            }
            GeometryReader { geometry in
                let w = geometry.size.width
                let first = min(max(Int(from * CGFloat(last)), 0), last)
                let end = min(max(Int((from + span) * CGFloat(last)) + 1, 0), last)
                let inView = curve[first...end].filter { $0 > 0 }.map { Double($0) }
                let measured = inView.isEmpty ? curve.filter { $0 > 0 }.map { Double($0) } : inView
                let low = (measured.min() ?? 0) - 4
                let high = (measured.max() ?? 1) + 4
                let x = { (index: Int) -> CGFloat in (CGFloat(index) / CGFloat(last) - from) * zoom * w }
                let y = { (value: UInt32) -> CGFloat in 6 + (1 - (Double(value) - low) / (high - low)) * (geometry.size.height - 12) }
                ZStack(alignment: .topLeading) {
                    Path { path in
                        for quarter in 0...4 {
                            let at = w * CGFloat(quarter) / 4
                            path.move(to: CGPoint(x: at, y: 0))
                            path.addLine(to: CGPoint(x: at, y: geometry.size.height))
                        }
                    }
                    .stroke(Color.secondary.opacity(0.14), lineWidth: 1)
                    Path { path in
                        var before = -100
                        for (index, value) in curve.enumerated() where value > 0 {
                            let point = CGPoint(x: x(index), y: y(value))
                            if index - before > 6 { path.move(to: point) } else { path.addLine(to: point) }
                            before = index
                        }
                    }
                    .trim(from: 0, to: reveal)
                    .stroke(Palette.indigo, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    if let pointer {
                        Rectangle().fill(Color.primary).frame(width: 1, height: geometry.size.height).offset(x: min(max(pointer, 0), w))
                        if let index = point(at: share(of: pointer)) {
                            Circle()
                                .fill(Palette.indigo)
                                .frame(width: 8, height: 8)
                                .overlay(Circle().stroke(Color(nsColor: .windowBackgroundColor), lineWidth: 2))
                                .position(x: x(index), y: y(curve[index]))
                        }
                    }
                }
                .clipped()
                .contentShape(Rectangle())
                .onContinuousHover { phase in
                    switch phase {
                    case .active(let location): pointer = location.x
                    case .ended: pointer = nil
                    }
                }
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            let start = pinchStart ?? (zoom, from)
                            pinchStart = start
                            let centre = value.startLocation.x / w
                            let under = start.from + centre / start.zoom
                            let next = min(max(start.zoom * value.magnification, 1), Self.maxZoom)
                            zoom = next
                            from = min(max(under - centre / next, 0), 1 - 1 / next)
                        }
                        .onEnded { _ in pinchStart = nil }
                )
                .simultaneousGesture(
                    // Dragging moves along a zoomed day; over the whole day there is nowhere to go.
                    DragGesture(minimumDistance: 4)
                        .onChanged { value in
                            guard zoom > 1.01 else { return }
                            let start = dragStart ?? from
                            dragStart = start
                            from = min(max(start - value.translation.width / w / zoom, 0), 1 - 1 / zoom)
                        }
                        .onEnded { _ in dragStart = nil }
                )
            }
            .frame(height: height)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = max($0, 1) }
            HStack {
                ForEach(0...4, id: \.self) { quarter in
                    Text(verbatim: clock(fromMidnight: minute(at: from + span * CGFloat(quarter) / 4))).font(.caption2).foregroundStyle(.secondary)
                    if quarter != 4 { Spacer(minLength: 0) }
                }
            }
        }
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }
}

/// The night as it went: which stage when, on four lanes with the deepest at the bottom. Pointing at it says what the model thought of that
/// moment, so a person can hold it against what they remember.
private struct SleepTimeline: View {
    let record: TandemHealthRecord
    @State private var pointer: CGFloat?
    @State private var reveal: CGFloat = 0

    private static let lanes: [TandemSleepStage] = [.wake, .rem, .light, .deep]
    private static let laneHeight: CGFloat = 30

    var body: some View {
        let total = CGFloat(record.stages.reduce(0) { $0 + Int($1.seconds) })
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { geometry in
                let width = geometry.size.width - 52
                Text(heading(width: width, total: total))
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 16)
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Self.lanes, id: \.self) { lane in
                        Text(stageName(lane))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .frame(height: Self.laneHeight)
                    }
                }
                .frame(width: 44, alignment: .leading)
                GeometryReader { geometry in
                    Canvas { context, size in
                        draw(in: &context, size: size, total: total)
                    }
                    .mask(alignment: .leading) { Rectangle().frame(width: geometry.size.width * reveal) }
                    .overlay(alignment: .leading) {
                        if let pointer {
                            Rectangle().fill(Color.primary).frame(width: 1).offset(x: pointer)
                        }
                    }
                    .contentShape(Rectangle())
                    .onContinuousHover { phase in
                        switch phase {
                        case let .active(location): pointer = min(max(location.x, 0), geometry.size.width)
                        case .ended: pointer = nil
                        }
                    }
                }
                .frame(height: Self.laneHeight * CGFloat(Self.lanes.count))
            }
        }
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }

    private func heading(width: CGFloat, total: CGFloat) -> String {
        guard let pointer, width > 0, total > 0 else {
            return String(localized: "\(clock(record.startMs)) to \(clock(record.endMs))")
        }
        let share = min(pointer / width, 1)
        var seconds = share * total
        let run = record.stages.first { candidate in
            seconds -= CGFloat(candidate.seconds)
            return seconds <= 0
        }
        let at = record.startMs + UInt64(Double(record.endMs - record.startMs) * Double(share))
        return "\(clock(at)) \u{00B7} \(run.map { String(localized: stageName($0.stage)) } ?? String(localized: "Nothing measured"))"
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, total: CGFloat) {
        guard total > 0 else { return }
        let lane = size.height / CGFloat(Self.lanes.count)
        let bar = lane * 0.62
        for index in Self.lanes.indices {
            var guide = Path()
            guide.move(to: CGPoint(x: 0, y: lane * CGFloat(index) + lane / 2))
            guide.addLine(to: CGPoint(x: size.width, y: lane * CGFloat(index) + lane / 2))
            context.stroke(guide, with: .color(.secondary.opacity(0.16)), lineWidth: 1)
        }
        var seconds: CGFloat = 0
        var before: Int?
        for run in record.stages {
            let x = seconds / total * size.width
            let length = max(CGFloat(run.seconds) / total * size.width, 2)
            seconds += CGFloat(run.seconds)
            guard let index = Self.lanes.firstIndex(of: run.stage) else {
                before = nil
                continue
            }
            let y = lane * CGFloat(index) + lane / 2
            if let before, before != index {
                var link = Path()
                link.move(to: CGPoint(x: x, y: lane * CGFloat(before) + lane / 2))
                link.addLine(to: CGPoint(x: x, y: y))
                context.stroke(link, with: .color(.secondary.opacity(0.4)), lineWidth: 1)
            }
            let shape = Path(roundedRect: CGRect(x: x, y: y - bar / 2, width: length, height: bar), cornerRadius: min(bar, length) / 2)
            context.fill(shape, with: .color(stageColor(run.stage)))
            before = index
        }
    }
}

private struct ScorePart: View {
    let label: LocalizedStringKey
    let value: UInt32
    @State private var reveal: CGFloat = 0

    var body: some View {
        HStack(spacing: 10) {
            Text(label).font(.caption).foregroundStyle(.secondary).frame(width: 64, alignment: .leading)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.18))
                    Capsule()
                        .fill(Palette.indigo)
                        .frame(width: max(geometry.size.width * CGFloat(min(value, 100)) / 100 * reveal, 8))
                }
            }
            .frame(height: 8)
            Text(verbatim: "\(value)").font(.caption.monospacedDigit()).frame(width: 26, alignment: .trailing)
        }
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }
}

private struct Fact: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(verbatim: value).font(.callout.weight(.semibold)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// How many of the nights something needs are already there. A row of dots says "this is coming" better than an empty card.
private struct NightsToGo: View {
    let needed: Int
    let total: Int

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<total, id: \.self) { index in
                Circle()
                    .fill(index < total - needed ? Palette.indigo : Color.secondary.opacity(0.2))
                    .frame(width: 8, height: 8)
            }
        }
    }
}

private struct ScoreRing: View {
    let score: UInt32
    @State private var reveal: CGFloat = 0

    var body: some View {
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.18), lineWidth: 8)
            Circle()
                .trim(from: 0, to: CGFloat(min(score, 100)) / 100 * reveal)
                .stroke(Palette.indigo, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(verbatim: "\(score)").font(.title3.bold()).contentTransition(.numericText(value: Double(score)))
        }
        .frame(width: 68, height: 68)
        .onAppear { withAnimation(.tandem) { reveal = 1 } }
    }
}

// MARK: The sections

private struct NightFacts: View {
    let record: TandemHealthRecord

    var body: some View {
        HStack(spacing: 8) {
            Fact(label: "Asleep", value: "\(record.efficiencyPercent)%")
            Fact(label: "Awake", value: minutesText(Int(record.awakeMinutes)))
            if let mean = record.heartRateMean { Fact(label: "Heart rate", value: "\(mean)") }
            if let low = record.heartRateLow { Fact(label: "Lowest", value: "\(low)") }
        }
    }
}

private struct StageTiles: View {
    let record: TandemHealthRecord

    var body: some View {
        if let light = record.lightMinutes, let deep = record.deepMinutes, let rem = record.remMinutes {
            HStack(spacing: 8) {
                tile(.light, light)
                tile(.deep, deep)
                tile(.rem, rem)
                tile(.wake, record.awakeMinutes)
            }
        }
    }

    private func tile(_ stage: TandemSleepStage, _ minutes: UInt32) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Circle().fill(stageColor(stage)).frame(width: 8, height: 8)
                Text(stageName(stage)).font(.caption2).foregroundStyle(.secondary)
            }
            Text(verbatim: minutesText(Int(minutes))).font(.callout.weight(.semibold)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The newest night, large: this is what the page gets opened for in the morning.
private struct LatestNightCards: View {
    let night: TandemHealthRecord
    let insight: TandemNightInsight?

    var body: some View {
        SectionTitle("Sleep")
        HealthCard {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    BigNumber(text: minutesText(Int(night.sleepMinutes)))
                    Text(day(night)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if let insight { ScoreRing(score: insight.score) }
            }
            NightFacts(record: night)
        }
        if !night.stages.isEmpty {
            HealthCard(
                title: "The night",
                more: "Most of a night is light sleep. Deep sleep comes mostly early in the night, REM mostly towards morning. Your watch estimates this from heart rate and movement, not a sleep lab. Point at the chart to see a moment."
            ) {
                SleepTimeline(record: night)
                StageTiles(record: night)
                if let insight {
                    ForEach(insight.wakeMoments, id: \.startMs) { moment in
                        Text("Awake at \(clock(moment.startMs)), \(minutesText(Int(moment.minutes)))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        if let quality = insight?.quality, !quality.isEmpty {
            HealthCard(
                title: "Sleep quality",
                more: "The parts the Sleep Score of Google shows. Time to sound sleep runs until the first ten minutes asleep without waking. Sound sleep is asleep in stretches of half an hour or more. Restlessness is the brief stirs under five minutes, interruptions the time awake in wakes of five minutes or more, and full awakenings how many of those there were. Tandem chose the ranges for adults: it knows no age or gender."
            ) {
                VStack(spacing: 8) {
                    ForEach(Array(quality.enumerated()), id: \.offset) { _, measure in QualityRow(measure: measure) }
                }
                HStack(spacing: 8) {
                    Capsule().strokeBorder(Color.secondary, style: StrokeStyle(lineWidth: 1, dash: [3, 2])).frame(width: 18, height: 10)
                    Text("Typical range for adults").font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        if let insight {
            HealthCard(
                title: "Sleep score",
                more: "Mostly how long you slept: 7 to 9 hours counts in full. Then how calm the night was: the first 10 minutes awake cost nothing. Deep sleep and REM count least, because a wrist can tell them least well."
            ) {
                ScorePart(label: "Length", value: insight.durationScore)
                ScorePart(label: "Calm", value: insight.continuityScore)
                if let stages = insight.stagesScore { ScorePart(label: "Stages", value: stages) }
            }
        }
    }
}

/// One part of the quality of a night as Google's Sleep Score shows it: a bar with the range most adults fall in as a dashed box, the
/// night as a dot, and a word for whether it falls in that range.
private struct QualityRow: View {
    let measure: TandemQualityMeasure

    private var inRange: Bool { (measure.typicalLow...measure.typicalHigh).contains(measure.value) }
    /// Amber outside the range: a sign to look at, not an alarm, so not the colour of an error.
    private var colour: Color { inRange ? .green : Color(red: 0.89, green: 0.63, blue: 0.03) }

    private var name: LocalizedStringKey {
        switch measure.kind {
        case .toSoundSleep: "Time to sound sleep"
        case .soundSleep: "Sound sleep"
        case .restlessness: "Restlessness"
        case .interruptions: "Interruptions"
        case .fullAwakenings: "Full awakenings"
        }
    }

    private var shown: String {
        measure.kind == .fullAwakenings ? String(localized: "\(Int(measure.value)) moments") : minutesText(Int(measure.value))
    }

    private var badge: LocalizedStringKey {
        measure.value > measure.typicalHigh ? "Above range" : measure.value < measure.typicalLow ? "Below range" : "In range"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                (Text(name) + Text(verbatim: " \u{00B7} \(shown)").foregroundStyle(.secondary)).font(.callout)
                Spacer(minLength: 8)
                Text(badge)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(colour.opacity(0.25), in: .capsule)
            }
            GeometryReader { geometry in
                let width = geometry.size.width
                let bar: CGFloat = 12
                let height = geometry.size.height
                let max = CGFloat(Swift.max(measure.scaleMax, 1))
                let x = { (at: UInt32) -> CGFloat in Swift.min(Swift.max(CGFloat(at) / max, 0), 1) * width }
                let end = Swift.min(Swift.max(x(measure.value), bar), width)
                let left = x(measure.typicalLow)
                let right = Swift.max(x(measure.typicalHigh), left + bar)
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08)).frame(width: width, height: bar)
                    Capsule().fill(colour).frame(width: end, height: bar)
                    Capsule()
                        .strokeBorder(Color.primary, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                        .frame(width: right - left, height: height)
                        .offset(x: left)
                    Circle()
                        .fill(Color.primary)
                        .frame(width: 13, height: 13)
                        .overlay(Circle().stroke(Color(nsColor: .controlBackgroundColor), lineWidth: 2.5))
                        .offset(x: Swift.min(Swift.max(end - bar / 2, bar / 2), width - bar / 2) - 6.5)
                }
                .frame(height: height)
            }
            .frame(height: 20)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.primary.opacity(0.04), in: .rect(cornerRadius: 16, style: .continuous))
    }
}

/// A computer sees whole days: only the phone hears the watch during the day.
private struct DayCards: View {
    let insights: TandemHealthInsights

    var body: some View {
        let week = insights.week
        let lastSteps = week.lastIndex { $0.steps != nil }
        let lastHeart = insights.latestDayHeart
        if lastSteps != nil || lastHeart != nil || !insights.naps.isEmpty {
            SectionTitle("Days")
        }
        if let lastSteps, let steps = week[lastSteps].steps {
            WeekCard(
                title: "Steps",
                number: Int(steps).formatted(),
                caption: dayName(week[lastSteps].startMs),
                week: week,
                dayNumbers: week.map { $0.steps.map { Int($0).formatted() } }
            ) { picked in
                WeekBars(values: week.map { $0.steps.map { Double($0) } }, days: week, picked: picked)
            }
            WeekCard(
                title: "Active minutes",
                number: String(localized: "\(Int(week[lastSteps].activeMinutes ?? 0)) min"),
                caption: String(localized: "\(week.reduce(0) { $0 + Int($1.activeMinutes ?? 0) }) of 150 this week"),
                week: week,
                dayNumbers: week.map { $0.activeMinutes.map { String(localized: "\(Int($0)) min") } },
                more: "A minute counts when you walk briskly, about 100 steps a minute. Health advice for adults is 150 minutes a week."
            ) { picked in
                WeekBars(values: week.map { $0.activeMinutes.map { Double($0) } }, days: week, picked: picked)
            }
        }
        if let lastHeart {
            let measured = lastHeart.curve.filter { $0 > 0 }
            HealthCard(
                title: "Heart rate",
                more: "Half a minute of measuring every five minutes while you wear your watch. A gap means it was off your wrist or could not measure."
            ) {
                HStack(alignment: .lastTextBaseline, spacing: 12) {
                    BigNumber(text: String(localized: "\(Int(measured.last ?? 0)) bpm"))
                    Text("\(dayName(lastHeart.startMs)), lowest \(Int(measured.min() ?? 0)), highest \(Int(measured.max() ?? 0))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                DayHeartLine(curve: lastHeart.curve)
            }
        }
        if !insights.naps.isEmpty {
            HealthCard(
                title: "Naps",
                more: "If you lie still for 20 minutes during the day, your watch asks whether you slept. Only a yes makes it a nap. You can also start one yourself on your watch."
            ) {
                BigNumber(text: minutesText(insights.naps.reduce(0) { $0 + Int($1.sleepMinutes) }))
                ForEach(insights.naps, id: \.startMs) { nap in
                    Text(verbatim: "\(dateTime(nap.startMs)), \(minutesText(Int(nap.sleepMinutes)))").font(.callout)
                }
            }
        }
    }
}

/// What only shows over more nights. Each card says how far it has come, because an empty page would look broken.
private struct WeekCards: View {
    let insights: TandemHealthInsights

    var body: some View {
        let week = insights.week
        let now = insights.thisWeek
        let before = insights.lastWeek
        if insights.latestNight != nil {
            SectionTitle("This week")
            if let sleep = now.meanSleepMinutes {
                WeekCard(
                    title: "Sleep",
                    number: minutesText(Int(sleep)),
                    caption: before.meanSleepMinutes.map { String(localized: "A night on average, last week \(minutesText(Int($0)))") } ?? String(localized: "A night on average"),
                    week: week,
                    dayNumbers: week.map { $0.sleepMinutes.map { minutesText(Int($0)) } }
                ) { picked in
                    WeekBars(values: week.map { $0.sleepMinutes.map { Double($0) } }, days: week, picked: picked)
                }
            }
            if let score = now.meanScore {
                WeekCard(
                    title: "Sleep score",
                    number: "\(score)",
                    caption: before.meanScore.map { String(localized: "On average, last week \(Int($0))") } ?? String(localized: "On average"),
                    week: week,
                    dayNumbers: week.map { $0.score.map { "\($0)" } }
                ) { picked in
                    WeekBars(values: week.map { $0.score.map { Double($0) } }, days: week, picked: picked)
                }
            }
            if let resting = insights.resting {
                WeekCard(
                    title: "Resting heart rate",
                    number: String(localized: "\(Int(resting.latest)) bpm"),
                    caption: resting.usual.map { String(localized: "Usually \(Int($0))") } ?? String(localized: "\(Int(resting.nightsNeeded)) more nights to show this"),
                    week: week,
                    dayNumbers: week.map { $0.resting.map { String(localized: "\(Int($0)) bpm") } },
                    more: "The lowest heart rate of the night. It says most next to your own usual: a cold or a drink can push it up for a night or two. Not a diagnosis."
                ) { picked in
                    VStack(spacing: 8) {
                        WeekLine(values: week.map { $0.resting.map { Double($0) } }, days: week, usual: resting.usual.map { Double($0) }, picked: picked)
                        if resting.usual == nil { NightsToGo(needed: Int(resting.nightsNeeded), total: 7) }
                    }
                }
            }
            HealthCard(
                title: "Regularity",
                more: "From your last nights, at most 14. Give or take says how far a usual night is from that time: the smaller, the steadier."
            ) {
                if let regular = insights.regularity {
                    Text("To bed around \(clock(minutes: regular.bedtimeMinutes)), give or take \(minutesText(Int(regular.bedtimeSpreadMinutes)))")
                    Text("Up around \(clock(minutes: regular.wakeMinutes)), give or take \(minutesText(Int(regular.wakeSpreadMinutes)))")
                } else {
                    Text("\(Int(insights.regularityNightsNeeded)) more nights to show this").font(.caption).foregroundStyle(.secondary)
                    NightsToGo(needed: Int(insights.regularityNightsNeeded), total: 5)
                }
                SleepWindows(days: week)
            }
            if now.workouts > 0 {
                HealthCard(title: "Workouts") {
                    BigNumber(text: minutesText(Int(now.workoutMinutes)))
                    Text(now.workouts == 1 ? "1 workout this week" : "\(Int(now.workouts)) workouts this week").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A night that opens where it is, to its whole chart.
private struct EarlierNightCard: View {
    let record: TandemHealthRecord
    @State private var open = false

    var body: some View {
        Card(radius: Metrics.card, padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Button {
                    guard !record.stages.isEmpty else { return }
                    withAnimation(.tandem) { open.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Text(verbatim: day(record)).font(.callout.weight(.semibold))
                        if !record.stages.isEmpty {
                            Image(systemName: "chevron.down")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .rotationEffect(.degrees(open ? 180 : 0))
                        }
                        Spacer(minLength: 8)
                        Text(verbatim: "\(minutesText(Int(record.sleepMinutes))), \(record.efficiencyPercent)%").font(.caption).foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if open {
                    SleepTimeline(record: record).transition(.opacity)
                    StageTiles(record: record).transition(.opacity)
                    NightFacts(record: record).transition(.opacity)
                } else if !record.stages.isEmpty {
                    StageBar(stages: record.stages).transition(.opacity)
                }
            }
        }
    }
}

private struct WorkoutCard: View {
    let record: TandemHealthRecord

    var body: some View {
        Card(radius: Metrics.card, padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Label { Text(verbatim: day(record)).font(.callout.weight(.semibold)) } icon: { Image(systemName: "figure.run") }
                HStack(spacing: 8) {
                    Fact(label: "Length", value: minutesText(Int((record.endMs - record.startMs) / 60_000)))
                    if let mean = record.heartRateMean { Fact(label: "Heart rate", value: "\(mean)") }
                    if let high = record.heartRateHigh { Fact(label: "Highest", value: "\(high)") }
                    if record.steps > 0 { Fact(label: "Steps", value: Int(record.steps).formatted()) }
                }
            }
        }
    }
}

private struct StageBar: View {
    let stages: [TandemStageRun]

    var body: some View {
        let total = CGFloat(stages.reduce(0) { $0 + Int($1.seconds) })
        GeometryReader { geometry in
            HStack(spacing: 0) {
                ForEach(Array(stages.enumerated()), id: \.offset) { _, run in
                    Rectangle().fill(stageColor(run.stage)).frame(width: geometry.size.width * CGFloat(run.seconds) / total)
                }
            }
        }
        .frame(height: 8)
        .clipShape(Capsule())
    }
}

// MARK: Words and colours

private func clock(_ ms: UInt64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened)
}

private func clock(minutes: UInt32) -> String {
    String(format: "%02d:%02d", Int(minutes) / 60 % 24, Int(minutes) % 60)
}

/// Minutes from a midnight, which can be negative for the evening before.
private func clock(fromMidnight minutes: Int) -> String {
    let inDay = ((minutes % 1440) + 1440) % 1440
    return String(format: "%02d:%02d", inDay / 60, inDay % 60)
}

func minutesText(_ minutes: Int) -> String {
    minutes >= 60 ? String(localized: "\(minutes / 60) h \(minutes % 60) m") : String(localized: "\(minutes) m")
}

/// Half a day on, so a change of the clock can not move a day.
private func noonOf(_ midnightMs: UInt64) -> Date {
    Date(timeIntervalSince1970: Double(midnightMs) / 1000 + 12 * 3600)
}

func dayName(_ midnightMs: UInt64) -> String {
    noonOf(midnightMs).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
}

private func dateTime(_ ms: UInt64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(.dateTime.weekday(.abbreviated).hour().minute())
}

/// A night that starts before midnight belongs to the morning it ends in.
private func day(_ record: TandemHealthRecord) -> String {
    Date(timeIntervalSince1970: Double(record.kind == .workout ? record.startMs : record.endMs) / 1000).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
}

private func stageName(_ stage: TandemSleepStage) -> LocalizedStringResource {
    switch stage {
    case .wake: "Awake"
    case .rem: "REM"
    case .light: "Light"
    case .deep: "Deep"
    case .unknown: "Nothing measured"
    }
}

private func stageColor(_ stage: TandemSleepStage) -> Color {
    switch stage {
    case .deep: Palette.indigo
    case .light: Palette.indigo.opacity(0.4)
    case .rem: .purple
    // The moments people look for, so they stand out. Not a warning colour: being awake for a bit is nothing to worry about.
    case .wake: .primary.opacity(0.7)
    case .unknown: .secondary.opacity(0.25)
    }
}
