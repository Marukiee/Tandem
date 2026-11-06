import SwiftUI
import TandemCore

/// The nights and workouts of the watch, by way of the phone. Which devices get them is a switch on the phone: only that one has a watch.
struct HealthSettings: View {
    @Environment(EngineModel.self) private var model
    @AppStorage("showHealth") private var showHealth = false

    private var hasWatch: Bool { model.devices.contains { $0.status.watch?.paired == true } }
    private var records: [TandemHealthRecord] { model.healthRecords.filter { $0.kind == .sleep || $0.kind == .workout } }

    var body: some View {
        Form {
            // Without a watch this is one more page that stays empty, unless someone asked for it.
            if !hasWatch {
                Section {
                    Toggle("Show without a watch", isOn: $showHealth)
                } footer: {
                    Text("This shows by itself once one of your phones has a watch.")
                }
            }
            if hasWatch || showHealth {
                if let insights = model.healthInsights { HealthInsightSections(insights: insights) }
                Section {
                    if records.isEmpty {
                        Text("Nothing here right now. Your phone decides which devices get them.").foregroundStyle(.secondary)
                    }
                    ForEach(records.prefix(60), id: \.id) { record in
                        if record.kind == .workout { WorkoutRow(record: record) } else { SleepRow(record: record) }
                    }
                } footer: {
                    Text("Sleep stages are an estimate from heart rate and movement, not a sleep lab.")
                }
            }
        }
        .formStyle(.grouped)
    }
}

/// What the nights say together. The sums are the core's, so this says the same as the phone.
private struct HealthInsightSections: View {
    let insights: TandemHealthInsights

