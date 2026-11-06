import SwiftUI
import TandemCore

/// The nights and workouts of the watch, by way of the phone. Which devices get them is a switch on the phone: only that one has a watch.
struct HealthSettings: View {
    @Environment(EngineModel.self) private var model
    @AppStorage("showHealth") private var showHealth = false

    private var hasWatch: Bool { model.devices.contains { $0.status.watch?.paired == true } }
    private var records: [TandemHealthRecord] { model.healthRecords.filter { $0.kind != .unknown } }

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
