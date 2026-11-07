import SwiftUI
import TandemCore

/// The watch of a phone as a device of its own. It only ever talks to that phone, so everything here goes by way of it, and what needs
/// the phone is grey and says why while the phone is away.
struct WatchDetail: View {
    @Environment(EngineModel.self) private var model
    let phone: TandemDevice
    var onOpenPhone: () -> Void = {}

    @State private var speaker = WatchAudio.shared.chosen
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmUnpair = false

    private var watch: TandemWatchStatus? { phone.status.watch }
    private var reachable: Bool { phone.online && watch?.online == true }
    private var name: String { watch?.name ?? String(localized: "Watch") }
    /// What is set for the watch, as its phone said last. Nil from a phone older than these: then nothing is shown as off that may be on.
    private var settings: TandemWatchSettings? { watch?.settings }
    /// Why the choices cannot be changed now, or nil when they can.
    private var settingsWhy: String? { phone.online ? nil : String(localized: "Its phone is not connected") }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                glance
                actions
                choices
                removal
            }
            .pagePadding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .animation(.tandem, value: reachable)
        .animation(.tandemFade, value: watch?.sleeping)
        .animation(.tandemFade, value: settings == nil)
        .alert("Rename watch", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") { model.renameWatch(phone.id, name: newName == String(localized: "Watch") ? "" : newName) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your phone and your other computers show it by this name too.")
        }
        .confirmationDialog("Unpair \(name)?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { model.unpairWatch(phone.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Pairing it again is done on your phone.")
        }
    }

    private var header: some View {
        Card(radius: Metrics.card, padding: 22, tint: reachable ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                Image(systemName: "applewatch")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(Palette.indigo)
                    .frame(width: 80, height: 80)
                    .background(Palette.indigo.opacity(0.14), in: Circle())
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: name).font(.title.weight(.bold)).lineLimit(1).contentTransition(.opacity)
                    Text("Wear OS \u{00B7} by way of \(phone.name)").font(.callout).foregroundStyle(.secondary).lineLimit(1)
                    HStack(spacing: 8) {
                        Chip(
                            symbol: reachable ? "checkmark.circle.fill" : "applewatch.slash",
                            text: reachable ? "Connected" : "Watch away",
                            tint: reachable ? .green : .secondary,
                            strong: reachable
                        )
                        if reachable, watch?.sleeping == true {
                            Chip(symbol: "bed.double.fill", text: "Recording your sleep", tint: Palette.indigo)
                        }
                    }
                }
                Spacer(minLength: 0)
                if let level = watch?.level {
                    BatteryRing(battery: TandemBattery(level: level, charging: watch?.charging == true, powerSave: false), size: 74, connected: reachable)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
    }

    /// What the watch measured last, as three numbers that open the whole page. Only what this Mac was given, and only once there is
    /// some: a computer sees whole days, so the steps are of the last whole day.
    @ViewBuilder
    private var glance: some View {
        let insights = model.healthInsights
        let night = model.healthRecords.first { $0.kind == .sleep }
        let stepsDay = insights?.week.last { $0.steps != nil }
        if night != nil || stepsDay != nil || insights?.resting != nil {
            HStack(alignment: .top, spacing: 12) {
                if let night {
                    let score = insights?.latestNight?.id == night.id ? insights?.latestNight?.score : nil
                    GlanceTile(
                        symbol: "bed.double", title: "Last night", number: minutesText(Int(night.sleepMinutes)),
                        caption: score.map { String(localized: "Sleep score \(Int($0))") } ?? dayName(night.endMs)
                    )
                }
                if let day = stepsDay, let steps = day.steps {
                    GlanceTile(symbol: "figure.walk", title: "Steps", number: Int(steps).formatted(), caption: dayName(day.startMs))
                }
                if let resting = insights?.resting {
                    GlanceTile(
                        symbol: "heart", title: "Resting heart rate", number: String(localized: "\(Int(resting.latest)) bpm"),
                        caption: resting.usual.map { String(localized: "Usually \(Int($0))") } ?? String(localized: "\(Int(resting.nightsNeeded)) more nights to show this")
                    )
                }
            }
        }
    }

    private var actions: some View {
        ActionGroup(title: "Watch") {
            GlassActionButton(
                title: watch?.ringing == true ? "Stop ringing" : "Find watch",
                symbol: watch?.ringing == true ? "bell.slash.fill" : "applewatch.radiowaves.left.and.right",
                prominent: watch?.ringing == true
            ) {
                model.ringWatch(phone.id, on: watch?.ringing != true)
            }
            .disabled(!reachable)
            .help(reachable ? "" : "The watch is not connected to its phone")
            GlassActionButton(title: "Sleep and workouts", symbol: "bed.double") { AppScenes.showSettings(.health) }
            GlassActionButton(title: "Open \(phone.name)", symbol: "iphone") { onOpenPhone() }
        }
    }

    /// What is set for the watch, kept on its phone, which passes it on: the same choices as there, so they can be made from here.
    private var choices: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                SettingRow(symbol: "pencil", title: "Name", subtitle: "\(name). Your phone and your other computers show it by this name too.") {
                    Button("Rename") {
                        newName = name
                        renaming = true
                    }
                    .buttonStyle(.tandemGlass)
                    .disabled(!phone.online)
                    .help(phone.online ? "" : "Its phone is not connected")
                }
                rowDivider
                // Over Bluetooth straight to this Mac, not by way of the phone: the watch is paired with it as its mouse.
                SettingRow(symbol: "speaker.wave.2", title: "Use the watch as a speaker", subtitle: "Over Bluetooth, in phone call quality. Only when you choose it here") {
                    Toggle("", isOn: Binding(
                        get: { speaker },
                        set: { on in
                            if WatchAudio.shared.choose(on) { speaker = on }
                            else { model.showToast(String(localized: "Connect the watch to this Mac over Bluetooth first")) }
                        }
                    ))
                    .controlSize(.mini)
                }
                if let settings {
                    rowDivider
                    toggle(
                        symbol: "cursorarrow.motionlines", title: "Use the watch as a mouse",
                        subtitle: "Steers a computer, only while the mouse screen is open on the unlocked watch",
                        on: settings.mouse
                    ) { TandemWatchSettingsChange(mouse: $0) }
                    rowDivider
                    toggle(
                        symbol: "moon", title: "Quiet while you sleep",
                        subtitle: "Your phone goes into a quiet mode of its own while the watch records your sleep, and out of it when you wake up",
                        on: settings.sleepQuiet
                    ) { TandemWatchSettingsChange(sleepQuiet: $0) }
                    rowDivider
                    toggle(
                        symbol: "alarm", title: "Wake me gently",
                        subtitle: "Five minutes before your alarm your watch starts buzzing, in long pulses that grow from soft to strong",
                        on: settings.smartWake
                    ) { TandemWatchSettingsChange(smartWake: $0) }
                    rowDivider
                    toggle(
                        symbol: "heart", title: "Heart rate all day",
                        subtitle: "Half a minute in every five while you wear your watch. Off saves battery.",
                        on: settings.heartAllDay
                    ) { TandemWatchSettingsChange(heartAllDay: $0) }
                    rowDivider
                    SettingRow(symbol: "figure.walk", title: "Step goal", subtitle: "Steps a day. Your watch shows it too.") {
                        HStack(spacing: 8) {
                            Text(verbatim: Int(settings.stepGoal).formatted()).monospacedDigit().contentTransition(.numericText())
                            Stepper(
                                "Step goal",
                                value: Binding(
                                    get: { Int(settings.stepGoal) },
                                    set: { model.setWatchSettings(phone.id, TandemWatchSettingsChange(stepGoal: UInt32($0))) }
                                ),
                                in: Self.stepGoals,
                                step: Self.stepGoalStep
                            )
                        }
                        .disabled(settingsWhy != nil)
                        .help(settingsWhy ?? "")
                    }
                } else {
                    // A phone with an older Tandem does not say them: a switch that shows off could be wrong, so none is shown.
                    rowDivider
                    SettingRow(
                        symbol: "arrow.down.circle", title: "More choices for the watch",
                        subtitle: "Update Tandem on \(phone.name) to make them from here"
                    ) { EmptyView() }
                }
            }
        }
    }

    private var rowDivider: some View {
        Divider().opacity(0.4).padding(.horizontal, 14)
    }

    private func toggle(
        symbol: String, title: LocalizedStringKey, subtitle: LocalizedStringKey, on: Bool, change: @escaping (Bool) -> TandemWatchSettingsChange
    ) -> some View {
        SettingRow(symbol: symbol, title: title, subtitle: subtitle) {
            Toggle("", isOn: Binding(get: { on }, set: { model.setWatchSettings(phone.id, change($0)) }))
                .controlSize(.mini)
                .disabled(settingsWhy != nil)
                .help(settingsWhy ?? "")
        }
    }

    /// The goals the phone offers, so both say the same.
    private static let stepGoals = 2000...30000
    private static let stepGoalStep = 500

    /// At the very bottom of the page: it is done once, if ever, and should not sit between the things that are used.
    private var removal: some View {
        Card(radius: Metrics.card, padding: 6) {
            SettingRow(symbol: "trash", title: "Unpair this watch", subtitle: "Pairing it again is done on your phone") {
                Button("Unpair", role: .destructive) { confirmUnpair = true }
                    .buttonStyle(.tandemGlass)
                    .disabled(!phone.online)
                    .help(phone.online ? "" : "Its phone is not connected")
            }
        }
    }
}

/// One number of what the watch measured, which opens the page with all of it.
private struct GlanceTile: View {
    let symbol: String
    let title: LocalizedStringKey
    let number: String
    let caption: String

    var body: some View {
        Button { AppScenes.showSettings(.health) } label: {
            Card(radius: Metrics.card, padding: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Label(title, systemImage: symbol)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(verbatim: number)
                        .font(.title2.weight(.bold))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(verbatim: caption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .help("Open sleep and workouts")
    }
}