    var body: some View {
        if let above = insights.alertAboveUsual {
            Section {
                Label {
                    Text("Your resting heart rate has been \(Int(above)) beats above usual for two nights. That can mean your body is dealing with something, like a cold coming on. This is not a diagnosis.")
                } icon: {
                    Image(systemName: "heart")
                }
            }
        }
        if let night = insights.latestNight {
            Section("Latest night") {
                LabeledContent("Sleep score") {
                    Text(verbatim: "\(night.score)").font(.title2.bold()).monospacedDigit()
                }
                if let stages = night.stagesScore {
                    Text("From length \(Int(night.durationScore)), calm \(Int(night.continuityScore)) and stages \(Int(stages))").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("From length \(Int(night.durationScore)) and calm \(Int(night.continuityScore))").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(night.wakeMoments, id: \.startMs) { moment in
                    Text("Awake at \(clock(moment.startMs)), \(minutesText(Int(moment.minutes)))").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Over more nights") {
                LabeledContent("Resting heart rate") { resting }
                LabeledContent("Regularity") { regularity }
                LabeledContent("This week") { week }
            }
        }
    }

    private func waiting(_ needed: UInt32) -> some View {
        Text("\(Int(needed)) more nights to show this").foregroundStyle(.secondary)
    }

    @ViewBuilder private var resting: some View {
        if let resting = insights.resting {
            if let usual = resting.usual {
                Text("\(Int(resting.latest)) bpm, usually \(Int(usual))")
            } else {
                VStack(alignment: .trailing) {
                    Text("\(Int(resting.latest)) bpm")
                    waiting(resting.nightsNeeded)
                }
            }
        } else {
            waiting(1)
        }
    }

    @ViewBuilder private var regularity: some View {
        if let regular = insights.regularity {
            VStack(alignment: .trailing) {
                Text("To bed around \(clock(minutes: regular.bedtimeMinutes)), give or take \(minutesText(Int(regular.bedtimeSpreadMinutes)))")
                Text("Up around \(clock(minutes: regular.wakeMinutes)), give or take \(minutesText(Int(regular.wakeSpreadMinutes)))")
            }
        } else {
            waiting(insights.regularityNightsNeeded)
        }
    }

    @ViewBuilder private var week: some View {
        let now = insights.thisWeek, before = insights.lastWeek
        VStack(alignment: .trailing) {
            if let sleep = now.meanSleepMinutes {
                if let was = before.meanSleepMinutes {
                    Text("\(minutesText(Int(sleep))) a night, last week \(minutesText(Int(was)))")
                } else {
                    Text("\(minutesText(Int(sleep))) a night")
                }
            }
            if let score = now.meanScore {
                if let was = before.meanScore {
                    Text("Score \(Int(score)), last week \(Int(was))")
                } else {
                    Text("Score \(Int(score))")
                }
            }
            if let resting = now.meanResting {
                if let was = before.meanResting {
                    Text("Resting \(Int(resting)) bpm, last week \(Int(was))")
                } else {
                    Text("Resting \(Int(resting)) bpm")
                }
            }
            if let steps = now.meanSteps {
                if let was = before.meanSteps {
                    Text("\(Int(steps)) steps a day, last week \(Int(was))")
                } else {
                    Text("\(Int(steps)) steps a day")
                }
                Text("\(Int(now.activeMinutes)) of 150 active minutes")
            }
            if now.workouts > 0 {
                Text("\(Int(now.workouts)) workouts, \(minutesText(Int(now.workoutMinutes)))")
            }
            if now.meanSleepMinutes == nil && now.workouts == 0 && now.meanSteps == nil {
                Text("Nothing this week yet.").foregroundStyle(.secondary)
            }
        }
    }
}

private func clock(_ ms: UInt64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened)
}

private func clock(minutes: UInt32) -> String {
    String(format: "%02d:%02d", Int(minutes) / 60 % 24, Int(minutes) % 60)
}

private func minutesText(_ minutes: Int) -> String {
    minutes >= 60 ? String(localized: "\(minutes / 60) h \(minutes % 60) m") : String(localized: "\(minutes) m")
}

/// A night that starts before midnight belongs to the morning it ends in.
private func day(_ record: TandemHealthRecord) -> String {
    Date(timeIntervalSince1970: Double(record.kind == .workout ? record.startMs : record.endMs) / 1000).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
}

private func duration(_ minutes: Int) -> LocalizedStringKey {
    minutes >= 60 ? "\(minutes / 60) h \(minutes % 60) m" : "\(minutes) m"
}

private struct SleepRow: View {
    let record: TandemHealthRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(day(record)).font(.headline)
            HStack(spacing: 0) {
                Text(duration(Int(record.sleepMinutes)))
                Text(verbatim: ", ")
                Text("\(Int(record.efficiencyPercent))% of the night")
            }
            if !record.stages.isEmpty { StageBar(stages: record.stages) }
            if let light = record.lightMinutes, let deep = record.deepMinutes, let rem = record.remMinutes {
                HStack(spacing: 12) {
                    Legend(stage: .light, name: "Light", minutes: light)
                    Legend(stage: .deep, name: "Deep", minutes: deep)
                    Legend(stage: .rem, name: "REM", minutes: rem)
                }
            }
            if let mean = record.heartRateMean, let low = record.heartRateLow {
                Text("Heart rate \(Int(mean)) on average, \(Int(low)) lowest").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct WorkoutRow: View {
    let record: TandemHealthRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(day(record)).font(.headline)
            Text(duration(Int((record.endMs - record.startMs) / 60_000)))
            if let mean = record.heartRateMean, let high = record.heartRateHigh {
                Text("Heart rate \(Int(mean)) on average, \(Int(high)) highest").font(.caption).foregroundStyle(.secondary)
            }
            if record.steps > 0 {
                Text("\(Int(record.steps)) steps").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
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
        .frame(height: 10)
        .clipShape(Capsule())
    }
}

private struct Legend: View {
    let stage: TandemSleepStage
    let name: LocalizedStringKey
    let minutes: UInt32

    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(stageColor(stage)).frame(width: 8, height: 8)
            Text(name)
            Text(duration(Int(minutes)))
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

private func stageColor(_ stage: TandemSleepStage) -> Color {
    switch stage {
    case .deep: .accentColor
    case .light: .accentColor.opacity(0.4)
    case .rem: .purple
    case .wake, .unknown: .secondary.opacity(0.25)
    }
}
